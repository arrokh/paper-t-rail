"use client";

import { useSearchParams } from "next/navigation";
import { ChevronDown } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Skeleton } from "@/components/ui/skeleton";
import { AnalysisPipelineChart } from "@/features/analysis-runs/components/analysis-pipeline-chart";
import { AnalysisRunStageResults } from "@/features/analysis-runs/components/analysis-run-stage-results";
import { WorkspaceBreadcrumb } from "@/features/workspace/components/workspace-breadcrumb";
import { normalizePipelineStageId, type PipelineStageId } from "@/features/analysis-runs/pipeline";
import {
  useAnalysisRun,
  useParsedDocument,
  useReferenceResolutionReport,
} from "@/features/analysis-runs/queries/analysis-run-queries";
import type { AnalysisRun } from "@/features/analysis-runs/types";
import { cn } from "@/lib/utils";

const STATUS_CLASS_NAMES: Record<AnalysisRun["status"], string> = {
  QUEUED: "border-border bg-muted text-muted-foreground",
  PROCESSING: "border-primary/25 bg-primary/10 text-primary",
  PARSED: "border-primary/20 bg-primary/5 text-primary",
  COMPLETED: "border-primary/20 bg-primary/5 text-primary",
  COMPLETED_WITH_WARNINGS: "border-warning/40 bg-warning/10 text-warning-foreground",
  FAILED: "border-destructive/25 bg-destructive/10 text-destructive",
};

function statusLabel(status: AnalysisRun["status"]): string {
  return status.replaceAll("_", " ").toLowerCase();
}

function isParsedDocumentReady(status: AnalysisRun["status"]): boolean {
  return status === "PARSED" || status === "COMPLETED" || status === "COMPLETED_WITH_WARNINGS";
}

function formatDate(value: string | null): string {
  if (!value) return "Not recorded";
  return new Intl.DateTimeFormat("en-GB", {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "Asia/Jakarta",
  }).format(new Date(value));
}

function homeHrefFor(searchParams: ReturnType<typeof useSearchParams>, focusRunId?: string): string {
  const params = new URLSearchParams();
  const query = searchParams.get("q");
  const status = searchParams.get("status");
  const cursor = searchParams.get("cursor");
  if (query) params.set("q", query);
  if (status) params.set("status", status);
  if (cursor) params.set("cursor", cursor);
  if (focusRunId) params.set("focus", focusRunId);
  const suffix = params.size > 0 ? `?${params.toString()}` : "";
  return `/${suffix}`;
}

function analysisRunBreadcrumbItems(currentLabel: string, listHref: string, backHref = listHref) {
  return [
    { label: "Back to Analysis Runs", href: backHref, backButton: true },
    { label: "Workspace", href: "/" },
    { label: "Analysis Runs", href: listHref },
    { label: currentLabel },
  ] as const;
}

function updatePipelineQuery(
  step: PipelineStageId,
) {
  const params = new URLSearchParams(window.location.search);
  params.set("step", step);
  params.delete("substep");
  const query = params.toString();
  const nextUrl = `${window.location.pathname}${query ? `?${query}` : ""}${window.location.hash}`;
  window.history.replaceState(null, "", nextUrl);
}

function RunStatusBadge({ status }: { status: AnalysisRun["status"] }) {
  return <Badge variant="outline" className={cn("shrink-0 capitalize", STATUS_CLASS_NAMES[status])}>{statusLabel(status)}</Badge>;
}

function AnalysisRunProvenance({ run }: { run: AnalysisRun }) {
  return (
    <Collapsible className="group/provenance rounded-xl border border-border bg-card shadow-sm">
      <CollapsibleTrigger className="group flex min-h-14 w-full items-center justify-between gap-4 rounded-xl px-4 py-3 text-left focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50 sm:px-5">
        <span className="min-w-0">
          <span className="block font-medium">Run provenance</span>
          <span className="mt-1 block text-xs text-muted-foreground">Source integrity, timestamps, and the provider configuration pinned to this Analysis Run</span>
        </span>
        <ChevronDown className="size-4 shrink-0 text-muted-foreground transition-transform group-data-[open]:rotate-180" aria-hidden="true" />
      </CollapsibleTrigger>
      <CollapsibleContent className="h-[var(--collapsible-panel-height)] overflow-hidden transition-[height] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] data-[starting-style]:h-0 data-[ending-style]:h-0 motion-reduce:transition-none">
        <div className="translate-y-0 border-t border-border px-4 py-4 opacity-100 transition-[opacity,translate] duration-200 ease-out group-data-[closed]/provenance:-translate-y-1 group-data-[closed]/provenance:opacity-0 motion-reduce:transition-none sm:px-5">
          <dl className="grid gap-x-6 gap-y-4 sm:grid-cols-2">
            <div className="space-y-1"><dt className="font-mono text-xs uppercase text-muted-foreground">Analysis Run ID</dt><dd className="m-0 break-all font-mono text-xs">{run.id}</dd></div>
            <div className="space-y-1"><dt className="font-mono text-xs uppercase text-muted-foreground">Source Document ID</dt><dd className="m-0 break-all font-mono text-xs">{run.documentId}</dd></div>
            <div className="space-y-1"><dt className="font-mono text-xs uppercase text-muted-foreground">Created</dt><dd className="m-0 text-sm">{formatDate(run.createdAt)}</dd></div>
            <div className="space-y-1"><dt className="font-mono text-xs uppercase text-muted-foreground">Started</dt><dd className="m-0 text-sm">{formatDate(run.startedAt)}</dd></div>
            <div className="min-w-0 space-y-1 sm:col-span-2"><dt className="font-mono text-xs uppercase text-muted-foreground">Source SHA-256</dt><dd className="m-0 break-all font-mono text-xs leading-relaxed">{run.sourceContentSha256}</dd></div>
            <div className="min-w-0 space-y-1 sm:col-span-2">
              <dt className="font-mono text-xs uppercase text-muted-foreground">Pinned providers</dt>
              <dd className="m-0 break-words text-sm leading-relaxed">
                Claims {run.configuration.claimExtractor.provider} · Embeddings {run.configuration.embedding.provider} · Evidence {run.configuration.systemOne.provider} · Bibliography {run.configuration.referenceResolution?.provider?.provider ?? "not configured"} · Cited full text {run.configuration.openAccess?.provider ?? "not configured"}
              </dd>
            </div>
            {run.configuration.externalProviderConsents && run.configuration.externalProviderConsents.length > 0 && (
              <div className="min-w-0 space-y-1 sm:col-span-2">
                <dt className="font-mono text-xs uppercase text-muted-foreground">Per-run external provider consent</dt>
                <dd className="m-0 text-sm leading-relaxed">
                  {run.configuration.externalProviderConsents.map((consent) => `${consent.providerId}: ${consent.dataCategories.join(", ")}`).join(" · ")}
                </dd>
              </div>
            )}
          </dl>
        </div>
      </CollapsibleContent>
    </Collapsible>
  );
}

