"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useQueries, useQueryClient } from "@tanstack/react-query";
import { Check, ChevronDown, ChevronRight, Copy, LoaderCircle, Trash2 } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { LocalDateTime } from "@/components/local-date-time";
import { normalizePipelineStageId, PIPELINE_STAGES } from "@/features/analysis-runs/pipeline";
import {
  executionSpanQueryKey,
  executionSpanQueryOptions,
  useExecutionArtifact,
  useExecutionSpan,
  useExecutionSpans,
  useExecutionSummary,
  useRemoveExecutionArtifact,
  useStopExecutionCapture,
} from "@/features/analysis-runs/queries/analysis-run-queries";
import {
  executionGapReasonDescription,
  executionSpanReasonCode,
  executionStatusLabel,
  filterExecutionSpans,
  getExecutionAncestorContext,
  formatExecutionDuration,
  isActiveExecutionSpan,
  getExecutionTimeline,
} from "@/features/analysis-runs/execution/execution-trace-utils";
import type {
  ExecutionArtifactDescriptor,
  ExecutionArtifactRole,
  ExecutionSpan,
  ExecutionSpanFilters,
  ExecutionSummary,
} from "@/features/analysis-runs/execution/execution-types";
import { cn } from "@/lib/utils";

type InspectorArtifactSection = "input" | "response" | "result";
const POLL_ERROR_MESSAGE = "Execution details could not be loaded.";
const INLINE_ARTIFACT_SIZE_LIMIT_BYTES = 64 * 1024;
const EXECUTION_GRID_COLUMNS = "grid-cols-[minmax(0,1fr)_5rem] sm:grid-cols-[minmax(12rem,1.2fr)_5rem_minmax(12rem,2fr)]";
const INSPECTOR_ASIDE_CLASS_NAME = "min-w-0 border-t border-border bg-background lg:sticky lg:top-4 lg:max-h-[calc(100dvh-2rem)] lg:self-start lg:overflow-y-auto lg:border-t-0 lg:border-l";

function isTerminalExecutionStatus(status: string): boolean {
  return ["PARSED", "COMPLETED", "COMPLETED_WITH_WARNINGS", "FAILED"].includes(status);
}

function elapsedMillisFromSummary(summary: ExecutionSummary, terminalRun: boolean, now: number): number | null {
  if (summary.totalDurationMillis !== null) return Math.max(0, summary.totalDurationMillis);
  const startedAt = summary.startedAt ? Date.parse(summary.startedAt) : Number.NaN;
  if (!Number.isFinite(startedAt)) return null;
  const finishedAt = summary.finishedAt ? Date.parse(summary.finishedAt) : Number.NaN;
  if (Number.isFinite(finishedAt)) return Math.max(0, finishedAt - startedAt);
  if (!terminalRun && summary.recordingState === "RECORDING") return Math.max(0, now - startedAt);
  return null;
}

function formatElapsed(summary: ExecutionSummary, terminalRun: boolean, now: number): string {
  const elapsed = elapsedMillisFromSummary(summary, terminalRun, now);
  return elapsed === null ? "Not recorded" : formatExecutionDuration(elapsed);
}

function statusBadgeClass(status: string): string {
  const normalized = status.toUpperCase();
  if (["FAILED", "ERROR"].includes(normalized)) return "border-destructive/40 bg-destructive/10 text-destructive";
  if (["SUCCEEDED", "SUCCESS", "COMPLETED", "REUSED"].includes(normalized)) return "border-success/40 bg-success/50 text-success-foreground";
  if (["RUNNING", "IN_PROGRESS"].includes(normalized)) return "border-info/40 bg-info text-info-foreground";
  if (["INTERRUPTED"].includes(normalized)) return "border-warning/50 bg-warning/30 text-warning-foreground";
  return "border-border bg-muted text-muted-foreground";
}

