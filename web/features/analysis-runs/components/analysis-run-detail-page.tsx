"use client";

import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import { ChevronDown } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { AnalysisPipelineChart } from "@/features/analysis-runs/components/analysis-pipeline-chart";
import { AnalysisRunPaperReview } from "@/features/analysis-runs/components/analysis-run-paper-review";
import { AnalysisRunDetailLoadingState } from "@/features/analysis-runs/components/analysis-run-loading";
import { AnalysisRunStageResults } from "@/features/analysis-runs/components/analysis-run-stage-results";
import { AnalysisRunExecution } from "@/features/analysis-runs/components/analysis-run-execution";
import { BackToTopFab } from "@/features/workspace/components/back-to-top-fab";
import { WorkspaceBreadcrumb } from "@/features/workspace/components/workspace-breadcrumb";
import { useWorkspaceShellState } from "@/features/workspace/components/workspace-shell-state";
import { normalizePipelineStageId, type PipelineStageId } from "@/features/analysis-runs/pipeline";
import {
  scrollToAnalysisPipelineCard,
  scrollToPaperReviewCard,
} from "@/features/analysis-runs/scroll-to-paper-review-card";
import { ANALYSIS_RUN_STATUS_CLASS_NAMES, analysisRunStatusLabel } from "@/features/analysis-runs/run-status";
import {
  useAnalysisRun,
  useParsedDocument,
  useReferenceResolutionReport,
} from "@/features/analysis-runs/queries/analysis-run-queries";
import type { AnalysisRun } from "@/features/analysis-runs/types";
import { cn } from "@/lib/utils";

const ANALYSIS_RUN_VIEW_TAB_CLASS_NAME = cn(
  "h-full min-w-0 px-2 text-xs font-semibold text-foreground/75 sm:min-w-32 sm:px-4 sm:text-sm",
  "hover:bg-accent hover:text-accent-foreground",
  "data-active:bg-primary data-active:text-primary-foreground data-active:shadow-sm",
  "data-active:hover:bg-primary data-active:hover:text-primary-foreground",
);

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

function updateQueryParameters(updates: Record<string, string | null>) {
  const params = new URLSearchParams(window.location.search);
  for (const [key, value] of Object.entries(updates)) {
    if (value === null) params.delete(key);
    else params.set(key, value);
  }
  const query = params.toString();
  const nextUrl = `${window.location.pathname}${query ? `?${query}` : ""}${window.location.hash}`;
  window.history.replaceState(null, "", nextUrl);
}

function RunStatusBadge({ status }: { status: AnalysisRun["status"] }) {
  return <Badge variant="outline" className={cn("shrink-0 capitalize", ANALYSIS_RUN_STATUS_CLASS_NAMES[status])}>{analysisRunStatusLabel(status)}</Badge>;
}