export function AnalysisRunDetailPage({ analysisRunId }: { analysisRunId: string }) {
  const searchParams = useSearchParams();
  const runQuery = useAnalysisRun(analysisRunId);
  const run = runQuery.data ?? null;
  const stageParam = searchParams.get("step");
  const selectedStage = normalizePipelineStageId(stageParam);
  const sourceStep = run?.pipeline?.stages.find((stage) => stage.id === "source")?.steps.find((step) => step.id === "parse-document");
  const parsedReady = Boolean(run && (sourceStep?.status === "COMPLETED" || isParsedDocumentReady(run.status)));
  const parsedQuery = useParsedDocument(analysisRunId, parsedReady);
  const reportQuery = useReferenceResolutionReport(analysisRunId, parsedReady);
  const parsedDocument = parsedQuery.data ?? null;
  const report = reportQuery.data ?? null;
  const parsedError = parsedQuery.error instanceof Error ? parsedQuery.error.message : parsedQuery.isError ? "Could not load the parsed Source Document." : null;
  const reportError = reportQuery.error instanceof Error ? reportQuery.error.message : reportQuery.isError ? "Could not load the Evidence Coverage Report." : null;
  const homeHref = homeHrefFor(searchParams);

  if (runQuery.isPending) {
    return (
      <div className="analysis-run-detail space-y-6">
        <WorkspaceBreadcrumb items={analysisRunBreadcrumbItems("Analysis Run details", homeHref)} />
        <div role="status" aria-label="Loading Analysis Run details">
          <span className="sr-only">Loading Analysis Run details</span>
          <Skeleton className="h-32 w-full" />
        </div>
        <Skeleton className="h-52 w-full" />
        <Skeleton className="h-72 w-full" />
      </div>
    );
  }

  if (!run) {
    return (
      <section className="analysis-run-detail space-y-6" aria-labelledby="missing-run-heading">
        <WorkspaceBreadcrumb items={analysisRunBreadcrumbItems("Unavailable", homeHref)} />
        <Alert variant="destructive">
          <AlertTitle id="missing-run-heading">Analysis Run unavailable</AlertTitle>
          <AlertDescription>{runQuery.error instanceof Error ? runQuery.error.message : "This Analysis Run could not be found in the local workspace."}</AlertDescription>
        </Alert>
      </section>
    );
  }

  const backHref = homeHrefFor(searchParams, run.id);

  function selectStage(stage: PipelineStageId) {
    updatePipelineQuery(stage);
  }

  return (
    <article className="analysis-run-detail space-y-6" aria-labelledby="analysis-run-heading">
      <WorkspaceBreadcrumb items={analysisRunBreadcrumbItems(run.filename, backHref)} />
      <header className="space-y-5">
        <div className="flex flex-col gap-4 border-b border-border/70 pb-5 sm:flex-row sm:items-start sm:justify-between">
          <div className="min-w-0 space-y-2">
            <p className="font-mono text-xs tracking-[0.13em] text-muted-foreground uppercase">Analysis Run details</p>
            <h1 id="analysis-run-heading" className="break-words font-serif text-2xl font-semibold tracking-tight sm:text-4xl">{run.filename}</h1>
            <p className="flex flex-wrap items-center gap-x-3 gap-y-2 text-sm text-muted-foreground">
              <span>Created {formatDate(run.createdAt)}</span>
              <RunStatusBadge status={run.status} />
            </p>
          </div>
        </div>
      </header>

      {run.status === "FAILED" && run.failureReason && (
        <Alert variant="destructive" className="analysis-run-failure">
          <AlertTitle>Analysis Run failed</AlertTitle>
          <AlertDescription>{run.failureReason}</AlertDescription>
        </Alert>
      )}

      <AnalysisRunProvenance run={run} />

      <AnalysisPipelineChart run={run} selectedStage={selectedStage} onSelectStage={selectStage} />

      {selectedStage && (
        <AnalysisRunStageResults
          run={run}
          selectedStage={selectedStage}
          backHref={backHref}
          parsedDocument={parsedDocument}
          report={report}
          parsedLoading={parsedReady && parsedQuery.isPending}
          reportLoading={parsedReady && reportQuery.isPending}
          parsedError={parsedError}
          reportError={reportError}
          onSelectStage={selectStage}
        />
      )}
    </article>
  );
}