function safeHttpRoute(span: ExecutionSpan): string | null {
  const routeCandidate = span.httpRoute ?? span.attributes.httpRoute ?? span.attributes.route;
  if (typeof routeCandidate !== "string" || !routeCandidate.trim()) return null;
  const route = routeCandidate.trim().replace(/^[A-Z]+\s+/i, "");
  const withoutQuery = route.split("?")[0].trim();
  try {
    if (/^https?:\/\//i.test(withoutQuery)) return new URL(withoutQuery).pathname;
  } catch {
    return null;
  }
  if (withoutQuery.startsWith("/")) return withoutQuery;
  const pathStart = withoutQuery.indexOf("/", withoutQuery.indexOf("://") + 3);
  if (pathStart > 0 && /^[^/]+\.[^/]+/.test(withoutQuery)) return withoutQuery.slice(pathStart);
  return withoutQuery;
}

function spanTrustBoundary(span: ExecutionSpan): string | null {
  const boundary = span.trustBoundary ?? span.attributes.trustBoundary;
  if (typeof boundary !== "string") return null;
  const normalized = boundary.toLowerCase();
  return ["internal", "local", "external"].includes(normalized) ? normalized : null;
}

function domainLinkLabel(type: string, id: string): string {
  const labels: Record<string, string> = {
    ANALYSIS_RUN: "Analysis Run",
    SOURCE_DOCUMENT: "Source Document",
    BIBLIOGRAPHY_ENTRY: "Bibliography entry",
  };
  return `${labels[type] ?? "Related run item"} · ${id.slice(0, 8)}`;
}

function SpanStatus({ status }: { status: string }) {
  return <Badge variant="outline" className={cn("h-5 shrink-0 px-1.5 text-[0.65rem] font-medium", statusBadgeClass(status))}>{executionStatusLabel(status)}</Badge>;
}

function spanChildren(spans: ExecutionSpan[]): Map<string, ExecutionSpan[]> {
  const result = new Map<string, ExecutionSpan[]>();
  for (const span of spans) {
    if (!span.parentSpanId) continue;
    const children = result.get(span.parentSpanId) ?? [];
    children.push(span);
    result.set(span.parentSpanId, children);
  }
  return result;
}

function stageLabel(stageId: string): string {
  const canonicalStageId = normalizePipelineStageId(stageId);
  return PIPELINE_STAGES.find((stage) => stage.id === canonicalStageId)?.label ?? stageId.replaceAll("-", " ");
}

function ExecutionRuler({ elapsedMillis }: { elapsedMillis: number }) {
  const midpoint = elapsedMillis / 2;
  return (
    <div className={cn("grid items-end gap-x-2 border-b border-border bg-muted/30 px-3 py-2 sm:gap-x-3 sm:px-4", EXECUTION_GRID_COLUMNS)}>
      <span className="min-w-0 text-xs font-medium text-muted-foreground">Operation / status</span>
      <span className="min-w-0 text-right text-xs font-medium text-muted-foreground">Duration</span>
      <div className="col-span-2 min-w-0 sm:col-span-1" role="img" aria-label={`Shared run-relative time axis from 0 to ${formatExecutionDuration(elapsedMillis)}`}>
        <div className="flex justify-between font-mono text-[0.65rem] text-muted-foreground">
          <span>0</span><span>{formatExecutionDuration(midpoint)}</span><span>{formatExecutionDuration(elapsedMillis)}</span>
        </div>
        <div className="mt-1 h-px bg-border" />
      </div>
    </div>
  );
}

type ExecutionSpanTreeContext = {
  depth: number;
  childrenByParent: Map<string, ExecutionSpan[]>;
  visibleIds: Set<string>;
  timelineRows: Map<string, ReturnType<typeof getExecutionTimeline>["rows"][number]>;
  selectedSpanId: string | null;
  autoExpandedSpanIds: Set<string>;
  filtersActive: boolean;
  expandedAll: boolean | null;
  openOverrides: Record<string, boolean>;
  onDisclosureChange: (id: string, open: boolean) => void;
  onSelect: (spanId: string) => void;
};

type LogicalOperationGroup = { key: string; spans: ExecutionSpan[] };

function ExecutionSpanRows({
  spans,
  groupSimilarOperations = true,
  ...context
}: { spans: ExecutionSpan[]; groupSimilarOperations?: boolean } & ExecutionSpanTreeContext) {
  const logicalGroups = new Map<string, ExecutionSpan[]>();
  for (const span of spans) {
    const groupKey = JSON.stringify([span.parentSpanId, span.operationId, span.stageId, span.kind, span.name]);
    const group = logicalGroups.get(groupKey) ?? [];
    group.push(span);
    logicalGroups.set(groupKey, group);
  }

  const families = new Map<string, LogicalOperationGroup[]>();
  for (const [key, operationSpans] of logicalGroups) {
    const span = operationSpans[0];
    const familyKey = JSON.stringify([span.parentSpanId, span.stageId, span.kind, span.name]);
    const family = families.get(familyKey) ?? [];
    family.push({ key, spans: operationSpans });
    families.set(familyKey, family);
  }

  return (
    <>
      {[...families].flatMap(([familyKey, operations]) => {
        const familySpans = operations.flatMap((operation) => operation.spans);
        if (groupSimilarOperations && operations.length >= 3) {
          const first = familySpans[0];
          const kind = first.kind.toUpperCase();
          const unit = kind === "QUEUE" ? "intervals" : kind.includes("PROVIDER") ? "calls" : "operations";
          const failureCount = familySpans.filter((span) => ["FAILED", "ERROR"].includes(span.status.toUpperCase())).length;
          const groupId = `similar:${familyKey}`;
          const selectedWithin = familySpans.some((span) => span.id === context.selectedSpanId || context.autoExpandedSpanIds.has(span.id));
          const isOpen = context.openOverrides[groupId] ?? (
            context.expandedAll === true || (context.expandedAll === null && selectedWithin)
          );
          return (
            <div key={groupId} role="listitem" className="min-w-0 border-b border-border/60 last:border-b-0">
              <Collapsible open={isOpen} onOpenChange={(open) => context.onDisclosureChange(groupId, open)}>
                <CollapsibleTrigger className="group flex min-h-9 min-w-0 w-full flex-wrap items-center gap-2 px-3 text-left text-sm font-medium whitespace-normal focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50 sm:px-4">
                  <ChevronDown className="size-4 shrink-0 transition-transform group-data-[closed]:-rotate-90" aria-hidden="true" />
                  <span className="min-w-0 flex-1 [overflow-wrap:anywhere]">{first.name || first.kind}</span>
                  <Badge variant="outline" className="shrink-0 text-[0.65rem]">{operations.length} {unit}</Badge>
                  {failureCount > 0 && <Badge variant="outline" className={cn("shrink-0 text-[0.65rem]", statusBadgeClass("FAILED"))}>{failureCount} failed</Badge>}
                </CollapsibleTrigger>
                <CollapsibleContent className="border-l border-border/70">
                  <div role="list">
                    <ExecutionSpanRows spans={familySpans} {...context} groupSimilarOperations={false} />
                  </div>
                </CollapsibleContent>
              </Collapsible>
            </div>
          );
        }

        return operations.flatMap(({ key, spans: attempts }) => {
          const attemptNumbers = new Set(attempts.map((span) => span.attempt));
          if (attempts.length === 1 || attemptNumbers.size !== attempts.length) {
            return attempts.map((span) => (
              <ExecutionSpanTreeRow key={span.id} span={span} {...context} timelineRow={context.timelineRows.get(span.id)!} />
            ));
          }
          const firstAttempt = attempts[0];
          const groupId = `attempts:${key}`;
          const isOpen = context.openOverrides[groupId] ?? context.expandedAll !== false;
          return (
            <div key={groupId} role="listitem" className="min-w-0 border-b border-border/60 last:border-b-0">
              <Collapsible open={isOpen} onOpenChange={(open) => context.onDisclosureChange(groupId, open)}>
                <CollapsibleTrigger className="group flex min-h-9 min-w-0 w-full flex-wrap items-center gap-2 px-3 text-left text-sm font-medium whitespace-normal focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50 sm:px-4">
                  <ChevronDown className="size-4 shrink-0 transition-transform group-data-[closed]:-rotate-90" aria-hidden="true" />
                  <span className="min-w-0 flex-1 [overflow-wrap:anywhere]">{firstAttempt.name || firstAttempt.kind}</span>
                  <Badge variant="outline" className="shrink-0 text-[0.65rem]">{attempts.length} attempts</Badge>
                </CollapsibleTrigger>
                <CollapsibleContent className="border-l border-border/70">
                  <div role="list">
                    {attempts.map((span) => (
                      <ExecutionSpanTreeRow
                        key={span.id}
                        span={span}
                        {...context}
                        depth={context.depth + 1}
                        timelineRow={context.timelineRows.get(span.id)!}
                      />
                    ))}
                  </div>
                </CollapsibleContent>
              </Collapsible>
            </div>
          );
        });
      })}
    </>
  );
}

function ExecutionSpanTreeRow({
  span,
  depth,
  childrenByParent,
  visibleIds,
  timelineRow,
  timelineRows,
  selectedSpanId,
  autoExpandedSpanIds,
  filtersActive,
  expandedAll,
  openOverrides,
  onDisclosureChange,
  onSelect,
}: ExecutionSpanTreeContext & {
  span: ExecutionSpan;
  timelineRow: ReturnType<typeof getExecutionTimeline>["rows"][number];
}) {
  const stageGroupId = normalizePipelineStageId(span.stageId) ?? "other";
  const children = (childrenByParent.get(span.id) ?? []).filter((child) =>
    visibleIds.has(child.id) && (normalizePipelineStageId(child.stageId) ?? "other") === stageGroupId,
  );
  const hasChildren = children.length > 0;
  const isRunning = isActiveExecutionSpan(span);
  const stageOpen = span.kind.toLowerCase() === "stage"
    || span.name.toLowerCase() === stageLabel(span.stageId).toLowerCase();
  const shouldExpandByDefault = stageOpen && depth === 0;
  const isOpen = openOverrides[span.id] ?? (
    expandedAll === true
    || (expandedAll === null && (
      autoExpandedSpanIds.has(span.id)
      || (filtersActive && hasChildren)
      || shouldExpandByDefault
    ))
  );
  const timelineStyle = {
    left: `${timelineRow.leftPercent}%`,
    width: timelineRow.isInstant ? "0.3rem" : `${timelineRow.widthPercent}%`,
  };
  const name = span.name || span.kind;
  return (
    <div role="listitem" className="min-w-0 border-b border-border/60 last:border-b-0">
      <div className={cn(
        "grid min-h-10 items-center gap-x-2 gap-y-0.5 px-3 py-0.5 sm:min-h-9 sm:gap-x-3 sm:gap-y-0 sm:px-4 sm:py-0",
        EXECUTION_GRID_COLUMNS,
        selectedSpanId === span.id && "bg-primary/5",
      )}>
        <div className="flex min-w-0 items-center gap-1" style={{ paddingInlineStart: `${Math.min(depth, 6) * 0.25}rem` }}>
          {hasChildren ? (
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              className="size-6 shrink-0 sm:size-8"
              aria-label={`${isOpen ? "Collapse" : "Expand"} ${name}`}
              aria-expanded={isOpen}
              onClick={() => onDisclosureChange(span.id, !isOpen)}
            >
              {isOpen ? <ChevronDown className="size-4" aria-hidden="true" /> : <ChevronRight className="size-4" aria-hidden="true" />}
            </Button>
          ) : <span className="inline-block size-6 shrink-0 sm:size-8" aria-hidden="true" />}
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="h-auto min-h-8 min-w-0 flex-1 flex-wrap justify-start gap-2 rounded-sm px-1 py-1 text-left whitespace-normal"
            aria-label={`${name}, ${executionStatusLabel(span.status)}, ${isRunning ? `Running, ${formatExecutionDuration(timelineRow.durationMillis)}` : formatExecutionDuration(timelineRow.durationMillis)}`}
            title={`${name} · Status: ${executionStatusLabel(span.status)} (${span.status})`}
            aria-pressed={selectedSpanId === span.id}
            onClick={() => onSelect(span.id)}
          >
            <span className="min-w-0 flex-1 [overflow-wrap:anywhere] text-sm font-medium text-foreground">{name}</span>
            <SpanStatus status={span.status} />
            {span.attempt > 1 && <Badge variant="outline" className="h-5 shrink-0 px-1.5 text-[0.65rem]">Attempt {span.attempt}</Badge>}
          </Button>
        </div>
        <span className="min-w-0 text-right font-mono text-xs tabular-nums text-muted-foreground [overflow-wrap:anywhere]" title={isRunning ? `Running · ${formatExecutionDuration(timelineRow.durationMillis)}` : formatExecutionDuration(timelineRow.durationMillis)}>
          {isRunning ? `Running · ${formatExecutionDuration(timelineRow.durationMillis)}` : formatExecutionDuration(timelineRow.durationMillis)}
        </span>
        <div className="col-span-2 h-3 min-w-0 sm:col-span-1" aria-hidden="true">
          <div className="relative h-full border-l border-border/70 bg-[linear-gradient(to_right,var(--ptr-border)_1px,transparent_1px)] bg-[length:25%_100%]">
            <span
              className={cn(
                "absolute top-1/2 h-2 -translate-y-1/2 rounded-sm bg-info-foreground/75",
                span.status.toUpperCase() === "FAILED" && "bg-destructive/80",
                isRunning && "animate-pulse motion-reduce:animate-none",
                timelineRow.isInstant && "w-1.5 rounded-full",
              )}
              style={timelineStyle}
            />
          </div>
        </div>
      </div>
      {hasChildren && isOpen && (
        <div role="list" className="border-l border-border/70" style={{ marginInlineStart: `${Math.min(depth + 1, 6) * 0.5}rem` }}>
          <ExecutionSpanRows
            spans={children}
            depth={depth + 1}
            childrenByParent={childrenByParent}
            visibleIds={visibleIds}
            timelineRows={timelineRows}
            selectedSpanId={selectedSpanId}
            autoExpandedSpanIds={autoExpandedSpanIds}
            filtersActive={filtersActive}
            expandedAll={expandedAll}
            openOverrides={openOverrides}
            onDisclosureChange={onDisclosureChange}
            onSelect={onSelect}
          />
        </div>
      )}
    </div>
  );

}

function ArtifactCapture({
  analysisRunId,
  spanId,
  descriptor,
  onRemove,
  removing,
}: {
  analysisRunId: string;
  spanId: string;
  descriptor: ExecutionArtifactDescriptor;
  onRemove: (artifactId: string) => void;
  removing: boolean;
}) {
  const artifactQuery = useExecutionArtifact(analysisRunId, descriptor.id, spanId, descriptor.role);
  const hasArtifactId = descriptor.id !== null;
  const artifact = artifactQuery.data;
  const [copied, setCopied] = useState(false);
  const [copyFailed, setCopyFailed] = useState(false);
  const artifactMatchesRequest = !artifact || (artifact.id === descriptor.id && artifact.spanId === spanId && artifact.role === descriptor.role);
  const fidelity = artifactMatchesRequest ? (artifact?.fidelity ?? descriptor.fidelity).toLowerCase() : "unavailable";
  const mediaType = artifactMatchesRequest ? artifact?.mediaType ?? descriptor.mediaType : descriptor.mediaType;
  const sizeBytes = artifactMatchesRequest ? artifact?.sizeBytes ?? descriptor.sizeBytes : descriptor.sizeBytes;
  const reason = artifactMatchesRequest ? artifact?.reason ?? descriptor.reason : "The artifact response did not match this operation and is unavailable.";
  const displayReason = reason === "UNSUPPORTED_OR_UNSAFE_FIELDS" ? null : reason;
  const content = artifactMatchesRequest ? artifact?.content : null;
  const contentSizeBytes = typeof content === "string"
    ? sizeBytes ?? new TextEncoder().encode(content).byteLength
    : null;
  const useArtifactDialog = contentSizeBytes !== null && contentSizeBytes > INLINE_ARTIFACT_SIZE_LIMIT_BYTES;
  const removed = fidelity === "removed";

  async function copySanitizedContent() {
    if (typeof content !== "string" || !navigator.clipboard) {
      setCopyFailed(true);
      return;
    }
    try {
      await navigator.clipboard.writeText(content);
      setCopied(true);
      setCopyFailed(false);
    } catch {
      setCopyFailed(true);
    }
  }

  function confirmRemoval() {
    const approved = window.confirm("Remove this deduplicated artifact body from all linked spans in this Analysis Run? The analysis result will not change.");
    if (approved && descriptor.id !== null) onRemove(descriptor.id);
  }

  return (
    <section className="space-y-2 border-b border-border/60 py-3 last:border-b-0" aria-label={`${descriptor.role.toLowerCase()} artifact`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <span className="font-medium capitalize">{((artifactMatchesRequest ? artifact?.role : null) ?? descriptor.role).toLowerCase()}</span>
          <Badge variant="outline" className="text-[0.65rem]">{fidelityLabel(fidelity)}</Badge>
          <span className="font-mono text-[0.65rem] text-muted-foreground">{mediaType ?? "Media type unknown"} · {sizeBytes ?? "Unknown size"} bytes</span>
        </div>
        {typeof content === "string" && (
          <div className="flex flex-wrap gap-2">
            {useArtifactDialog && (
              <Dialog>
                <DialogTrigger render={<Button type="button" variant="outline" size="sm" className="min-h-10" />}>Open full artifact</DialogTrigger>
                <DialogContent className="grid min-w-0 max-h-[90dvh] max-w-[calc(100%-2rem)] grid-cols-[minmax(0,1fr)] grid-rows-[auto_minmax(0,1fr)] gap-4 overflow-hidden sm:max-w-[calc(100%-2rem)] lg:max-w-5xl">
                  <DialogHeader className="min-w-0 pr-10">
                    <DialogTitle className="break-words">Full {descriptor.role.toLowerCase()} artifact</DialogTitle>
                    <DialogDescription>The complete captured text is selectable and displayed as text, not HTML.</DialogDescription>
                  </DialogHeader>
                  <pre role="region" tabIndex={0} aria-label={`${descriptor.role.toLowerCase()} artifact content`} className="m-0 min-h-0 max-h-[70dvh] min-w-0 overflow-auto overscroll-contain rounded-md border border-border bg-muted/30 p-3 font-mono text-xs leading-relaxed whitespace-pre-wrap [overflow-wrap:anywhere] select-text">{content}</pre>
                </DialogContent>
              </Dialog>
            )}
            <Button type="button" variant="outline" size="sm" className="min-h-10" onClick={copySanitizedContent}>
              {copied ? <Check className="size-3.5" aria-hidden="true" /> : <Copy className="size-3.5" aria-hidden="true" />}
              {copied ? "Copied sanitized content" : "Copy sanitized content"}
            </Button>
          </div>
        )}
      </div>
      {displayReason && (typeof content === "string" || artifactQuery.isPending || artifactQuery.isError || removed) && <p className="m-0 text-sm text-muted-foreground">{displayReason}</p>}
      {copyFailed && <p className="m-0 text-sm text-destructive" role="alert">Could not copy this sanitized artifact to the clipboard.</p>}
      {hasArtifactId && artifactQuery.isPending && <Skeleton className="h-24 w-full" aria-label="Loading captured artifact" />}
      {hasArtifactId && artifactQuery.isError && <Alert variant="destructive"><AlertTitle>Artifact unavailable</AlertTitle><AlertDescription>{permissionError(artifactQuery.error)}</AlertDescription></Alert>}
      {(!hasArtifactId || (!artifactQuery.isPending && !artifactQuery.isError)) && (removed || content == null) && (
        <p className="m-0 rounded-md border border-border bg-muted/30 p-3 text-sm text-muted-foreground">
          {removed ? "This artifact was removed. Its captured content is no longer available." : displayReason ?? "No artifact content was captured for this operation."}
        </p>
      )}
      {typeof content === "string" && !artifactQuery.isError && !useArtifactDialog && (
        <pre className="max-h-80 overflow-auto rounded-md border border-border bg-muted/30 p-3 font-mono text-xs leading-relaxed whitespace-pre-wrap [overflow-wrap:anywhere] select-text">{content}</pre>
      )}
      {artifact && artifactMatchesRequest && <p className="m-0 break-words font-mono text-[0.65rem] text-muted-foreground">Schema {artifact.schemaVersion ?? "unknown"} · Capture {artifact.captureVersion ?? "unknown"} · Sanitizer {artifact.sanitizerVersion ?? "unknown"}</p>}
      {hasArtifactId && fidelity !== "removed" && typeof content === "string" && (
        <Button type="button" variant="ghost" size="sm" className="min-h-10" disabled={removing} onClick={confirmRemoval}>
          <Trash2 className="size-3.5" aria-hidden="true" /> Remove artifact body
        </Button>
      )}
      <span className="sr-only">Artifact for span {spanId}</span>
    </section>
  );
}

function fidelityLabel(fidelity: string): string {
  const labels: Record<string, string> = {
    complete: "Complete",
    sanitized: "Sanitized",
    partial: "Partial",
    omitted: "Omitted",
    removed: "Removed",
    unavailable: "Unavailable",
  };
  return labels[fidelity] ?? "Unavailable";
}

function permissionError(error: unknown): string {
  const message = error instanceof Error ? error.message : POLL_ERROR_MESSAGE;
  return /403|forbidden|permission denied/i.test(message)
    ? "Permission denied. This workspace cannot inspect the captured artifact."
    : message;
}

function InspectorArtifacts({
  analysisRunId,
  detail,
  section,
  onRemove,
  removing,
}: {
  analysisRunId: string;
  detail: NonNullable<ReturnType<typeof useExecutionSpan>["data"]>;
  section: InspectorArtifactSection;
  onRemove: (artifactId: string) => void;
  removing: boolean;
}) {
  const roleSet: ExecutionArtifactRole[] = section === "input"
    ? ["INPUT", "REQUEST"]
    : section === "response"
      ? ["RESPONSE"]
      : ["RESULT"];
  const descriptors = detail.artifactRoles.filter((descriptor) => roleSet.includes(descriptor.role));
  if (!descriptors.length) {
    return <p className="m-0 rounded-md border border-border bg-muted/30 p-4 text-sm text-muted-foreground">No {section === "input" ? "input or request" : section} artifact was recorded for this operation.</p>;
  }
  return (
    <div className="divide-y divide-border/60">
      {descriptors.map((descriptor) => (
        <ArtifactCapture
          key={`${descriptor.role}:${descriptor.id ?? descriptor.fidelity}`}
          analysisRunId={analysisRunId}
          spanId={detail.id}
          descriptor={descriptor}
          onRemove={onRemove}
          removing={removing}
        />
      ))}
    </div>
  );
}

function SpanInspector({ analysisRunId, spanId }: { analysisRunId: string; spanId: string | null }) {
  const detailQuery = useExecutionSpan(analysisRunId, spanId);
  const removeMutation = useRemoveExecutionArtifact(analysisRunId);
  if (!spanId) {
    return (
      <aside className={cn(INSPECTOR_ASIDE_CLASS_NAME, "flex min-h-64 items-center justify-center p-5")} aria-label="Selected operation details">
        <div className="max-w-sm space-y-2 text-center">
          <h3 className="m-0 font-serif text-lg font-semibold">Choose an operation</h3>
          <p className="m-0 text-sm text-muted-foreground">Select a row to see its timing, safe metadata, and any captured artifacts.</p>
        </div>
      </aside>
    );
  }
  if (detailQuery.isPending) {
    return <aside className={cn(INSPECTOR_ASIDE_CLASS_NAME, "space-y-3 p-5")} aria-label="Selected operation details" tabIndex={0}><Skeleton className="h-6 w-2/3" /><Skeleton className="h-20 w-full" /><Skeleton className="h-32 w-full" /></aside>;
  }
  if (detailQuery.isError || !detailQuery.data) {
    return (
      <aside className={cn(INSPECTOR_ASIDE_CLASS_NAME, "p-5")} aria-label="Selected operation details">
        <Alert variant="destructive"><AlertTitle>Operation details unavailable</AlertTitle><AlertDescription>{permissionError(detailQuery.error)}</AlertDescription></Alert>
      </aside>
    );
  }
  const detail = detailQuery.data;
  const safeRoute = safeHttpRoute(detail);
  const trustBoundary = spanTrustBoundary(detail);
  const stage = detail.stageId ? stageLabel(detail.stageId) : null;
  const reasonCode = executionSpanReasonCode(detail);
  const safeAttributes = [
    ["Queue wait", detail.attributes.queueWaitMillis],
    ["Retry backoff", detail.attributes.retryBackoffMillis],
    ["Capture overhead", detail.attributes.captureOverheadMillis],
    ["First response", detail.attributes.firstChunkLatencyMillis],
  ].filter(([, value]) => typeof value === "number") as Array<[string, number]>;

  return (
    <aside className={INSPECTOR_ASIDE_CLASS_NAME} aria-label="Selected operation details" tabIndex={0}>
      <div className="space-y-1 px-4 py-4 sm:px-5">
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="m-0 min-w-0 break-words font-serif text-xl font-semibold tracking-tight">{detail.name}</h3>
          <SpanStatus status={detail.status} />
        </div>
        <p className="m-0 text-sm text-muted-foreground">{detail.kind} · Attempt {detail.attempt} · {formatExecutionDuration(detail.durationMillis)}</p>
      </div>
      <Separator />
      {removeMutation.isError && (
        <div className="px-4 py-3 sm:px-5">
          <Alert variant="destructive"><AlertTitle>Artifact removal failed</AlertTitle><AlertDescription>{permissionError(removeMutation.error)}</AlertDescription></Alert>
        </div>
      )}
      <section aria-labelledby="selected-operation-overview-heading" className="space-y-3 p-4 sm:p-5">
        <h4 id="selected-operation-overview-heading" className="m-0 text-sm font-semibold">Overview</h4>
        <dl className="grid min-w-0 grid-cols-[minmax(6rem,0.8fr)_minmax(0,1.2fr)] gap-x-3 gap-y-3 text-sm">
          <dt className="text-muted-foreground">Execution span</dt><dd className="m-0 break-all font-mono text-xs">{detail.id}</dd>
          <dt className="text-muted-foreground">Logical operation</dt><dd className="m-0 break-all font-mono text-xs">{detail.operationId}</dd>
          <dt className="text-muted-foreground">Kind</dt><dd className="m-0 break-words">{detail.kind}</dd>
          {stage && <><dt className="text-muted-foreground">Pipeline stage</dt><dd className="m-0 break-words">{stage}</dd></>}
          {trustBoundary && <><dt className="text-muted-foreground">Trust boundary</dt><dd className="m-0 capitalize">{trustBoundary}</dd></>}
          {detail.providerId && <><dt className="text-muted-foreground">Provider</dt><dd className="m-0 break-words">{detail.providerId}</dd></>}
          {detail.modelId && <><dt className="text-muted-foreground">Model</dt><dd className="m-0 break-words">{detail.modelId}</dd></>}
          {safeRoute && <><dt className="text-muted-foreground">HTTP route</dt><dd className="m-0 break-all font-mono text-xs">{safeRoute}</dd></>}
          {detail.httpStatus !== null && <><dt className="text-muted-foreground">HTTP status</dt><dd className="m-0">{detail.httpStatus}</dd></>}
          {detail.safeErrorCode && <><dt className="text-muted-foreground">Safe error code</dt><dd className="m-0 break-words font-mono text-xs">{detail.safeErrorCode}</dd></>}
          {reasonCode && <><dt className="text-muted-foreground">Persisted reason</dt><dd className="m-0 break-words font-mono text-xs">{reasonCode}</dd></>}
          <dt className="text-muted-foreground">Started</dt><dd className="m-0 break-words text-sm tabular-nums"><LocalDateTime value={detail.startedAt} /></dd>
          {detail.endedAt && <><dt className="text-muted-foreground">Finished</dt><dd className="m-0 break-words text-sm tabular-nums"><LocalDateTime value={detail.endedAt} /></dd></>}
          <dt className="text-muted-foreground">Duration</dt><dd className="m-0">{formatExecutionDuration(detail.durationMillis)} <span className="text-xs text-muted-foreground">(includes capture overhead)</span></dd>
          {safeAttributes.map(([label, value]) => <div key={label} className="contents"><dt className="text-muted-foreground">{label}</dt><dd className="m-0">{formatExecutionDuration(value)}</dd></div>)}
        </dl>
        {detail.domainLinks?.some((link) => link.href.startsWith("/") && !link.href.startsWith("//")) && (
          <div className="space-y-2 border-t border-border pt-4">
            <h5 className="m-0 text-sm font-semibold">Related run items</h5>
            {detail.domainLinks.filter((link) => link.href.startsWith("/") && !link.href.startsWith("//")).map((link) => <a key={`${link.type}:${link.id}:${link.href}`} className="block break-words text-sm text-primary underline-offset-4 hover:underline" href={link.href}>{domainLinkLabel(link.type, link.id)}</a>)}
          </div>
        )}
      </section>
      {(["input", "response", "result"] as const).map((section) => {
        const heading = section === "input" ? "Input / Request" : section === "response" ? "Response" : "Result";
        const headingId = `selected-operation-${section}-heading`;
        return (
          <div key={section}>
            <Separator />
            <section aria-labelledby={headingId} className="space-y-3 p-4 sm:p-5">
              <h4 id={headingId} className="m-0 text-sm font-semibold">{heading}</h4>
              <InspectorArtifacts
                analysisRunId={analysisRunId}
                detail={detail}
                section={section}
                onRemove={(artifactId) => removeMutation.mutate(artifactId)}
                removing={removeMutation.isPending}
              />
            </section>
          </div>
        );
      })}
    </aside>
  );
}

export function AnalysisRunExecution({
  analysisRunId,
  runStatus,
  selectedSpanId,
  onSelectSpan,
}: {
  analysisRunId: string;
  runStatus: string;
  selectedSpanId: string | null;
  onSelectSpan: (spanId: string | null) => void;
}) {
  const terminalRun = isTerminalExecutionStatus(runStatus);
  const summaryQuery = useExecutionSummary(analysisRunId, true, terminalRun);
  const { data: summary, refetch: refetchSummary } = summaryQuery;
  useEffect(() => {
    if (terminalRun && summary?.recordingState === "RECORDING") {
      void refetchSummary();
    }
  }, [terminalRun, summary?.recordingState, refetchSummary]);
  const recording = summary?.recordingState === "RECORDING";
  const spansQuery = useExecutionSpans(
    analysisRunId,
    Boolean(summary && summary.recordingState !== "NOT_RECORDED" && summary.completeness !== "NOT_RECORDED"),
    terminalRun,
    recording,
  );
  const { refetch: refetchSpans } = spansQuery;
  const terminalSpanRefreshPending = useRef(!terminalRun);
  useEffect(() => {
    if (!terminalRun) {
      terminalSpanRefreshPending.current = true;
      return;
    }
    if (!terminalSpanRefreshPending.current || spansQuery.isPending || !spansQuery.data) return;
    terminalSpanRefreshPending.current = false;
    void refetchSpans();
  }, [terminalRun, spansQuery.isPending, spansQuery.data, refetchSpans]);
  const selectedSpanQuery = useExecutionSpan(analysisRunId, selectedSpanId);
  const selectedSpanDetail = selectedSpanQuery.data?.id === selectedSpanId ? selectedSpanQuery.data : null;
  const [filters, setFilters] = useState<ExecutionSpanFilters>({ query: "", stage: "all", status: "all", kind: "all" });
  const [expandedAll, setExpandedAll] = useState<boolean | null>(null);
  const [openOverrides, setOpenOverrides] = useState<Record<string, boolean>>({});
  const pages = spansQuery.data?.pages;
  const pageSpans = useMemo(() => pages?.flatMap((page) => page.items) ?? [], [pages]);
  const queryClient = useQueryClient();
  const ancestorContext = useMemo(() => getExecutionAncestorContext(
    selectedSpanDetail,
    pageSpans,
    (spanId) => queryClient.getQueryData<ExecutionSpan>(executionSpanQueryKey(analysisRunId, spanId)),
  ), [selectedSpanDetail, pageSpans, queryClient, analysisRunId]);
  const ancestorQuery = useQueries({
    queries: [executionSpanQueryOptions(analysisRunId, ancestorContext.missingAncestorSpanId ?? "")],
  })[0];
  const ancestorContextSpan = ancestorQuery.data?.id === ancestorContext.missingAncestorSpanId
    ? ancestorQuery.data
    : null;
  const autoExpandedSpanIds = useMemo(() => new Set([
    ...ancestorContext.spans.map((span) => span.id),
    ...(ancestorContextSpan ? [ancestorContextSpan.id] : []),
  ]), [ancestorContext.spans, ancestorContextSpan]);
  const spans = useMemo(() => {
    const byId = new Map(pageSpans.map((span) => [span.id, span]));
    ancestorContext.spans.forEach((span) => byId.set(span.id, span));
    if (ancestorContextSpan) byId.set(ancestorContextSpan.id, ancestorContextSpan);
    if (selectedSpanDetail) byId.set(selectedSpanDetail.id, selectedSpanDetail);
    return [...byId.values()];
  }, [pageSpans, ancestorContext.spans, ancestorContextSpan, selectedSpanDetail]);
  const previousSpanStatuses = useRef<Map<string, string> | null>(null);
  const [spanStatusAnnouncement, setSpanStatusAnnouncement] = useState("");
  useEffect(() => {
    const previous = previousSpanStatuses.current;
    previousSpanStatuses.current = new Map(spans.map((span) => [span.id, span.status]));
    if (!previous) return;

    const changed = spans.filter((span) => previous.has(span.id) && previous.get(span.id) !== span.status);
    if (changed.length === 0) return;
    const transitions = changed.slice(0, 2).map((span) => `${span.name}: ${executionStatusLabel(span.status).toLowerCase()}`);
    setSpanStatusAnnouncement(changed.length > 2
      ? `${changed.length} operation statuses changed.`
      : `Execution status changed: ${transitions.join("; ")}.`);
  }, [spans]);
  const filteredIds = useMemo(() => filterExecutionSpans(spans, filters), [spans, filters]);
  const filtersActive = filters.query.trim() !== "" || filters.stage !== "all" || filters.status !== "all" || filters.kind !== "all";
  const executionNow = Math.max(summaryQuery.dataUpdatedAt ?? 0, spansQuery.dataUpdatedAt ?? 0);
  const timelineStartedAt = summary?.startedAt ?? null;
  const recordingActive = Boolean(summary && !terminalRun && summary.recordingState === "RECORDING");
  const timelineDurationMillis = summary ? elapsedMillisFromSummary(summary, terminalRun, executionNow) : null;
  const timeline = useMemo(
    () => getExecutionTimeline(spans, timelineStartedAt, timelineDurationMillis, executionNow, recordingActive),
    [spans, timelineStartedAt, timelineDurationMillis, executionNow, recordingActive],
  );
  const timelineRows = useMemo(() => new Map(timeline.rows.map((row) => [row.span.id, row])), [timeline.rows]);
  const childrenByParent = useMemo(() => spanChildren(spans), [spans]);
  const visibleRows = timeline.rows.filter((row) => filteredIds.has(row.span.id));
  const stageGroups = useMemo(() => {
    const groups = new Map<string, ExecutionSpan[]>();
    const spansById = new Map(spans.map((span) => [span.id, span]));
    for (const span of spans) {
      if (!filteredIds.has(span.id)) continue;
      const groupId = normalizePipelineStageId(span.stageId) ?? "other";
      const parent = span.parentSpanId ? spansById.get(span.parentSpanId) : undefined;
      const parentGroupId = parent ? normalizePipelineStageId(parent.stageId) ?? "other" : null;
      if (parentGroupId === groupId) continue;
      const group = groups.get(groupId) ?? [];
      group.push(span);
      groups.set(groupId, group);
    }
    return groups;
  }, [spans, filteredIds]);
  const stageOrder = [...PIPELINE_STAGES.map((stage) => stage.id), "other"];
  const visibleStages = stageOrder.filter((stageId) => stageGroups.has(stageId));
  const kinds = [...new Set(spans.map((span) => span.kind))].sort();
  const statuses = [...new Set(spans.map((span) => span.status))].sort();
  const stopCapture = useStopExecutionCapture(analysisRunId);

  function changeFilter(key: keyof ExecutionSpanFilters, value: string) {
    setExpandedAll(null);
    setOpenOverrides({});
    setFilters((current) => ({ ...current, [key]: value }));
  }

  function updateDisclosure(id: string, open: boolean) {
    setOpenOverrides((current) => ({ ...current, [id]: open }));
  }

  function selectSpan(spanId: string) {
    if (expandedAll === false) setExpandedAll(null);
    onSelectSpan(spanId);
  }

  if (summaryQuery.isPending) {
    return <section className="space-y-4" aria-labelledby="execution-heading"><Skeleton className="h-16 w-full" /><Skeleton className="h-72 w-full" aria-label="Loading execution trace" /></section>;
  }
  if (summaryQuery.isError || !summary) {
    return <section className="space-y-4" aria-labelledby="execution-heading"><Alert variant="destructive"><AlertTitle>Execution unavailable</AlertTitle><AlertDescription>{permissionError(summaryQuery.error)}</AlertDescription></Alert></section>;
  }

  if (summary.recordingState === "NOT_RECORDED" || summary.completeness === "NOT_RECORDED") {
    return (
      <section className="space-y-4" aria-labelledby="execution-heading">
        <header className="space-y-1"><h2 id="execution-heading" className="m-0 font-serif text-2xl font-semibold tracking-tight">Execution Trace</h2><p className="m-0 text-sm text-muted-foreground">Processing steps, timings, and service calls for this run.</p></header>
        <Alert>
          <AlertTitle>Historical execution unavailable</AlertTitle>
          <AlertDescription>This Analysis Run predates execution recording. Its execution history was not recorded, so no timeline can be reconstructed.</AlertDescription>
        </Alert>
      </section>
    );
  }

  const elapsedLabel = formatElapsed(summary, terminalRun, executionNow);
  const traceMayBeIncomplete = summary.completeness === "INCOMPLETE" || (terminalRun && summary.recordingState === "RECORDING");
  const captureWasStopped = summary.captureRequested === true && !summary.captureEnabled;
  const recordingStatusLabel = summary.recordingState === "RECORDING"
    ? terminalRun ? "Run ended; recording state not finalized" : "Recording"
    : summary.recordingState === "STOPPED"
      ? terminalRun && !captureWasStopped ? "Recording complete" : "Recording stopped"
      : "Not recorded";
  const stageName = (stageId: string) => stageId === "other" ? "Other operations" : stageLabel(stageId);

  return (
    <section className="min-w-0 space-y-4" aria-labelledby="execution-heading">
      <header className="space-y-3">
        <div className="space-y-1">
          <h2 id="execution-heading" className="m-0 font-serif text-2xl font-semibold tracking-tight sm:text-3xl">Execution Trace</h2>
          <p className="m-0 text-sm text-muted-foreground">Processing steps, timings, and service calls for this run.</p>
        </div>
        <dl className="m-0 flex flex-wrap items-baseline gap-x-6 gap-y-2 border-y border-border py-3 text-sm">
          <div className="flex items-baseline gap-2"><dt className="text-xs text-muted-foreground">Elapsed</dt><dd className="m-0 font-mono tabular-nums">{elapsedLabel}</dd></div>
          <div className="flex items-baseline gap-2"><dt className="text-xs text-muted-foreground">Recording</dt><dd className="m-0" aria-live="polite">{recordingStatusLabel}</dd></div>
          <div className="flex items-baseline gap-2"><dt className="text-xs text-muted-foreground">Payload capture at start</dt><dd className="m-0">{summary.captureRequested === null ? "Unavailable" : summary.captureRequested ? "Enabled" : "Disabled"}</dd></div>
          {traceMayBeIncomplete && <div className="contents"><dt className="sr-only">Trace completeness</dt><dd className="m-0"><Badge variant="outline" className="border-warning/50 bg-warning/20 text-warning-foreground">Incomplete trace</Badge></dd></div>}
        </dl>
      </header>
      <p className="sr-only" role="status" aria-live="polite" aria-atomic="true">{spanStatusAnnouncement}</p>

      {summary.recordingState === "RECORDING" && !summary.captureEnabled && (
        <Alert>
          <AlertTitle>{terminalRun ? "Execution recording not finalized" : "Payload capture is disabled"}</AlertTitle>
          <AlertDescription>{terminalRun
            ? "The Analysis Run is terminal while this summary still reports recording. The trace may be incomplete."
            : "Execution spans continue to record, but no new payload artifacts will be captured."}</AlertDescription>
        </Alert>
      )}
      {traceMayBeIncomplete && (
        <Alert>
          <AlertTitle>Some execution details may be missing</AlertTitle>
          <AlertDescription>
            {summary.completeness === "INCOMPLETE" ? (
              <>
                {executionGapReasonDescription(summary.gapReason)} This trace gap does not change the Analysis Run result. Missing spans are not reconstructed.
                {summary.gapReason && <span className="mt-1 block text-xs">Reason code: <code>{summary.gapReason}</code></span>}
              </>
            ) : "The Analysis Run is terminal, but execution recording is not finalized. This trace may contain gaps."}
          </AlertDescription>
        </Alert>
      )}
      {spansQuery.isError && <Alert variant="destructive"><AlertTitle>Could not load operations</AlertTitle><AlertDescription>{permissionError(spansQuery.error)}</AlertDescription></Alert>}

      <div className="flex flex-col gap-3 border-y border-border py-3 sm:flex-row sm:flex-wrap sm:items-end sm:justify-between">
        <div className="grid min-w-0 flex-1 gap-3 sm:grid-cols-2 lg:grid-cols-[minmax(12rem,1.2fr)_repeat(3,minmax(8rem,0.7fr))]">
          <label className="space-y-1 text-xs font-medium text-muted-foreground">
            <span>Find operation</span>
            <Input value={filters.query} onChange={(event) => changeFilter("query", event.target.value)} placeholder="Name, provider, model, or error" className="h-11" />
          </label>
          <label className="space-y-1 text-xs font-medium text-muted-foreground">
            <span>Pipeline stage</span>
            <NativeSelect value={filters.stage} onChange={(event) => changeFilter("stage", event.target.value)} className="h-11 w-full" selectClassName="h-11">
              <NativeSelectOption value="all">All stages</NativeSelectOption>
              {PIPELINE_STAGES.map((stage) => <NativeSelectOption key={stage.id} value={stage.id}>{stage.label}</NativeSelectOption>)}
            </NativeSelect>
          </label>
          <label className="space-y-1 text-xs font-medium text-muted-foreground">
            <span>Status</span>
            <NativeSelect value={filters.status} onChange={(event) => changeFilter("status", event.target.value)} className="h-11 w-full" selectClassName="h-11">
              <NativeSelectOption value="all">All statuses</NativeSelectOption>
              {statuses.map((status) => <NativeSelectOption key={status} value={status}>{executionStatusLabel(status)}</NativeSelectOption>)}
            </NativeSelect>
          </label>
          <label className="space-y-1 text-xs font-medium text-muted-foreground">
            <span>Kind</span>
            <NativeSelect value={filters.kind} onChange={(event) => changeFilter("kind", event.target.value)} className="h-11 w-full" selectClassName="h-11">
              <NativeSelectOption value="all">All kinds</NativeSelectOption>
              {kinds.map((kind) => <NativeSelectOption key={kind} value={kind}>{kind.replaceAll("_", " ")}</NativeSelectOption>)}
            </NativeSelect>
          </label>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button type="button" size="sm" variant="outline" className="h-11" onClick={() => { setExpandedAll(expandedAll === true ? false : true); setOpenOverrides({}); }}>
            {expandedAll === true ? "Collapse all" : "Expand all"}
          </Button>
          {summary.recordingState === "RECORDING" && summary.captureEnabled && !terminalRun && (
            <Button type="button" size="sm" variant="outline" className="h-11" disabled={stopCapture.isPending} onClick={() => stopCapture.mutate()}>
              {stopCapture.isPending && <LoaderCircle className="size-3.5 animate-spin motion-reduce:animate-none" aria-hidden="true" />}
              Stop future capture
            </Button>
          )}
        </div>
        {stopCapture.isError && <p role="alert" className="m-0 basis-full text-sm text-destructive">{permissionError(stopCapture.error)}</p>}
      </div>

      {captureWasStopped && <p className="m-0 text-sm text-muted-foreground">Execution recording was stopped; operations and artifacts already recorded remain available.</p>}

      {spansQuery.isPending && (
        <div className="space-y-2" aria-label="Loading operations"><Skeleton className="h-12 w-full" /><Skeleton className="h-12 w-full" /><Skeleton className="h-12 w-full" /></div>
      )}
      {!spansQuery.isPending && !spansQuery.isError && spans.length === 0 && (
        <Alert><AlertTitle>No operations yet</AlertTitle><AlertDescription>{summary.recordingState === "RECORDING" ? "Execution spans will appear as operations are recorded." : "No execution operations were recorded for this Analysis Run."}</AlertDescription></Alert>
      )}
      {!spansQuery.isPending && spans.length > 0 && visibleRows.length === 0 && (
        <p className="rounded-md border border-border bg-muted/20 p-5 text-sm text-muted-foreground">No operations match these filters.</p>
      )}

      <div className="grid min-w-0 gap-0 rounded-md border border-border bg-background lg:grid-cols-[minmax(0,1.65fr)_minmax(20rem,0.85fr)]">
        <div className="min-w-0">
          <ExecutionRuler elapsedMillis={timeline.elapsedMillis} />
          {visibleStages.length > 0 ? (
            <div role="list" aria-label="Execution operations">
              {visibleStages.map((stageId) => {
                const stageSpans = stageGroups.get(stageId) ?? [];
                const stageKey = `stage:${stageId}`;
                const stageOpen = openOverrides[stageKey] ?? expandedAll !== false;
                const rowContext = {
                  depth: 0,
                  childrenByParent,
                  visibleIds: filteredIds,
                  timelineRows,
                  selectedSpanId,
                  autoExpandedSpanIds,
                  filtersActive,
                  expandedAll,
                  openOverrides,
                  onDisclosureChange: updateDisclosure,
                  onSelect: selectSpan,
                };
                const operationCount = new Set(stageSpans.map((span) => span.operationId)).size;
                return (
                  <div key={stageId} role="listitem" className="border-b border-border last:border-b-0">
                    <Collapsible open={stageOpen} onOpenChange={(open) => updateDisclosure(stageKey, open)}>
                      <div className="flex min-h-9 min-w-0 items-center gap-2 bg-muted/25 px-3 sm:px-4">
                        <CollapsibleTrigger className="group flex min-h-9 min-w-0 flex-1 flex-wrap items-center gap-x-2 gap-y-1 text-left text-xs font-semibold text-muted-foreground whitespace-normal focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
                          <ChevronDown className="size-3.5 shrink-0 transition-transform group-data-[closed]:-rotate-90" aria-hidden="true" />
                          <span className="min-w-0 [overflow-wrap:anywhere]">{stageId === "other" ? "Other operations" : `Pipeline stage: ${stageName(stageId)}`}</span>
                          <span className="shrink-0 font-mono text-[0.65rem] font-normal">{operationCount} {operationCount === 1 ? "operation" : "operations"}</span>
                        </CollapsibleTrigger>
                      </div>
                      <CollapsibleContent>
                        <div role="list">
                          <ExecutionSpanRows spans={stageSpans} {...rowContext} />
                        </div>
                      </CollapsibleContent>
                    </Collapsible>
                  </div>
                );
              })}
            </div>
          ) : !spansQuery.isPending && spans.length > 0 && <p className="m-0 p-5 text-sm text-muted-foreground">No operations match these filters.</p>}
          {spansQuery.isPending && <p className="m-0 border-t border-border px-4 py-2 text-xs text-muted-foreground">Loading all operations…</p>}
          {traceMayBeIncomplete && <p className="m-0 border-t border-border px-4 py-2 text-xs text-muted-foreground">This trace may contain gaps. Captured execution is not evidence that every operation was recorded.</p>}
        </div>
        <SpanInspector key={selectedSpanId ?? "no-selection"} analysisRunId={analysisRunId} spanId={selectedSpanId} />
      </div>
    </section>
  );
}
