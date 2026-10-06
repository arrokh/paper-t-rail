import { normalizePipelineStageId } from "../pipeline";
import type { ExecutionGapReason, ExecutionSpan, ExecutionSpanFilters } from "./execution-types";

export const MAX_SELECTED_SPAN_ANCESTORS = 12;

export type ExecutionTimelineRow = {
  span: ExecutionSpan;
  leftPercent: number;
  widthPercent: number;
  durationMillis: number | null;
  isInstant: boolean;
};

export type ExecutionTimeline = {
  elapsedMillis: number;
  startedAtMillis: number | null;
  rows: ExecutionTimelineRow[];
};

function parseTimestamp(value: string | null | undefined): number | null {
  if (!value) return null;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : null;
}

export function isActiveExecutionSpan(span: ExecutionSpan): boolean {
  return span.endedAt === null && ["RUNNING", "IN_PROGRESS"].includes(span.status.toUpperCase());
}

function spanDuration(span: ExecutionSpan, now: number): number | null {
  if (!isActiveExecutionSpan(span) && span.durationMillis !== null && Number.isFinite(span.durationMillis)) {
    return Math.max(0, span.durationMillis);
  }
  const startedAt = parseTimestamp(span.startedAt);
  if (startedAt === null) return null;
  if (span.endedAt === null && !isActiveExecutionSpan(span)) return span.durationMillis;
  const endedAt = parseTimestamp(span.endedAt) ?? now;
  return Math.max(0, endedAt - startedAt);
}

export function getExecutionTimeline(
  spans: ExecutionSpan[],
  summaryStartedAt: string | null,
  summaryDurationMillis: number | null,
  now = Date.now(),
  recordingActive = spans.some(isActiveExecutionSpan),
): ExecutionTimeline {
  const spanStarts = spans.map((span) => parseTimestamp(span.startedAt)).filter((value): value is number => value !== null);
  const startedAtMillis = parseTimestamp(summaryStartedAt) ?? (spanStarts.length ? Math.min(...spanStarts) : null);
  const latestKnownOffset = spans.reduce((latest, span) => {
    const start = parseTimestamp(span.startedAt);
    const end = parseTimestamp(span.endedAt) ?? (isActiveExecutionSpan(span) ? now : null);
    if (startedAtMillis === null || start === null || end === null) return latest;
    return Math.max(latest, end - startedAtMillis);
  }, 0);
  const currentElapsed = startedAtMillis === null ? 0 : Math.max(0, now - startedAtMillis);
  const elapsedMillis = Math.max(
    0,
    summaryDurationMillis ?? 0,
    latestKnownOffset,
    recordingActive ? currentElapsed : 0,
  );

  const rows = spans.map((span): ExecutionTimelineRow => {
    const start = parseTimestamp(span.startedAt);
    const relativeStart = startedAtMillis === null || start === null ? 0 : Math.max(0, start - startedAtMillis);
    const durationMillis = spanDuration(span, now);
    const safeElapsed = elapsedMillis || durationMillis || 1;
    return {
      span,
      leftPercent: Math.min(100, (relativeStart / safeElapsed) * 100),
      widthPercent: !durationMillis ? 0 : Math.min(100, (durationMillis / safeElapsed) * 100),
      durationMillis,
      isInstant: durationMillis === 0,
    };
  });

  return { elapsedMillis, startedAtMillis, rows };
}

export function formatExecutionDuration(durationMillis: number | null): string {
  if (durationMillis === null || !Number.isFinite(durationMillis)) return "Unknown duration";
  if (durationMillis < 1_000) return `${Math.max(0, Math.round(durationMillis))} ms`;
  const seconds = durationMillis / 1_000;
  const formatted = Number.isInteger(seconds) ? String(seconds) : seconds.toFixed(1).replace(/\.0$/, "");
  return `${formatted} sec`;
}

