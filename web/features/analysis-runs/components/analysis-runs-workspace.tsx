"use client";

import { MouseEvent, useEffect, useMemo, useRef, useState } from "react";
import { useIsMutating } from "@tanstack/react-query";
import { useProviderConfiguration } from "@/features/providers/provider-configuration-context";
import {
  useParsedDocument,
  useRecentAnalysisRuns,
  UPLOAD_ANALYSIS_RUN_MUTATION_KEY,
  useReanalyzeDocument,
  useReferenceResolutionReport,
} from "@/features/analysis-runs/queries/analysis-run-queries";
import type { AnalysisRun, AnalysisRunPage } from "@/features/analysis-runs/types";
import {
  ArrowLeft,
  ArrowRight,
  ArrowUpRight,
  ChevronDown,
  FileText,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { TabsContent } from "@/components/ui/tabs";
import { ReferenceResolutionBadge } from "@/features/reference-resolution/components/reference-resolution-badge";
import { ReferenceResolutionEntryCard } from "@/features/reference-resolution/components/reference-resolution-entry-card";
import { WorkflowStepTabs, type WorkflowStep } from "@/features/analysis-runs/components/workflow-step-tabs";
import { formatConfidenceThreshold } from "@/features/reference-resolution/format-confidence-threshold";
import { scrollToAnchorTarget } from "@/lib/scroll-to-anchor";

type AnalysisRunDetailTab = "progress" | "parsed" | "report";

const EMPTY_ANALYSIS_RUN_PAGE: AnalysisRunPage = { items: [], nextCursor: null };

const STATUS_CLASS_NAMES: Record<AnalysisRun["status"], string> = {
  QUEUED: "border-border bg-muted text-muted-foreground",
  PROCESSING: "border-primary/25 bg-primary/10 text-primary",
  PARSED: "border-primary/20 bg-primary/5 text-primary",
  COMPLETED: "border-primary/20 bg-primary/5 text-primary",
  COMPLETED_WITH_WARNINGS: "border-warning/40 bg-warning/10 text-warning-foreground",
  FAILED: "border-destructive/25 bg-destructive/10 text-destructive",
};

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

function statusLabel(status: AnalysisRun["status"]): string {
  return status.replaceAll("_", " ").toLowerCase();
}

function RunStatusBadge({ status }: { status: AnalysisRun["status"] }) {
  return (
    <Badge
      variant="outline"
      className={cn("shrink-0 capitalize", STATUS_CLASS_NAMES[status])}
    >
      {statusLabel(status)}
    </Badge>
  );
}

function ReportMetric({ label, value }: { label: string; value: number | string }) {
  return (
    <Card size="sm">
      <CardContent className="space-y-1">
        <dt className="text-xs leading-relaxed text-muted-foreground">{label}</dt>
        <dd className="m-0 font-mono text-lg font-semibold text-foreground">{value}</dd>
      </CardContent>
    </Card>
  );
}

function isParsedDocumentReady(status: AnalysisRun["status"] | undefined): boolean {
  return status === "PARSED" || status === "COMPLETED" || status === "COMPLETED_WITH_WARNINGS";
}

function runProgressStep(status: AnalysisRun["status"] | undefined): Pick<WorkflowStep, "state" | "statusLabel"> {
  switch (status) {
    case "QUEUED":
      return { state: "waiting", statusLabel: "Queued" };
    case "PROCESSING":
      return { state: "in-progress", statusLabel: "In progress" };
    case "PARSED":
      return { state: "ready", statusLabel: "Parsed" };
    case "COMPLETED":
      return { state: "complete", statusLabel: "Done" };
    case "COMPLETED_WITH_WARNINGS":
      return { state: "complete", statusLabel: "Done with warnings" };
    case "FAILED":
      return { state: "failed", statusLabel: "Failed" };
    default:
      return { state: "waiting", statusLabel: "Select a run" };
  }
}

function scrollToDetails(element: HTMLElement | null) {
  element?.scrollIntoView({
    behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth",
    block: "start",
  });
}

function scrollToParsedDocumentTarget(event: MouseEvent<HTMLAnchorElement>) {
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;

  const targetId = decodeURIComponent(event.currentTarget.hash.slice(1));
  const target = document.getElementById(targetId);
  if (!target) return;

  event.preventDefault();
  scrollToAnchorTarget(target, event.currentTarget.hash);
}

function referenceResolutionAnchorId(referenceKey: string): string {
  return `reference-resolution-${referenceKey}`;
}

export function AnalysisRunsWorkspace({ initialSelectedRunId }: { initialSelectedRunId: string | null }) {
  const [pageCursors, setPageCursors] = useState<Array<string | null>>([null]);
  const [pageIndex, setPageIndex] = useState(0);
  const [activeDetailTab, setActiveDetailTab] = useState<AnalysisRunDetailTab>("progress");
  const [selectedRunId, setSelectedRunId] = useState<string | null>(initialSelectedRunId);
  const [validationError, setValidationError] = useState<string | null>(null);
  const providerConfiguration = useProviderConfiguration();
  const reanalyzeMutation = useReanalyzeDocument();
  const uploadPending = useIsMutating({ mutationKey: UPLOAD_ANALYSIS_RUN_MUTATION_KEY }) > 0;
  const busy = reanalyzeMutation.isPending || uploadPending;
  const detailsCardRef = useRef<HTMLDivElement>(null);
  const pendingDetailTarget = useRef<{ tab: AnalysisRunDetailTab; targetId: string } | null>(null);
  const pageCursor = pageCursors[pageIndex] ?? null;
  const runsQuery = useRecentAnalysisRuns(pageCursor);
  const runPage = runsQuery.data ?? EMPTY_ANALYSIS_RUN_PAGE;
  const runs = runPage.items;
  const loading = runsQuery.isPending;
  const selectedRun = useMemo(
    () => runs.find((run) => run.id === selectedRunId) ?? null,
    [runs, selectedRunId],
  );
  const selectedRunStatus = selectedRun?.status;
  const parsedDocumentReady = isParsedDocumentReady(selectedRunStatus);
  const parsedDocumentQuery = useParsedDocument(selectedRunId, parsedDocumentReady);
  const reportQuery = useReferenceResolutionReport(selectedRunId, parsedDocumentReady);
  const parsedDocument = parsedDocumentQuery.data ?? null;
  const parsedDocumentError = parsedDocumentQuery.isError
    ? errorMessage(parsedDocumentQuery.error, "Could not load the parsed document.")
    : null;
  const coverageReport = reportQuery.data ?? null;
  const coverageReportError = reportQuery.isError
    ? errorMessage(reportQuery.error, "Could not load the Reference Resolution Report.")
    : null;
  const resolutionEntriesByReferenceKey = useMemo(
    () => new Map(coverageReport?.referenceResolution.entries.map((entry) => [entry.localReferenceKey, entry] as const) ?? []),
    [coverageReport],
  );
  const parsedReferenceKeys = useMemo(
    () => new Set(parsedDocument?.bibliographyEntries.map((entry) => entry.localReferenceKey) ?? []),
    [parsedDocument],
  );
  const coverageReportLoading = Boolean(selectedRun && parsedDocumentReady && reportQuery.isPending);
  const citationContextAnchorsByReferenceKey = useMemo(() => {
    const anchorsByReferenceKey = new Map<string, Array<{ id: string; label: string }>>();
    if (!parsedDocument) return anchorsByReferenceKey;

    parsedDocument.citationContexts.forEach((context, contextIndex) => {
      const referenceKeys = new Set(
        context.occurrences.flatMap((occurrence) => occurrence.bibliographyReferenceKeys),
      );
      for (const referenceKey of referenceKeys) {
        const anchors = anchorsByReferenceKey.get(referenceKey) ?? [];
        anchors.push({ id: context.id, label: `Citation Context ${contextIndex + 1}` });
        anchorsByReferenceKey.set(referenceKey, anchors);
      }
    });

    return anchorsByReferenceKey;
  }, [parsedDocument]);
  const parsedDocumentLoading = Boolean(selectedRun && parsedDocumentReady && parsedDocumentQuery.isPending);
  useEffect(() => {
    const pending = pendingDetailTarget.current;
    if (!pending || activeDetailTab !== pending.tab) return;

    let frame = 0;
    let attempts = 0;
    const locateTarget = () => {
      if (pendingDetailTarget.current !== pending) return;

      const target = document.getElementById(pending.targetId);
      if (target) {
        pendingDetailTarget.current = null;
        scrollToAnchorTarget(target);
        return;
      }

      attempts += 1;
      if (attempts < 8) {
        frame = window.requestAnimationFrame(locateTarget);
        return;
      }

      const targetDataLoaded = pending.tab === "parsed" ? Boolean(parsedDocument) : Boolean(coverageReport);
      const targetDataFailed = pending.tab === "parsed" ? Boolean(parsedDocumentError) : Boolean(coverageReportError);
      if (targetDataLoaded || targetDataFailed) pendingDetailTarget.current = null;
    };

    frame = window.requestAnimationFrame(locateTarget);
    return () => window.cancelAnimationFrame(frame);
  }, [activeDetailTab, parsedDocument, parsedDocumentError, coverageReport, coverageReportError]);

  function selectRun(runId: string) {
    pendingDetailTarget.current = null;
    setSelectedRunId(runId);
    setActiveDetailTab("progress");
    scrollToDetails(detailsCardRef.current);
  }

  function goToNextRunPage() {
    if (!runPage.nextCursor || loading) return;
    pendingDetailTarget.current = null;
    setSelectedRunId(null);
    setActiveDetailTab("progress");
    setPageCursors((current) => [...current.slice(0, pageIndex + 1), runPage.nextCursor!]);
    setPageIndex(pageIndex + 1);
  }

  function goToPreviousRunPage() {
    if (pageIndex === 0 || loading) return;
    pendingDetailTarget.current = null;
    setSelectedRunId(null);
    setActiveDetailTab("progress");
    setPageIndex(pageIndex - 1);
  }

  function navigateToDetailTarget(
    event: MouseEvent<HTMLAnchorElement>,
    tab: AnalysisRunDetailTab,
    targetId: string,
  ) {
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;

    event.preventDefault();
    pendingDetailTarget.current = { tab, targetId };
    setActiveDetailTab(tab);
  }

  function reanalyze() {
    if (!selectedRun) return;

    let configuration;
    try {
      configuration = providerConfiguration.createConfiguration();
    } catch (cause) {
      setValidationError(errorMessage(cause, "Review provider consent before continuing."));
      return;
    }

    setValidationError(null);
    reanalyzeMutation.mutate({ documentId: selectedRun.documentId, configuration }, {
      onSuccess: (created) => {
        setPageCursors([null]);
        setPageIndex(0);
        setActiveDetailTab("progress");
        setSelectedRunId(created.analysisRunId);
      },
    });
  }

  const runQueryError = runsQuery.error
    ? errorMessage(runsQuery.error, "Could not load saved Analysis Runs.")
    : null;
  const reanalysisError = validationError
    ?? (reanalyzeMutation.error ? errorMessage(reanalyzeMutation.error, "A new Analysis Run could not be created.") : null);
  const parsedStepState: Pick<WorkflowStep, "state" | "statusLabel"> = !parsedDocumentReady
    ? { state: "waiting", statusLabel: "Waiting" }
    : parsedDocumentLoading
      ? { state: "loading", statusLabel: "Loading" }
      : parsedDocumentError
        ? { state: "failed", statusLabel: "Unavailable" }
        : { state: "ready", statusLabel: "Ready" };
  const reportStepState: Pick<WorkflowStep, "state" | "statusLabel"> = !parsedDocumentReady
    ? { state: "waiting", statusLabel: "Waiting" }
    : coverageReportLoading
      ? { state: "loading", statusLabel: "Loading" }
      : coverageReportError
        ? { state: "failed", statusLabel: "Unavailable" }
        : { state: "ready", statusLabel: "Ready" };
  const workflowSteps: WorkflowStep[] = [
    { value: "progress", number: "01", label: "Run Progress", compactLabel: "Progress", ...runProgressStep(selectedRunStatus) },
    { value: "parsed", number: "02", label: "Parsed Document", compactLabel: "Parsed", ...parsedStepState, disabled: !parsedDocumentReady },
    { value: "report", number: "03", label: "Reference Resolution Report", compactLabel: "Report", ...reportStepState, disabled: !parsedDocumentReady },
  ];

  function scrollToDetailsHeading() {
    document.getElementById("parsed-document-heading")?.focus({ preventScroll: true });
    scrollToDetails(detailsCardRef.current);
  }

  return (
    <>
        <Card className="shadow-sm">
          <CardHeader className="gap-2 border-b border-border/70 pb-5">
            <p className="flex items-center gap-2 font-mono text-xs tracking-[0.12em] text-muted-foreground uppercase">
              <span className="font-semibold text-warning-foreground">02</span> Persisted Progress
            </p>
            <div className="flex items-center justify-between gap-3">
              <CardTitle id="runs-heading" role="heading" aria-level={2} className="text-xl tracking-tight">
                Analysis Runs
              </CardTitle>
              <Badge variant="secondary" className="font-mono text-xs">
                {runs.length.toString().padStart(2, "0")}
              </Badge>
            </div>
          </CardHeader>
          <CardContent className="space-y-4">
            {runQueryError && (
              <Alert variant="destructive">
                <AlertTitle>Could not load saved Analysis Runs</AlertTitle>
                <AlertDescription>{runQueryError}</AlertDescription>
              </Alert>
            )}
            {loading ? (
              <div className="space-y-3" role="status" aria-label="Loading saved Analysis Runs">
                <span className="sr-only">Loading saved Analysis Runs</span>
                <Skeleton className="h-14 w-full" aria-hidden="true" />
                <Skeleton className="h-14 w-full" aria-hidden="true" />
                <Skeleton className="h-14 w-4/5" aria-hidden="true" />
              </div>
            ) : runs.length === 0 ? (
              <div className="grid min-h-40 place-items-center rounded-lg border border-dashed border-border bg-muted/20 p-6 text-center">
                <div className="space-y-2">
                  <FileText className="mx-auto size-6 text-muted-foreground" aria-hidden="true" />
                  <p className="text-sm font-medium">Your first Analysis Run will appear here.</p>
                  <p className="text-xs text-muted-foreground">Upload an academic PDF to begin.</p>
                </div>
              </div>
            ) : (
              <div className="max-h-[26rem] space-y-2 overflow-y-auto pr-1" role="list" aria-label="Saved Analysis Runs">
                {runs.map((run) => (
                  <div role="listitem" key={run.id}>
                    <Button
                      type="button"
                      variant="ghost"
                      aria-current={run.id === selectedRunId ? "true" : undefined}
                      className={cn(
                        "h-auto min-h-14 w-full justify-between gap-3 rounded-lg border border-transparent px-3 py-3 text-left",
                        run.id === selectedRunId && "border-primary/20 bg-accent hover:bg-accent",
                      )}
                      onClick={() => selectRun(run.id)}
                    >
                      <span className="min-w-0 flex-1 space-y-1">
                        <span className="block truncate text-sm font-medium text-foreground">{run.filename}</span>
                        <span className="block font-mono text-xs text-muted-foreground">{new Date(run.createdAt).toLocaleString()}</span>
                      </span>
                      <RunStatusBadge status={run.status} />
                    </Button>
                  </div>
                ))}
              </div>
            )}

            <Separator />
            <nav className="flex items-center justify-between gap-3" aria-label="Analysis Run pages">
              <span className="font-mono text-xs text-muted-foreground" aria-live="polite">
                Page {pageIndex + 1}
              </span>
              <div className="flex items-center gap-2">
                {pageIndex > 0 && (
                  <Button type="button" variant="outline" size="sm" className="min-h-11" onClick={goToPreviousRunPage} disabled={loading}>
                    <ArrowLeft aria-hidden="true" /> Previous
                  </Button>
                )}
                <Button type="button" variant="outline" size="sm" className="min-h-11" onClick={goToNextRunPage} disabled={!runPage.nextCursor || loading}>
                  Next <ArrowRight aria-hidden="true" />
                </Button>
              </div>
            </nav>
          </CardContent>
        </Card>

        <Card className="scroll-mt-5 min-w-0 overflow-visible shadow-sm lg:col-span-2" ref={detailsCardRef}>
          <CardHeader className="gap-3 border-b border-border/70 pb-5">
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div className="min-w-0 space-y-2">
                <p className="flex items-center gap-2 font-mono text-xs tracking-[0.12em] text-muted-foreground uppercase">
                  <span className="font-semibold text-warning-foreground">03</span> Parsed Document
                </p>
                <CardTitle id="parsed-document-heading" role="heading" aria-level={2} tabIndex={-1} className="break-words text-xl tracking-tight">
                  {selectedRun?.filename ?? "Analysis Run details"}
                </CardTitle>
              </div>
              {selectedRun && <RunStatusBadge status={selectedRun.status} />}
            </div>
          </CardHeader>

          {!selectedRun ? (
            <CardContent>
              <div className="grid min-h-44 place-items-center p-6 text-center">
                <div className="space-y-2">
                  <FileText className="mx-auto size-6 text-muted-foreground" aria-hidden="true" />
                  <p className="text-sm font-medium">Select an Analysis Run to inspect its progress and parsed document.</p>
                </div>
              </div>
            </CardContent>
          ) : (
            <CardContent className="space-y-5">
              <WorkflowStepTabs
                value={activeDetailTab}
                onValueChange={(value) => {
                  if (value === "progress" || value === "parsed" || value === "report") {
                    pendingDetailTarget.current = null;
                    setActiveDetailTab(value);
                  }
                }}
                steps={workflowSteps}
                onScrollToTop={scrollToDetailsHeading}
                scrollToTopLabel="Back to Parsed Document heading"
              >

                <TabsContent value="progress" className="space-y-5 outline-none">
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <p className="font-mono text-xs tracking-[0.1em] text-muted-foreground uppercase">Current run progress</p>
                    <RunStatusBadge status={selectedRun.status} />
                  </div>
                  <p className="text-sm leading-relaxed text-foreground" aria-live="polite">
                    {selectedRun.progress.message ?? (selectedRun.status === "QUEUED" ? "Waiting for a worker." : "Progress saved.")}
                  </p>
                  <Separator />
                  <dl className="grid gap-4 sm:grid-cols-[minmax(9rem,0.35fr)_minmax(0,1fr)]">
                    <dt className="font-mono text-xs tracking-wide text-muted-foreground uppercase">Source SHA-256</dt>
                    <dd className="m-0 break-all font-mono text-xs leading-relaxed text-foreground">{selectedRun.sourceContentSha256}</dd>
                    <dt className="font-mono text-xs tracking-wide text-muted-foreground uppercase">Configuration</dt>
                    <dd className="m-0 break-words text-sm text-foreground">
                      Claim {selectedRun.configuration.claimExtractor.provider} · Embeddings {selectedRun.configuration.embedding.provider} · Evidence {selectedRun.configuration.systemOne.provider} · Bibliography {selectedRun.configuration.referenceResolution?.provider?.provider ?? "not configured"} · Cited full text {selectedRun.configuration.openAccess?.provider ?? "not configured"}
                    </dd>
                    <dt className="font-mono text-xs tracking-wide text-muted-foreground uppercase">Worker stage</dt>
                    <dd className="m-0 text-sm capitalize text-foreground">
                      {selectedRun.progress.stage?.replaceAll("_", " ").toLowerCase() ?? "queued"}
                    </dd>
                  </dl>
                  {selectedRun.status === "FAILED" && selectedRun.failureReason && (
                    <Alert variant="destructive">
                      <AlertTitle>Analysis Run failed</AlertTitle>
                      <AlertDescription>{selectedRun.failureReason}</AlertDescription>
                    </Alert>
                  )}
                  <Button type="button" variant="outline" className="min-h-11 w-full justify-between sm:w-auto" disabled={busy || !providerConfiguration.configurationReady} onClick={reanalyze}>
                    Create a new run from this document <ArrowUpRight aria-hidden="true" />
                  </Button>
                  {reanalysisError && (
                    <Alert variant="destructive">
                      <AlertTitle>Could not create a new Analysis Run</AlertTitle>
                      <AlertDescription>{reanalysisError}</AlertDescription>
                    </Alert>
                  )}
                </TabsContent>

                <TabsContent value="parsed" className="space-y-5 outline-none">
                  {!isParsedDocumentReady(selectedRunStatus) && (
                    <p className="rounded-lg border border-border bg-muted/30 p-4 text-sm text-muted-foreground">
                      The parsed document becomes available when this run reaches PARSED.
                    </p>
                  )}
                  {parsedDocumentLoading && (
                    <p className="flex items-center gap-2 text-sm text-muted-foreground" role="status">
                      <Spinner aria-hidden="true" /> Loading parsed document structure…
                    </p>
                  )}
                  {parsedDocumentError && (
                    <Alert variant="destructive">
                      <AlertTitle>Parsed document unavailable</AlertTitle>
                      <AlertDescription>{parsedDocumentError}</AlertDescription>
                    </Alert>
                  )}
                  {parsedDocument && (
                    <div className="space-y-6 pt-1">
                      <div className="flex flex-wrap items-center justify-between gap-3">
                        <div className="space-y-1">
                          <p className="font-mono text-xs tracking-[0.12em] text-muted-foreground uppercase">Parsed document</p>
                          <h3 className="font-heading text-lg font-semibold tracking-tight">Sections and references</h3>
                        </div>
                        <Badge variant="secondary" className="font-mono text-xs">
                          {parsedDocument.parser.provider} {parsedDocument.parser.version}
                        </Badge>
                      </div>
                      <p className="rounded-md bg-muted/50 px-3 py-2 text-xs leading-relaxed text-muted-foreground">
                        Source offsets are zero-based and end-exclusive UTF-16 indexes in the normalized source text.
                      </p>

                      <section className="space-y-3" aria-labelledby="sections-heading">
                        <div className="flex items-center justify-between gap-3">
                          <h4 id="sections-heading" className="font-heading text-base font-semibold">Sections</h4>
                          <Badge variant="outline">{parsedDocument.sections.length}</Badge>
                        </div>
                        {parsedDocument.sections.length === 0 ? (
                          <p className="text-sm text-muted-foreground">No sections were returned by the parser.</p>
                        ) : (
                          <ol className="space-y-2">
                            {parsedDocument.sections.map((section) => (
                              <li key={section.id}>
                                <Collapsible className="group/section rounded-lg border border-border bg-card">
                                  <CollapsibleTrigger className="flex min-h-12 w-full items-center justify-between gap-4 px-4 py-3 text-left focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
                                    <span className="min-w-0 flex-1 truncate text-sm font-medium">
                                      {section.heading || `Section ${section.sectionOrder + 1}`}
                                    </span>
                                    <span className="shrink-0 font-mono text-xs text-muted-foreground">
                                      {section.startOffset}–{section.endOffset}
                                    </span>
                                    <ChevronDown className="size-4 shrink-0 text-muted-foreground transition-transform group-data-[open]/section:rotate-180" aria-hidden="true" />
                                  </CollapsibleTrigger>
                                  <CollapsibleContent className="border-t border-border px-4 py-3">
                                    <p className="whitespace-pre-wrap break-words text-sm leading-relaxed text-muted-foreground">{section.text}</p>
                                  </CollapsibleContent>
                                </Collapsible>
                              </li>
                            ))}
                          </ol>
                        )}
                      </section>

                      <Separator />
                      <section className="space-y-3" aria-labelledby="contexts-heading">
                        <div className="flex items-center justify-between gap-3">
                          <h4 id="contexts-heading" className="font-heading text-base font-semibold">Citation Contexts</h4>
                          <Badge variant="outline">{parsedDocument.citationContexts.length}</Badge>
                        </div>
                        {parsedDocument.citationContexts.length === 0 ? (
                          <p className="text-sm text-muted-foreground">No citation markers were detected.</p>
                        ) : (
                          <ol className="space-y-3">
                            {parsedDocument.citationContexts.map((context, index) => (
                              <li key={context.id} id={`citation-context-${context.id}`} className="citation-context-anchor scroll-mt-5">
                                <article className="space-y-3 rounded-lg border border-border bg-muted/20 p-4">
                                  <div className="flex flex-wrap items-center justify-between gap-2 font-mono text-xs text-muted-foreground">
                                    <Badge variant="secondary" className="text-[0.65rem] uppercase">
                                      {context.boundaryKind.replaceAll("_", " ").toLowerCase()}
                                    </Badge>
                                    <span>{context.startOffset}–{context.endOffset}</span>
                                  </div>
                                  <p className="break-words text-sm leading-relaxed">{context.text}</p>
                                  <section className="space-y-2" aria-label={`Atomic Claims from Citation Context ${index + 1}`}>
                                    <h5 className="font-mono text-xs tracking-wide text-muted-foreground uppercase">Atomic Claims</h5>
                                    {context.atomicClaims.length === 0 ? (
                                      <p className="text-sm text-muted-foreground">No Atomic Claims were extracted from this Citation Context.</p>
                                    ) : (
                                      <ol className="space-y-2">
                                        {context.atomicClaims.map((claim) => (
                                          <li key={claim.id}>
                                            <article className="space-y-2 rounded-md border border-border bg-card p-3">
                                              <div className="flex flex-wrap items-start justify-between gap-2">
                                                <p className="min-w-0 flex-1 break-words text-sm leading-relaxed">{claim.text}</p>
                                                <Badge variant="outline" className="shrink-0">
                                                  {claim.citationTargets.length > 0 ? "Inferred · provisional" : "No Citation Targets"}
                                                </Badge>
                                              </div>
                                              <p className="font-mono text-xs text-muted-foreground">
                                                Source span {claim.sourceStartOffset}–{claim.sourceEndOffset} in the Citation Context above
                                              </p>
                                              {claim.citationTargets.length === 0 ? (
                                                <p className="text-xs text-muted-foreground">No Citation Targets were resolved in this Citation Context.</p>
                                              ) : (
                                                <ul className="flex flex-wrap gap-x-3 gap-y-1 border-t border-border pt-2" aria-label="Inferred Citation Targets">
                                                  {claim.citationTargets.map((target) => (
                                                    <li key={target.id}>
                                                      <a
                                                        className="max-w-full break-words text-xs text-primary underline underline-offset-4 hover:text-primary/80"
                                                        href={`#bibliography-${target.bibliographyReferenceKey}`}
                                                        onClick={scrollToParsedDocumentTarget}
                                                      >
                                                        <code className="font-mono">{target.markerText}</code>
                                                        <span className="ml-1">{target.bibliographyTitle || target.bibliographyReferenceKey}</span>
                                                      </a>
                                                    </li>
                                                  ))}
                                                </ul>
                                              )}
                                            </article>
                                          </li>
                                        ))}
                                      </ol>
                                    )}
                                  </section>
                                  <ul className="space-y-2 border-l-2 border-primary/20 pl-4">
                                    {context.occurrences.map((occurrence) => (
                                      <li key={occurrence.id} className="break-words text-sm">
                                        <code className="rounded bg-background px-1.5 py-0.5 font-mono text-xs text-primary">{occurrence.markerText}</code>
                                        <span className="ml-2 font-mono text-xs text-muted-foreground">
                                          {occurrence.startOffset}–{occurrence.endOffset}
                                        </span>
                                        {occurrence.bibliographyReferenceKeys.length > 0 && (
                                          <span className="ml-2 inline-flex flex-wrap gap-x-2">
                                            <span className="sr-only">Bibliography entries:</span>
                                            {occurrence.bibliographyReferenceKeys.map((key) => (
                                              <a key={key} className="text-primary underline underline-offset-4 hover:text-primary/80" href={`#bibliography-${key}`} onClick={scrollToParsedDocumentTarget}>
                                                {key}
                                              </a>
                                            ))}
                                          </span>
                                        )}
                                      </li>
                                    ))}
                                  </ul>
                                </article>
                              </li>
                            ))}
                          </ol>
                        )}
                      </section>

                      <Separator />
                      <section className="space-y-3" aria-labelledby="bibliography-heading">
                        <div className="flex items-center justify-between gap-3">
                          <h4 id="bibliography-heading" className="font-heading text-base font-semibold">Bibliography Entries</h4>
                          <Badge variant="outline">{parsedDocument.bibliographyEntries.length}</Badge>
                        </div>
                        {parsedDocument.bibliographyEntries.length === 0 ? (
                          <p className="text-sm text-muted-foreground">No bibliography entries were detected.</p>
                        ) : (
                          <ol className="space-y-3">
                            {parsedDocument.bibliographyEntries.map((entry) => {
                              const citingContexts = citationContextAnchorsByReferenceKey.get(entry.localReferenceKey) ?? [];
                              const resolution = resolutionEntriesByReferenceKey.get(entry.localReferenceKey);
                              return (
                                <li key={entry.localReferenceKey} id={`bibliography-${entry.localReferenceKey}`} className="bibliography-entry-anchor scroll-mt-5 rounded-lg border border-border bg-card p-4">
                                  <h5 className="break-words font-medium leading-relaxed">
                                    {entry.title || entry.localReferenceKey}
                                  </h5>
                                  <p className="mt-1 font-mono text-xs text-muted-foreground uppercase">
                                    {entry.localReferenceKey} · {entry.referenceType.toLowerCase().replaceAll("_", " ")}{entry.year ? ` · ${entry.year}` : ""}
                                  </p>
                                  {entry.authors.length > 0 && (
                                    <p className="mt-2 break-words text-sm text-muted-foreground">{entry.authors.join(", ")}</p>
                                  )}
                                  <p className="mt-3 break-words text-sm leading-relaxed">{entry.rawText}</p>
                                  {entry.doi && (
                                    <p className="mt-2 break-all font-mono text-xs text-muted-foreground">DOI: {entry.doi}</p>
                                  )}
                                  {resolution && (
                                    <div className="mt-3 flex flex-wrap items-center justify-between gap-3 border-t border-border pt-3">
                                      <div className="flex items-center gap-2">
                                        <span className="text-xs text-muted-foreground">Resolution</span>
                                        <ReferenceResolutionBadge status={resolution.status} />
                                      </div>
                                      <a
                                        className="inline-flex min-h-11 items-center gap-2 text-sm font-medium text-primary underline underline-offset-4 hover:text-primary/80 focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
                                        href={`#${referenceResolutionAnchorId(entry.localReferenceKey)}`}
                                        onClick={(event) => navigateToDetailTarget(event, "report", referenceResolutionAnchorId(entry.localReferenceKey))}
                                      >
                                        View resolution result <ArrowRight className="size-4" aria-hidden="true" />
                                      </a>
                                    </div>
                                  )}
                                  {citingContexts.length > 0 && (
                                    <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 border-t border-border pt-3">
                                      <span className="text-xs text-muted-foreground">Cited in:</span>
                                      {citingContexts.map((context) => (
                                        <a
                                          key={context.id}
                                          className="text-xs text-primary underline underline-offset-4 hover:text-primary/80"
                                          href={`#citation-context-${context.id}`}
                                          onClick={scrollToParsedDocumentTarget}
                                        >
                                          {context.label}
                                        </a>
                                      ))}
                                    </div>
                                  )}
                                </li>
                              );
                            })}
                          </ol>
                        )}
                      </section>
                    </div>
                  )}
                </TabsContent>

                <TabsContent value="report" className="space-y-5 outline-none">
                  {coverageReportLoading && (
                    <p className="flex items-center gap-2 text-sm text-muted-foreground" role="status">
                      <Spinner aria-hidden="true" /> Loading Evidence Coverage Report…
                    </p>
                  )}
                  {coverageReportError && (
                    <Alert variant="destructive">
                      <AlertTitle>Report unavailable</AlertTitle>
                      <AlertDescription>{coverageReportError}</AlertDescription>
                    </Alert>
                  )}
                  {coverageReport && (() => {
                    const resolution = coverageReport.referenceResolution;
                    const counts = resolution.summary;
                    const coverage = coverageReport.evidenceCoverage;
                    const verificationCounts = coverage.summary;
                    return (
                      <div className="space-y-5">
                        <div className="flex flex-wrap items-start justify-between gap-3">
                          <div className="space-y-1">
                            <p className="font-mono text-xs tracking-[0.12em] text-muted-foreground uppercase">Evidence Coverage Report</p>
                            <h3 className="font-heading text-lg font-semibold tracking-tight">Traceable claim and cited-reference outcomes</h3>
                          </div>
                          <Badge variant="secondary" className="font-mono text-xs">
                            {coverage.executionStatus.replaceAll("_", " ").toLowerCase()}
                          </Badge>
                        </div>
                        <Alert>
                          <AlertTitle>Conservative research triage</AlertTitle>
                          <AlertDescription>{coverage.triageDisclaimer}</AlertDescription>
                        </Alert>
                        <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                          {([
                            ["Claim–Reference pairs", verificationCounts.totalVerifications],
                            ["Completed pairs", verificationCounts.completedVerifications],
                            ["Incomplete pairs", verificationCounts.incompleteVerifications],
                            ["Comparable conflicts", verificationCounts.evidenceConflicts],
                          ] as const).map(([label, count]) => (
                            <ReportMetric key={label} label={label} value={count} />
                          ))}
                        </dl>
                        <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-4" aria-label="Counts by final verification status">
                          {([
                            ["SUPPORTED", verificationCounts.supported],
                            ["PARTIALLY_SUPPORTED", verificationCounts.partiallySupported],
                            ["CONTRADICTED", verificationCounts.contradicted],
                            ["INSUFFICIENT_EVIDENCE", verificationCounts.insufficientEvidence],
                            ["INACCESSIBLE", verificationCounts.inaccessible],
                            ["UNRESOLVED", verificationCounts.unresolved],
                            ["UNSUPPORTED_REFERENCE_TYPE", verificationCounts.unsupportedReferenceType],
                          ] as const).map(([label, count]) => (
                            <ReportMetric key={label} label={label.replaceAll("_", " ").toLowerCase()} value={count} />
                          ))}
                        </dl>
                        <dl className="grid gap-3 rounded-lg border border-border bg-muted/20 p-4 sm:grid-cols-3">
                          <div className="space-y-1">
                            <dt className="font-mono text-xs uppercase text-muted-foreground">Evidence-strength rubric</dt>
                            <dd className="m-0 break-words font-mono text-xs text-foreground">{coverage.verificationPolicyVersion ?? "Not configured for this run"}</dd>
                          </div>
                          <div className="space-y-1">
                            <dt className="font-mono text-xs uppercase text-muted-foreground">Aggregation policy</dt>
                            <dd className="m-0 break-words font-mono text-xs text-foreground">{coverage.aggregationPolicyVersion ?? "Not configured for this run"}</dd>
                          </div>
                          <div className="space-y-1">
                            <dt className="font-mono text-xs uppercase text-muted-foreground">Pinned thresholds</dt>
                            <dd className="m-0 break-words font-mono text-xs text-foreground">
                              {coverage.thresholds ? Object.entries(coverage.thresholds).map(([key, value]) => `${key}: ${value.toFixed(2)}`).join(" · ") : "Not configured for this run"}
                            </dd>
                          </div>
                        </dl>
                        <dl className="grid gap-3 rounded-lg border border-border bg-muted/20 p-4 sm:grid-cols-2">
                          <div className="space-y-1">
                            <dt className="font-mono text-xs uppercase text-muted-foreground">Score policy</dt>
                            <dd className="m-0 break-words font-mono text-xs text-foreground">{resolution.scorePolicyVersion ?? "Not configured for this run"}</dd>
                          </div>
                          <div className="space-y-1">
                            <dt className="font-mono text-xs uppercase text-muted-foreground">Configured threshold</dt>
                            <dd className="m-0 font-mono text-xs text-foreground">{formatConfidenceThreshold(resolution.confidenceThreshold)}</dd>
                          </div>
                        </dl>
                        <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-6">
                          {([
                            ["Bibliography entries", counts.total],
                            ["Resolved", counts.resolved],
                            ["Unresolved", counts.unresolved],
                            ["Unsupported types", counts.unsupportedReferenceType],
                            ["Not attempted", counts.notAttempted],
                            ["Processing failed", counts.failed],
                          ] as const).map(([label, count]) => (
                            <ReportMetric key={label} label={label} value={count} />
                          ))}
                        </dl>
                        <section className="space-y-3" aria-labelledby="cited-paper-access-heading">
                          <div className="space-y-1">
                            <h4 id="cited-paper-access-heading" className="font-mono text-xs tracking-wide text-muted-foreground uppercase">Cited Paper access outcomes</h4>
                            <p className="text-sm text-muted-foreground">Access availability is separate from each Claim–Reference Verification status.</p>
                          </div>
                          <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-5">
                            {([
                              ["Full text available", "FULL_TEXT_AVAILABLE"],
                              ["Abstract only", "ABSTRACT_ONLY"],
                              ["Metadata only", "METADATA_ONLY"],
                              ["Unavailable", "UNAVAILABLE"],
                            ] as const).map(([label, status]) => (
                              <ReportMetric
                                key={status}
                                label={label}
                                value={resolution.entries.filter((entry) => entry.citedPaperAccess?.accessStatus === status).length}
                              />
                            ))}
                            <ReportMetric
                              label="Not attempted"
                              value={resolution.entries.filter((entry) => entry.status === "RESOLVED" && !entry.citedPaperAccess).length}
                            />
                          </dl>
                        </section>
                        {resolution.entries.length === 0 ? (
                          <p className="rounded-lg border border-dashed border-border bg-muted/20 p-5 text-sm text-muted-foreground">
                            No Bibliography Entries were available for resolution.
                          </p>
                        ) : (
                          <ol className="space-y-3">
                            {resolution.entries.map((entry) => (
                              <ReferenceResolutionEntryCard
                                key={entry.localReferenceKey}
                                entry={entry}
                                anchorId={referenceResolutionAnchorId(entry.localReferenceKey)}
                                parsedEntryHref={`#bibliography-${entry.localReferenceKey}`}
                                parsedEntryAvailable={parsedReferenceKeys.has(entry.localReferenceKey)}
                                onViewParsedEntry={(event, referenceKey) => navigateToDetailTarget(event, "parsed", `bibliography-${referenceKey}`)}
                              />
                            ))}
                          </ol>
                        )}
                        <p className="text-xs leading-relaxed text-muted-foreground">
                          Ambiguous and below-threshold matches remain unresolved. Access provenance does not itself imply support: abstract-only and unsupported-language references receive conservative terminal statuses without semantic-provider calls. Processing failures remain incomplete pairs and never enter the seven domain-status counts.
                        </p>
                      </div>
                    );
                  })()}
                </TabsContent>
              </WorkflowStepTabs>
            </CardContent>
          )}
        </Card>
    </>
  );
}