function AnalysisRunProvenance({ run }: { run: AnalysisRun }) {
  return (
    <Collapsible defaultOpen className="group/provenance rounded-xl border border-border bg-card shadow-sm">
      <CollapsibleTrigger className="group flex min-h-14 w-full items-center justify-between gap-4 rounded-xl px-4 py-3 text-left focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50 sm:px-5">
        <span className="min-w-0">
          <span className="block font-medium">Run provenance</span>
          <span className="mt-1 block text-xs text-muted-foreground">Source integrity, pinned providers, and consent records for this Analysis Run</span>
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
                <dd className="m-0 space-y-3 text-sm leading-relaxed">
                  {run.configuration.externalProviderConsents.map((consent) => (
                    <div key={consent.providerId} className="space-y-1">
                      <p className="m-0 font-medium">{consent.providerId}: {consent.dataCategories.join(", ")}</p>
                      <p className="m-0 text-muted-foreground">
                        {consent.retentionDisclosure ?? "No disclosure snapshot is available for this legacy Analysis Run."}
                      </p>
                    </div>
                  ))}
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
  const { setPaperReviewActive: setShellPaperReviewActive } = useWorkspaceShellState();
  const runQuery = useAnalysisRun(analysisRunId);
  const run = runQuery.data ?? null;
  const stageParam = searchParams.get("step");
  const selectedStage = normalizePipelineStageId(stageParam);
  const routeViewParam = searchParams.get("view");
  const routeSelectedView = routeViewParam === "review" ? "review" : routeViewParam === "execution" ? "execution" : "pipeline";
  const [selectedView, setSelectedView] = useState(routeSelectedView);
  const urlSelectedSpanId = searchParams.get("span");
  const [selectedExecutionSpanId, setSelectedExecutionSpanId] = useState(urlSelectedSpanId);
  const [previousUrlSelectedSpanId, setPreviousUrlSelectedSpanId] = useState(urlSelectedSpanId);
  if (urlSelectedSpanId !== previousUrlSelectedSpanId) {
    setPreviousUrlSelectedSpanId(urlSelectedSpanId);
    setSelectedExecutionSpanId(urlSelectedSpanId);
  }
  const isPaperReviewActive = selectedView === "review" || routeSelectedView === "review";
  const previousRouteView = useRef(routeSelectedView);
  const previousSelectedView = useRef(selectedView);
  const [hasOpenedPaperReview, setHasOpenedPaperReview] = useState(selectedView === "review");
  const selectedOutcomeId = searchParams.get("reviewPair");
  const selectedReferenceKey = searchParams.get("reviewReference");
  const reviewDetailParam = searchParams.get("reviewDetail");
  const selectedReviewDetail = reviewDetailParam === "citations"
    ? "citations"
    : reviewDetailParam === "results" || selectedOutcomeId || !selectedReferenceKey
      ? "results"
      : "citations";
  const sourceStep = run?.pipeline?.stages.find((stage) => stage.id === "source")?.steps.find((step) => step.id === "parse-document");
  const parsedReady = Boolean(run && (sourceStep?.status === "COMPLETED" || isParsedDocumentReady(run.status)));
  const parsedQuery = useParsedDocument(analysisRunId, parsedReady);
  const reportQuery = useReferenceResolutionReport(analysisRunId, parsedReady);
  const parsedDocument = parsedQuery.data ?? null;
  const report = reportQuery.data ?? null;
  const parsedError = parsedQuery.error instanceof Error ? parsedQuery.error.message : parsedQuery.isError ? "Could not load the parsed Source Document." : null;
  const reportError = reportQuery.error instanceof Error ? reportQuery.error.message : reportQuery.isError ? "Could not load the Evidence Coverage Report." : null;
  const homeHref = homeHrefFor(searchParams);

  useEffect(() => {
    setShellPaperReviewActive(isPaperReviewActive);
  }, [isPaperReviewActive, setShellPaperReviewActive]);

  useEffect(() => () => setShellPaperReviewActive(false), [setShellPaperReviewActive]);

  useEffect(() => {
    if (routeSelectedView === previousRouteView.current) return;
    previousRouteView.current = routeSelectedView;
    setSelectedView(routeSelectedView);
  }, [routeSelectedView]);

  useEffect(() => {
    if (previousSelectedView.current === selectedView) return;
    previousSelectedView.current = selectedView;

    const cardId = selectedView === "review" ? "paper-review-card" : selectedView === "execution" ? "execution-trace" : "analysis-pipeline-card";
    const panel = document.getElementById(cardId)?.closest<HTMLElement>(".analysis-run-view-panel");
    const scrollToSelectedView = () => {
      if (selectedView === "review") scrollToPaperReviewCard();
      else if (selectedView === "execution") document.getElementById("execution-trace")?.scrollIntoView({
        block: "start",
        behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth",
      });
      else scrollToAnalysisPipelineCard();
    };

    let animationFrame = 0;
    let fallbackTimer = 0;
    const finish = () => {
      window.clearTimeout(fallbackTimer);
      if (panel) panel.removeEventListener("animationend", onPanelAnimationEnd);
      scrollToSelectedView();
    };
    const onPanelAnimationEnd = (event: AnimationEvent) => {
      if (event.target !== panel || event.animationName !== "analysis-run-view-enter") return;
      finish();
    };

    if (!panel || window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      animationFrame = window.requestAnimationFrame(scrollToSelectedView);
      return () => window.cancelAnimationFrame(animationFrame);
    }

    panel.addEventListener("animationend", onPanelAnimationEnd);
    animationFrame = window.requestAnimationFrame(() => {
      if (!panel.matches("[data-starting-style]")) finish();
      else fallbackTimer = window.setTimeout(finish, 500);
    });

    return () => {
      window.cancelAnimationFrame(animationFrame);
      window.clearTimeout(fallbackTimer);
      panel.removeEventListener("animationend", onPanelAnimationEnd);
    };
  }, [selectedView]);

  if (runQuery.isPending) {
    return <AnalysisRunDetailLoadingState backHref={homeHref} showStageResults={Boolean(selectedStage)} />;
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
    updateQueryParameters({ step: stage, substep: null });
  }

  function selectReviewPair(outcomeId: string, localReferenceKey: string) {
    setHasOpenedPaperReview(true);
    setSelectedView("review");
    updateQueryParameters({ view: "review", reviewPair: outcomeId, reviewReference: localReferenceKey, reviewDetail: "results" });
  }

  function clearReviewPair() {
    updateQueryParameters({ reviewPair: null, reviewReference: null, reviewDetail: "results" });
  }

  function clearReviewReference() {
    updateQueryParameters({ reviewReference: null, reviewDetail: "citations" });
  }

  function clearReviewState() {
    updateQueryParameters({ reviewPair: null, reviewReference: null, reviewFilter: null });
  }

  function selectReviewReference(localReferenceKey: string) {
    setHasOpenedPaperReview(true);
    setSelectedView("review");
    const currentReferenceKey = new URLSearchParams(window.location.search).get("reviewReference");
    updateQueryParameters({
      view: "review",
      reviewReference: localReferenceKey,
      reviewPair: currentReferenceKey === localReferenceKey ? new URLSearchParams(window.location.search).get("reviewPair") : null,
      reviewDetail: "citations",
    });
  }

  function selectReviewDetail(detail: "results" | "citations") {
    updateQueryParameters({ view: "review", reviewDetail: detail });
  }

  function selectExecutionSpan(spanId: string | null) {
    setSelectedExecutionSpanId(spanId);
    setSelectedView("execution");
    updateQueryParameters({ view: "execution", span: spanId });
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

      <Tabs
        value={selectedView}
        onValueChange={(value) => {
          if (typeof value !== "string") return;
          const nextView = value === "review" ? "review" : value === "execution" ? "execution" : "pipeline";
          if (value === "review") setHasOpenedPaperReview(true);
          setSelectedView(nextView);
          updateQueryParameters({ view: nextView === "pipeline" ? null : nextView });
        }}
        className="gap-5"
      >
        <TabsList
          aria-label="Analysis Run views"
          className="mx-auto h-12 w-fit max-w-full rounded-lg border border-border bg-muted/70 p-1 shadow-sm"
        >
          <TabsTrigger value="pipeline" className={ANALYSIS_RUN_VIEW_TAB_CLASS_NAME}>
            Analysis Pipeline
          </TabsTrigger>
          <TabsTrigger
            value="review"
            onPointerEnter={() => setHasOpenedPaperReview(true)}
            onFocus={() => setHasOpenedPaperReview(true)}
            className={ANALYSIS_RUN_VIEW_TAB_CLASS_NAME}
          >
            Paper Review
          </TabsTrigger>
          <TabsTrigger value="execution" className={ANALYSIS_RUN_VIEW_TAB_CLASS_NAME}>
            Execution
          </TabsTrigger>
        </TabsList>
        <TabsContent value="pipeline" className="analysis-run-view-panel space-y-6">
          <AnalysisPipelineChart
            run={run}
            selectedStage={selectedStage}
            onSelectStage={selectStage}
            afterIntro={<AnalysisRunProvenance run={run} />}
          />
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
          {!selectedStage && (
            <section id="pipeline-results" aria-labelledby="pipeline-results-heading" className="pipeline-results">
              <Card className="shadow-sm">
                <CardContent className="flex min-h-40 flex-col items-center justify-center gap-1 py-10 text-center">
                  <h2 id="pipeline-results-heading" className="m-0 text-base font-semibold">Pipeline results</h2>
                  <p className="m-0 text-sm text-muted-foreground">Please select a pipeline stage above to view its results.</p>
                </CardContent>
              </Card>
            </section>
          )}
        </TabsContent>
        <TabsContent value="execution" className="analysis-run-view-panel" id="execution-trace">
          <AnalysisRunExecution
            analysisRunId={analysisRunId}
            runStatus={run.status}
            selectedSpanId={selectedExecutionSpanId}
            onSelectSpan={selectExecutionSpan}
          />
        </TabsContent>
        <TabsContent value="review" keepMounted={hasOpenedPaperReview || selectedView === "review"} className="analysis-run-view-panel">
          <AnalysisRunPaperReview
            run={run}
            parsedDocument={parsedDocument}
            report={report}
            parsedLoading={parsedReady && parsedQuery.isPending}
            reportLoading={parsedReady && reportQuery.isPending}
            parsedError={parsedError}
            reportError={reportError}
            selectedOutcomeId={selectedOutcomeId}
            selectedReferenceKey={selectedReferenceKey}
            selectedDetailSection={selectedReviewDetail}
            hasReviewState={searchParams.has("reviewPair") || searchParams.has("reviewReference") || searchParams.has("reviewFilter")}
            onSelectOutcome={selectReviewPair}
            onSelectReference={selectReviewReference}
            onClearReviewPair={clearReviewPair}
            onClearSelectedReference={clearReviewReference}
            onClearReviewState={clearReviewState}
            onSelectDetailSection={selectReviewDetail}
          />
        </TabsContent>
        {!isPaperReviewActive && <BackToTopFab />}
      </Tabs>
    </article>
  );
}