const EXECUTION_GAP_REASON_DESCRIPTIONS: Record<ExecutionGapReason, string> = {
  UNSAFE_SPAN_METADATA_OMITTED: "An operation's provider or model metadata did not meet trace safety rules, so its span was omitted.",
  UNSAFE_SPAN_RESULT_OMITTED: "An operation result did not meet trace safety rules, so its span was omitted.",
  INTERVAL_TIMESTAMPS_UNAVAILABLE: "Required interval timestamps were unavailable.",
  SPAN_STORAGE_UNAVAILABLE: "Span storage was unavailable for part of the run.",
  RETRY_SCHEDULE_TIMESTAMPS_UNAVAILABLE: "A retry could not be timestamped.",
  QUEUE_ENQUEUE_TIMESTAMP_UNAVAILABLE: "A queued operation could not be timestamped.",
  UNSAFE_ARTIFACT_METADATA_OMITTED: "Artifact metadata did not meet trace safety rules and was omitted.",
  ARTIFACT_STORAGE_UNAVAILABLE: "Artifact storage was unavailable for part of the run.",
  UNSAFE_SPAN_ATTRIBUTES_OMITTED: "Some operation attributes did not meet trace safety rules and were omitted.",
  CAPTURE_STOPPED: "Trace capture was stopped before the Analysis Run finished.",
  INTERRUPTED_OPERATION: "An operation was still running when the Analysis Run ended.",
};

export function executionGapReasonDescription(gapReason: ExecutionGapReason | null): string {
  return gapReason ? EXECUTION_GAP_REASON_DESCRIPTIONS[gapReason] ?? "One or more operation spans could not be recorded." : "One or more operation spans could not be recorded.";
}

export function getExecutionAncestorContext(
  selectedSpan: ExecutionSpan | null,
  spans: ExecutionSpan[],
  getCachedSpan: (spanId: string) => ExecutionSpan | undefined,
): { spans: ExecutionSpan[]; missingAncestorSpanId: string | null } {
  if (!selectedSpan) return { spans: [], missingAncestorSpanId: null };

  const spansById = new Map(spans.map((span) => [span.id, span]));
  const visited = new Set([selectedSpan.id]);
  const ancestors: ExecutionSpan[] = [];
  let current = selectedSpan;

  for (let depth = 0; depth < MAX_SELECTED_SPAN_ANCESTORS; depth += 1) {
    const parentId = current.parentSpanId;
    if (!parentId || visited.has(parentId)) return { spans: ancestors, missingAncestorSpanId: null };
    visited.add(parentId);

    const parent = spansById.get(parentId) ?? getCachedSpan(parentId);
    if (!parent || parent.id !== parentId) return { spans: ancestors, missingAncestorSpanId: parentId };
    spansById.set(parent.id, parent);
    ancestors.push(parent);
    current = parent;
  }

  return { spans: ancestors, missingAncestorSpanId: null };
}

export function filterExecutionSpans(spans: ExecutionSpan[], filters: ExecutionSpanFilters): Set<string> {
  const byId = new Map(spans.map((span) => [span.id, span]));
  const normalizedQuery = filters.query.trim().toLowerCase();
  const matches = spans.filter((span) => {
    const textMatches = !normalizedQuery || [
      span.name,
      span.kind,
      span.providerId ?? "",
      span.modelId ?? "",
      span.safeErrorCode ?? "",
      span.operationId,
    ].some((value) => value.toLowerCase().includes(normalizedQuery));
    const stageMatches = filters.stage === "all" || normalizePipelineStageId(span.stageId) === filters.stage;
    const statusMatches = filters.status === "all" || span.status.toLowerCase() === filters.status.toLowerCase();
    const kindMatches = filters.kind === "all" || span.kind.toLowerCase() === filters.kind.toLowerCase();
    return textMatches && stageMatches && statusMatches && kindMatches;
  });

  const visible = new Set<string>();
  for (const match of matches) {
    let current: ExecutionSpan | undefined = match;
    while (current && !visible.has(current.id)) {
      visible.add(current.id);
      current = current.parentSpanId ? byId.get(current.parentSpanId) : undefined;
    }
  }
  return visible;
}

export function executionStatusLabel(status: string): string {
  const key = status.toUpperCase();
  const labels: Record<string, string> = {
    WAITING: "Waiting",
    PENDING: "Waiting",
    RUNNING: "Running",
    IN_PROGRESS: "Running",
    SUCCEEDED: "Succeeded",
    SUCCESS: "Succeeded",
    COMPLETED: "Succeeded",
    FAILED: "Failed",
    SKIPPED: "Skipped",
    REUSED: "Reused",
    INTERRUPTED: "Interrupted",
    UNKNOWN: "Unknown",
  };
  return labels[key] ?? status.replaceAll("_", " ").toLowerCase().replace(/^./, (character) => character.toUpperCase());
}
