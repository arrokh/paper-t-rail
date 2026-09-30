"use client";

import { useEffect, useRef, useState } from "react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Separator } from "@/components/ui/separator";
import { Spinner } from "@/components/ui/spinner";
import { ClaimEvidencePassages } from "@/features/reference-resolution/components/claim-evidence-passages";
import { CitedPaperAccessSummary } from "@/features/reference-resolution/components/cited-paper-access-summary";
import { ReferenceResolutionBadge } from "@/features/reference-resolution/components/reference-resolution-badge";
import { BackLink } from "@/features/workspace/components/back-link";
import { PIPELINE_STAGES, PIPELINE_STAGE_STATE_LABELS, pipelineStage, pipelineStageState, type PipelineStageId } from "@/features/analysis-runs/pipeline";
import type { AnalysisRun, ParsedDocument, ReferenceResolutionReportResponse } from "@/features/analysis-runs/types";
import { cn } from "@/lib/utils";

type ReportEntry = ReferenceResolutionReportResponse["referenceResolution"]["entries"][number];
type VerificationOutcome = ReportEntry["verificationOutcomes"][number];

function scrollIntoViewAndWaitForCompletion(element: HTMLElement): Promise<void> {
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const scrollMarginTop = Number.parseFloat(window.getComputedStyle(element).scrollMarginTop) || 0;
  const targetScrollTop = Math.max(0, element.getBoundingClientRect().top + window.scrollY - scrollMarginTop);
  const alreadyAtTarget = Math.abs(targetScrollTop - window.scrollY) < 1;

  if (reducedMotion || alreadyAtTarget) {
    element.scrollIntoView({ behavior: "instant", block: "start" });
    return Promise.resolve();
  }

  return new Promise((resolve) => {
    let idleTimeout = 0;
    let fallbackTimeout = 0;
    let settled = false;

    const finish = () => {
      if (settled) return;
      settled = true;
      window.removeEventListener("scroll", handleScroll);
      window.clearTimeout(idleTimeout);
      window.clearTimeout(fallbackTimeout);
      resolve();
    };

    const handleScroll = () => {
      window.clearTimeout(idleTimeout);
      idleTimeout = window.setTimeout(finish, 120);
    };

    window.addEventListener("scroll", handleScroll, { passive: true });
    fallbackTimeout = window.setTimeout(finish, 2000);
    element.scrollIntoView({ behavior: "smooth", block: "start" });
  });
}

function ResultMetric({ label, value }: { label: string; value: number | string }) {
  return (
    <Card size="sm" className="shadow-none">
      <CardContent className="p-3">
        <dl className="space-y-1">
          <dt className="text-xs leading-relaxed text-muted-foreground">{label}</dt>
          <dd className="m-0 font-mono text-lg font-semibold text-foreground">{value}</dd>
        </dl>
      </CardContent>
    </Card>
  );
}

function RunResultsUnavailable({ stage, run }: { stage: PipelineStageId; run: AnalysisRun }) {
  if (run.status === "FAILED" && run.failureReason) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Analysis Run failed</AlertTitle>
        <AlertDescription>{run.failureReason}</AlertDescription>
      </Alert>
    );
  }
  return (
    <div className="rounded-lg border border-dashed border-border bg-muted/20 p-5">
      <p className="font-medium">Results are not available for this stage yet.</p>
      <p className="mt-1 text-sm leading-relaxed text-muted-foreground">
        {stage === "source"
          ? "The parsed sections and annotations appear after the source structure has been persisted."
          : run.status === "PARSED"
            ? "PARSED means parsing, reference resolution and eligible evidence retrieval are ready. Claim–Paper Verification has not completed."
            : "The worker has not persisted output for this stage yet. The pipeline shows only progress reported by the system."}
      </p>
      {run.progress.message && <p className="mt-3 text-sm text-muted-foreground" aria-live="polite">{run.progress.message}</p>}
    </div>
  );
}

function PipelineStageQuickNavigation({
  selectedStage,
  onSelectStage,
}: {
  selectedStage: PipelineStageId;
  onSelectStage: (stage: PipelineStageId) => void;
}) {
  return (
    <nav className="mx-auto w-full min-w-0 max-w-full overflow-x-auto overflow-y-hidden px-1 py-2 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden" aria-label="Analysis pipeline stages">
      <ol className="mx-auto flex w-fit min-w-max items-center justify-center gap-1">
        {PIPELINE_STAGES.map((pipelineStage) => {
          const selected = pipelineStage.id === selectedStage;
          return (
            <li key={pipelineStage.id}>
              <Button
                type="button"
                variant={selected ? "default" : "ghost"}
                size="sm"
                aria-current={selected ? "step" : undefined}
                className="h-8 shrink-0 gap-1.5 px-2 text-xs"
                onClick={() => onSelectStage(pipelineStage.id)}
              >
                <span className="font-mono text-[0.65rem]">{pipelineStage.number}</span>
                <span className="whitespace-nowrap">{pipelineStage.label}</span>
              </Button>
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

function providerLabel(provider: { provider: string; model?: string | null; version: string } | null | undefined): string {
  if (!provider) return "Not configured";
  return [provider.provider, provider.model, provider.version].filter(Boolean).join(" · ");
}

function PipelineConfiguration({ run, stageId }: { run: AnalysisRun; stageId: PipelineStageId }) {
  const configuration = run.configuration;
  const thresholdLabel = (configuration.aggregation?.thresholds
    ? Object.entries(configuration.aggregation.thresholds).map(([key, value]) => `${key}: ${value}`).join(" · ")
    : null) ?? "Not configured";
  const rows: Array<[string, string]> = stageId === "source"
    ? [
        ["Source parser", providerLabel(configuration.sourceParser)],
        ["Claim extraction", providerLabel(configuration.claimExtractor)],
        ["Maximum claim–reference pairs", String(configuration.validationLimits?.maxClaimCitationPairs ?? "Not recorded")],
      ]
    : stageId === "references"
      ? [
          ["Resolver", providerLabel(configuration.referenceResolution?.provider)],
          ["Execution", configuration.referenceResolution?.executionStatus ?? "Not configured"],
          ["Score policy", configuration.referenceResolution?.scorePolicyVersion ?? "Not configured"],
          ["Match threshold", configuration.referenceResolution?.confidenceThreshold?.toFixed(3) ?? "Not configured"],
        ]
      : stageId === "access"
        ? [
            ["Cited-source provider", providerLabel(configuration.openAccess)],
            ["Language detector", providerLabel(configuration.languageDetector)],
            ["Minimum language confidence", String(configuration.validationLimits?.minimumLanguageConfidence ?? "Not recorded")],
          ]
        : stageId === "evidence"
          ? [
              ["Embedding provider", providerLabel(configuration.embedding)],
              ["Retrieval profile", configuration.retrieval.profileId],
              ["Candidate limits", `vector ${configuration.retrieval.vectorCandidateLimit} · lexical ${configuration.retrieval.lexicalCandidateLimit} · final ${configuration.retrieval.finalCandidateLimit}`],
              ["Rank fusion constant", String(configuration.retrieval.reciprocalRankFusionConstant)],
            ]
          : [
              ["System One", providerLabel(configuration.systemOne)],
              ["Aggregation status", configuration.aggregation?.executionStatus ?? "Not configured"],
              ["Verification policy", configuration.aggregation?.verificationPolicyVersion ?? "Not configured"],
              ["Aggregation policy", configuration.aggregation?.aggregationPolicyVersion ?? "Not configured"],
              ["Pinned thresholds", thresholdLabel],
            ];

  return (
    <section className="space-y-3 rounded-lg border border-border bg-muted/20 p-4" aria-label={`${pipelineStage(stageId).label} configuration`}>
      <div>
        <h4 className="font-medium">Run-pinned configuration</h4>
        <p className="mt-1 text-xs text-muted-foreground">Provider and policy selections saved with this immutable Analysis Run.</p>
      </div>
      <dl className="grid gap-3 text-sm sm:grid-cols-2 xl:grid-cols-3">
        {rows.map(([label, value]) => (
          <div key={label} className="min-w-0 space-y-1">
            <dt className="font-mono text-[0.65rem] tracking-wide text-muted-foreground uppercase">{label}</dt>
            <dd className="m-0 break-words">{value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

function PipelineWorkProgress({ run, stageId }: { run: AnalysisRun; stageId: PipelineStageId }) {
  const stage = run.pipeline?.stages.find((candidate) => candidate.id === stageId);
  if (!stage) return null;
  const items = stage.steps.flatMap((step) => step.items);
  const visibleItems = items.slice(0, 6);
  const statusLabel = (status: string) => status.replaceAll("_", " ").toLowerCase();

  return (
    <section className="space-y-3 rounded-lg border border-border p-4" aria-label={`${pipelineStage(stageId).label} persisted work status`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h4 className="font-medium">Persisted worker progress</h4>
        <Badge variant="outline" className="capitalize">{statusLabel(stage.status)}</Badge>
      </div>
      <p className="text-xs text-muted-foreground">
        {stage.counts.total} items · {stage.counts.completed} complete · {stage.counts.inProgress} in progress · {stage.counts.waiting} waiting · {stage.counts.skipped} skipped · {stage.counts.failed} failed
      </p>
      {visibleItems.length > 0 && (
        <ul className="grid gap-2 sm:grid-cols-2 xl:grid-cols-3">
          {visibleItems.map((item) => (
            <li key={item.id} className="flex min-w-0 items-start justify-between gap-3 rounded-md bg-muted/30 px-3 py-2 text-xs">
              <span className="min-w-0 break-words font-mono">{item.label}</span>
              <span className="shrink-0 text-right capitalize text-muted-foreground">
                {statusLabel(item.status)}{item.reasonCode ? ` · ${statusLabel(item.reasonCode)}` : ""}
              </span>
            </li>
          ))}
        </ul>
      )}
      {items.length > visibleItems.length && (
        <details className="group/all-items rounded-md border border-border/70 px-3 py-2">
          <summary className="cursor-pointer text-xs font-medium text-muted-foreground">Show all {items.length} work items</summary>
          <ul className="mt-3 grid gap-2 sm:grid-cols-2 xl:grid-cols-3">
            {items.slice(visibleItems.length).map((item) => (
              <li key={item.id} className="flex min-w-0 items-start justify-between gap-3 rounded-md bg-muted/30 px-3 py-2 text-xs">
                <span className="min-w-0 break-words font-mono">{item.label}</span>
                <span className="shrink-0 text-right capitalize text-muted-foreground">
                  {statusLabel(item.status)}{item.reasonCode ? ` · ${statusLabel(item.reasonCode)}` : ""}
                </span>
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  );
}

function AnnotationResults({ parsedDocument, view }: { parsedDocument: ParsedDocument; view: string }) {
  if (view === "sections") {
    return (
      <section className="space-y-3" aria-labelledby="parsed-sections-heading">
        <div className="flex items-center justify-between gap-3">
          <h4 id="parsed-sections-heading" className="font-heading font-semibold">Parsed sections</h4>
          <Badge variant="outline">{parsedDocument.sections.length}</Badge>
        </div>
        {parsedDocument.sections.length === 0 ? <p className="text-sm text-muted-foreground">No sections were returned by the parser.</p> : (
          <ol className="space-y-2">
            {parsedDocument.sections.map((section) => (
              <li key={section.id}>
                <Collapsible className="group/section rounded-lg border border-border bg-card">
                  <CollapsibleTrigger className="flex min-h-12 w-full items-center justify-between gap-4 px-4 py-3 text-left focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
                    <span className="min-w-0 flex-1 break-words text-sm font-medium">{section.heading || `Section ${section.sectionOrder + 1}`}</span>
                    <span className="shrink-0 font-mono text-xs text-muted-foreground">{section.startOffset}–{section.endOffset}</span>
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
    );
  }

  if (view === "claims") {
    const claims = parsedDocument.citationContexts.flatMap((context, contextIndex) =>
      context.atomicClaims.map((claim) => ({ context, contextIndex, claim })),
    );
    return (
      <section className="space-y-3" aria-labelledby="atomic-claims-heading">
        <div className="flex items-center justify-between gap-3">
          <h4 id="atomic-claims-heading" className="font-heading font-semibold">Extracted Atomic Claims</h4>
          <Badge variant="outline">{claims.length}</Badge>
        </div>
        {claims.length === 0 ? <p className="text-sm text-muted-foreground">No Atomic Claims were extracted.</p> : (
          <ol className="space-y-3">
            {claims.map(({ context, contextIndex, claim }) => (
              <li key={claim.id} className="rounded-lg border border-border bg-card p-4">
                <p className="break-words text-sm leading-relaxed">{claim.text}</p>
                <p className="mt-2 text-xs text-muted-foreground">Citation Context {contextIndex + 1} · source span {claim.sourceStartOffset}–{claim.sourceEndOffset}</p>
                <p className="mt-2 rounded-md bg-muted/30 p-3 text-xs leading-relaxed text-muted-foreground">{context.text}</p>
                <div className="mt-3 flex flex-wrap gap-2">
                  {claim.citationTargets.length === 0 ? <Badge variant="outline">No Citation Targets</Badge> : claim.citationTargets.map((target) => (
                    <Badge variant="secondary" key={target.id} className="max-w-full break-words whitespace-normal">
                      {target.markerText} · {target.bibliographyTitle || target.bibliographyReferenceKey} · inferred
                    </Badge>
                  ))}
                </div>
              </li>
            ))}
          </ol>
        )}
      </section>
    );
  }

  const occurrenceCount = parsedDocument.citationContexts.reduce((count, context) => count + context.occurrences.length, 0);
  return (
    <section className="space-y-3" aria-labelledby="citation-annotations-heading">
      <div className="flex items-center justify-between gap-3">
        <h4 id="citation-annotations-heading" className="font-heading font-semibold">Citation annotations</h4>
        <Badge variant="outline">{occurrenceCount} occurrences</Badge>
      </div>
      {parsedDocument.citationContexts.length === 0 ? <p className="text-sm text-muted-foreground">No citation contexts were detected.</p> : (
        <ol className="space-y-3">
          {parsedDocument.citationContexts.map((context, index) => (
            <li key={context.id} className="rounded-lg border border-border bg-card p-4">
              <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                <Badge variant="secondary">Citation Context {index + 1} · {context.boundaryKind.replaceAll("_", " ").toLowerCase()}</Badge>
                <span className="font-mono text-xs text-muted-foreground">{context.startOffset}–{context.endOffset}</span>
              </div>
              <p className="break-words text-sm leading-relaxed">{context.text}</p>
              <ul className="mt-3 flex flex-wrap gap-2" aria-label={`Citation Occurrences in context ${index + 1}`}>
                {context.occurrences.map((occurrence) => (
                  <li key={occurrence.id} className="min-w-0 max-w-full">
                    <Badge variant="outline" className="h-auto max-w-full justify-start whitespace-normal break-words py-1 text-left font-mono text-xs">{occurrence.markerText} · {occurrence.startOffset}–{occurrence.endOffset}</Badge>
                  </li>
                ))}
              </ul>
              <p className="mt-3 text-xs text-muted-foreground">{context.atomicClaims.length} Atomic Claims linked to this context. Associations to Citation Targets are inferred and provisional.</p>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

function ReferenceMatchResults({ report, view }: { report: ReferenceResolutionReportResponse; view: string }) {
  const resolution = report.referenceResolution;
  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <ResultMetric label="Bibliography Entries" value={resolution.summary.total} />
        <ResultMetric label="Resolved" value={resolution.summary.resolved} />
        <ResultMetric label="Unresolved" value={resolution.summary.unresolved} />
        <ResultMetric label="Below or outside policy" value={resolution.summary.unsupportedReferenceType + resolution.summary.notAttempted + resolution.summary.failed} />
      </div>
      <dl className="grid gap-3 rounded-lg border border-border bg-muted/20 p-4 sm:grid-cols-2">
        <div><dt className="font-mono text-xs uppercase text-muted-foreground">Score policy</dt><dd className="mt-1 break-words font-mono text-xs">{resolution.scorePolicyVersion ?? "Not configured"}</dd></div>
        <div><dt className="font-mono text-xs uppercase text-muted-foreground">Match threshold</dt><dd className="mt-1 font-mono text-xs">{resolution.confidenceThreshold?.toFixed(3) ?? "Not configured"}</dd></div>
      </dl>
      {resolution.entries.length === 0 ? <p className="text-sm text-muted-foreground">No Bibliography Entries were available for resolution.</p> : (
        <ol className="space-y-2">
          {resolution.entries.map((entry) => <ReferenceMatchCard key={entry.localReferenceKey} entry={entry} view={view} />)}
        </ol>
      )}
    </div>
  );
}

function ReferenceMatchCard({ entry, view }: { entry: ReportEntry; view: string }) {
  return (
    <li className="rounded-lg border border-border bg-card p-4">
      <div className="flex items-start justify-between gap-3">
        <p className="min-w-0 break-words font-mono text-xs text-muted-foreground">{entry.localReferenceKey}{entry.year ? ` · ${entry.year}` : ""}</p>
        <ReferenceResolutionBadge status={entry.status} />
      </div>
      <div className="mt-1 min-w-0 space-y-1">
        <h4 className="break-words font-medium">{entry.title || entry.localReferenceKey}</h4>
        {entry.authors.length > 0 && <p className="break-words text-sm text-muted-foreground">{entry.authors.join(", ")}</p>}
      </div>
      {view === "normalize" ? (
        <p className="mt-3 border-t border-border pt-3 text-sm leading-relaxed text-muted-foreground">{entry.rawText}</p>
      ) : view === "match" ? (
        <dl className="mt-3 grid gap-2 border-t border-border pt-3 text-xs sm:grid-cols-2">
          <div><dt className="font-mono uppercase text-muted-foreground">Match method</dt><dd className="mt-1 break-words">{entry.matchMethod?.replaceAll("_", " ").toLowerCase() ?? "Not recorded"}</dd></div>
          <div><dt className="font-mono uppercase text-muted-foreground">Matched Canonical Paper</dt><dd className="mt-1 break-words">{entry.canonicalPaper?.title ?? "No Canonical Paper matched"}</dd></div>
        </dl>
      ) : (
        <dl className="mt-3 grid gap-2 border-t border-border pt-3 text-xs sm:grid-cols-3">
          <div><dt className="font-mono uppercase text-muted-foreground">Confidence</dt><dd className="mt-1 font-mono">{entry.confidenceScore?.toFixed(3) ?? "Not scored"}</dd></div>
          <div><dt className="font-mono uppercase text-muted-foreground">Decision reason</dt><dd className="mt-1 break-words">{entry.reasonCode?.replaceAll("_", " ").toLowerCase() ?? "—"}</dd></div>
          <div><dt className="font-mono uppercase text-muted-foreground">Candidate</dt><dd className="mt-1 break-words">{entry.canonicalPaper?.title ?? "No match retained"}</dd></div>
        </dl>
      )}
      {view === "all" && (
        <details className="mt-3 border-t border-border pt-3">
          <summary className="cursor-pointer text-xs font-medium text-muted-foreground">Original bibliography entry</summary>
          <p className="mt-2 break-words text-sm leading-relaxed text-muted-foreground">{entry.rawText}</p>
        </details>
      )}
    </li>
  );
}

function AccessResults({ report, view }: { report: ReferenceResolutionReportResponse; view: string }) {
  const entries = report.referenceResolution.entries;
  const accessStates = ["FULL_TEXT_AVAILABLE", "ABSTRACT_ONLY", "METADATA_ONLY", "UNAVAILABLE"] as const;
  if (view === "acquire") {
    return (
      <div className="space-y-4">
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {accessStates.map((state) => (
            <ResultMetric key={state} label={state.replaceAll("_", " ").toLowerCase()} value={entries.filter((entry) => entry.citedPaperAccess?.accessStatus === state).length} />
          ))}
        </div>
        <ol className="space-y-3">
          {entries.map((entry) => (
            <li key={entry.localReferenceKey} className="rounded-lg border border-border bg-card p-4">
              <h4 className="break-words font-medium">{entry.title || entry.localReferenceKey}</h4>
              <p className="mt-1 text-xs text-muted-foreground">{entry.localReferenceKey} · {entry.status.replaceAll("_", " ").toLowerCase()}</p>
              <CitedPaperAccessSummary access={entry.citedPaperAccess} />
              {!entry.citedPaperAccess && <p className="mt-3 text-sm text-muted-foreground">No access result was persisted; unresolved and unsupported references do not enter the access lookup.</p>}
            </li>
          ))}
        </ol>
      </div>
    );
  }

  if (view === "discover") {
    const discovered = entries.filter((entry) => entry.citedPaperAccess);
    return (
      <div className="space-y-4">
        <p className="text-sm leading-relaxed text-muted-foreground">These are the persisted legal access discovery outcomes. Entries without a resolved Canonical Paper can skip this worker operation.</p>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <ResultMetric label="Access outcomes" value={discovered.length} />
          <ResultMetric label="Full text located" value={discovered.filter((entry) => entry.citedPaperAccess?.accessStatus === "FULL_TEXT_AVAILABLE").length} />
          <ResultMetric label="Abstract only" value={discovered.filter((entry) => entry.citedPaperAccess?.accessStatus === "ABSTRACT_ONLY").length} />
          <ResultMetric label="No full text" value={discovered.filter((entry) => entry.citedPaperAccess?.accessStatus === "METADATA_ONLY" || entry.citedPaperAccess?.accessStatus === "UNAVAILABLE").length} />
        </div>
        {discovered.length === 0 ? <p className="text-sm text-muted-foreground">No access discovery result was persisted.</p> : (
          <ol className="space-y-2">
            {discovered.map((entry) => (
              <li key={entry.localReferenceKey} className="rounded-lg border border-border bg-card p-4">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0"><p className="font-mono text-xs text-muted-foreground">{entry.localReferenceKey} · {entry.citedPaperAccess?.providerId}</p><h4 className="break-words font-medium">{entry.title || entry.localReferenceKey}</h4></div>
                  <Badge variant="outline">{entry.citedPaperAccess?.accessStatus.replaceAll("_", " ").toLowerCase()}</Badge>
                </div>
                <dl className="mt-3 grid gap-2 border-t border-border pt-3 text-xs sm:grid-cols-2">
                  <div><dt className="font-mono uppercase text-muted-foreground">Access reason</dt><dd className="mt-1 break-words">{entry.citedPaperAccess?.accessReason?.replaceAll("_", " ").toLowerCase() ?? "Full-text location found"}</dd></div>
                  <div><dt className="font-mono uppercase text-muted-foreground">License</dt><dd className="mt-1 break-words">{entry.citedPaperAccess?.license ?? "Not recorded"}</dd></div>
                  {entry.citedPaperAccess?.sourceUrl && <div className="min-w-0 sm:col-span-2"><dt className="font-mono uppercase text-muted-foreground">Discovered legal location</dt><dd className="mt-1 break-all">{entry.citedPaperAccess.sourceUrl}</dd></div>}
                </dl>
              </li>
            ))}
          </ol>
        )}
      </div>
    );
  }

  const entriesWithAccess = entries.filter((entry) => entry.citedPaperAccess);
  return (
    <div className="space-y-4">
      <p className="text-sm leading-relaxed text-muted-foreground">Access discovery is separate from final evidence status. Abstract-only, metadata-only, and unavailable results follow conditional paths into the report.</p>
      {entriesWithAccess.length === 0 ? <p className="text-sm text-muted-foreground">No Cited Paper access outcomes were persisted.</p> : (
        <ol className="space-y-3">
          {entriesWithAccess.map((entry) => (
            <li key={entry.localReferenceKey} className="rounded-lg border border-border bg-card p-4">
              <div className="mb-2 flex flex-wrap items-start justify-between gap-3">
                <div><p className="font-mono text-xs text-muted-foreground">{entry.localReferenceKey}</p><h4 className="break-words font-medium">{entry.title || entry.localReferenceKey}</h4></div>
                <Badge variant="outline">{entry.citedPaperAccess?.language?.toLowerCase() ?? "language not recorded"}</Badge>
              </div>
              <CitedPaperAccessSummary access={entry.citedPaperAccess} />
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

function IndexingResults({ report, view }: { report: ReferenceResolutionReportResponse; view: string }) {
  const entries = report.referenceResolution.entries;
  if (view === "language") {
    const entriesWithAccess = entries.filter((entry) => entry.citedPaperAccess);
    return (
      <div className="space-y-4">
        <p className="text-sm leading-relaxed text-muted-foreground">Language gating applies to acquired full-text assets. Abstract-only, metadata-only, or unavailable records follow the report path without full-text indexing.</p>
        {entriesWithAccess.length === 0 ? <p className="text-sm text-muted-foreground">No Cited Paper language outcomes were persisted.</p> : (
          <ul className="space-y-2">
            {entriesWithAccess.map((entry) => {
              const access = entry.citedPaperAccess!;
              return (
                <li key={entry.localReferenceKey} className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border bg-card p-4">
                  <div className="min-w-0"><p className="font-mono text-xs text-muted-foreground">{entry.localReferenceKey}</p><p className="break-words text-sm font-medium">{entry.title || entry.localReferenceKey}</p></div>
                  <div className="flex flex-wrap items-center gap-2"><Badge variant="outline">{access.accessStatus.replaceAll("_", " ").toLowerCase()}</Badge><Badge variant="secondary">{access.language?.toLowerCase() ?? "language not recorded"}</Badge></div>
                  <p className="w-full text-xs text-muted-foreground">Detector {access.languageDetectorVersion ?? "not recorded"}{access.accessReason ? ` · ${access.accessReason.replaceAll("_", " ").toLowerCase()}` : ""}</p>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <p className="text-sm leading-relaxed text-muted-foreground">Only legally acquired, supported-language full text is parsed and indexed for Evidence Passage retrieval.</p>
      <ol className="space-y-2">
        {entries.map((entry) => {
          const access = entry.citedPaperAccess;
          const indexing = access?.evidenceIndexing;
          return (
            <li key={entry.localReferenceKey} className="rounded-lg border border-border bg-card p-4">
              <div className="flex flex-wrap items-start justify-between gap-3"><div><p className="font-mono text-xs text-muted-foreground">{entry.localReferenceKey}</p><h4 className="break-words font-medium">{entry.title || entry.localReferenceKey}</h4></div><Badge variant="outline" className="capitalize">{indexing?.status.toLowerCase() ?? "not started"}</Badge></div>
              {indexing ? (
                <dl className="mt-3 grid gap-3 border-t border-border pt-3 text-xs sm:grid-cols-2">
                  <div><dt className="font-mono uppercase text-muted-foreground">Cited Paper parser</dt><dd className="mt-1">{indexing.parserProvider ?? "Not recorded"} {indexing.parserVersion ?? ""}</dd></div>
                  <div><dt className="font-mono uppercase text-muted-foreground">Detected language</dt><dd className="mt-1">{indexing.language?.toLowerCase() ?? "Not recorded"} · {indexing.languageDetectorVersion ?? "detector not recorded"}</dd></div>
                  <div className="min-w-0"><dt className="font-mono uppercase text-muted-foreground">Pinned asset</dt><dd className="mt-1 break-all font-mono">{indexing.assetId ?? "Not recorded"}</dd></div>
                  <div><dt className="font-mono uppercase text-muted-foreground">Indexing result</dt><dd className="mt-1 break-words">{indexing.failureReason?.replaceAll("_", " ").toLowerCase() ?? "Evidence index persisted"}</dd></div>
                  <div className="sm:col-span-2"><dt className="font-mono uppercase text-muted-foreground">Retrieval profile</dt><dd className="mt-1 break-words">{indexing.retrievalProfile.profileId} · vector {indexing.retrievalProfile.vectorCandidateLimit} · lexical {indexing.retrievalProfile.lexicalCandidateLimit} · final {indexing.retrievalProfile.finalCandidateLimit}</dd></div>
                </dl>
              ) : <p className="mt-3 text-sm text-muted-foreground">No evidence indexing record is available for this reference.</p>}
            </li>
          );
        })}
      </ol>
    </div>
  );
}

function VerificationResults({ runId, report, view }: { runId: string; report: ReferenceResolutionReportResponse; view: string }) {
  const outcomes: Array<{ outcome: VerificationOutcome; entry: ReportEntry }> = report.referenceResolution.entries.flatMap((entry) =>
    entry.verificationOutcomes.map((outcome) => ({ outcome, entry })),
  );
  const displayedOutcomes = view === "judge"
    ? outcomes.filter(({ outcome }) => outcome.evidencePassages.some((passage) => passage.evidenceJudgement) || outcome.processingFailureReason)
    : outcomes;
  const judged = outcomes.filter(({ outcome }) => outcome.evidencePassages.some((passage) => passage.evidenceJudgement)).length;
  const passages = outcomes.reduce((count, { outcome }) => count + outcome.evidencePassages.length, 0);
  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <ResultMetric label="Claim × Reference pairs" value={outcomes.length} />
        <ResultMetric label="Evidence Passages" value={passages} />
        <ResultMetric label="Pairs with judgements" value={judged} />
        <ResultMetric label="Incomplete pairs" value={outcomes.filter(({ outcome }) => outcome.processingStatus === "INCOMPLETE").length} />
      </div>
      <p className="text-sm leading-relaxed text-muted-foreground">
        {view === "retrieve"
          ? "Each listed outcome connects a Citation Context and an inferred claim-to-reference link to its ranked Evidence Passages. Retrieval makes a passage a candidate; it does not itself mean support."
          : "Persisted System One judgements appear inside each Evidence Passage. Final Claim–Paper statuses are produced by the deterministic aggregation stage."}
      </p>
      {displayedOutcomes.length === 0 ? <p className="text-sm text-muted-foreground">{view === "judge" ? "No persisted System One judgement is available for this run." : "No Claim–Reference verification pairs were created for this run."}</p> : (
        <ol className="space-y-3">
          {displayedOutcomes.map(({ outcome, entry }) => (
            <li key={outcome.id} className="rounded-lg border border-border bg-muted/10 p-3">
              <div className="mb-2 flex flex-wrap items-center gap-2">
                <span className="font-mono text-xs text-muted-foreground">{entry.localReferenceKey}</span>
                <ReferenceResolutionBadge status={outcome.finalStatus ?? outcome.processingStatus} />
              </div>
              <ol className="space-y-2">
                <ClaimEvidencePassages
                  analysisRunId={runId}
                  outcome={outcome}
                  indexingStatus={entry.citedPaperAccess?.evidenceIndexing?.status ?? null}
                />
              </ol>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

function ReportResults({ report, view }: { report: ReferenceResolutionReportResponse; view: string }) {
  const coverage = report.evidenceCoverage;
  const counts = coverage.summary;
  const labels: Array<[string, number]> = [
    ["Supported", counts.supported],
    ["Partially supported", counts.partiallySupported],
    ["Contradicted", counts.contradicted],
    ["Insufficient evidence", counts.insufficientEvidence],
    ["Inaccessible", counts.inaccessible],
    ["Unresolved", counts.unresolved],
    ["Unsupported reference type", counts.unsupportedReferenceType],
  ];
  return (
    <div className="space-y-5">
      <Alert>
        <AlertTitle>{view === "aggregate" ? "Deterministic aggregation" : "Research triage, not certification"}</AlertTitle>
        <AlertDescription>{view === "aggregate" ? "Final statuses use the policy and thresholds pinned to this Analysis Run. Incomplete pairs do not receive fabricated domain statuses." : coverage.triageDisclaimer}</AlertDescription>
      </Alert>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <ResultMetric label="Claim–Reference pairs" value={counts.totalVerifications} />
        <ResultMetric label="Completed pairs" value={counts.completedVerifications} />
        <ResultMetric label="Incomplete pairs" value={counts.incompleteVerifications} />
        <ResultMetric label="Comparable conflicts" value={counts.evidenceConflicts} />
      </div>
      <section className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-4" aria-label="Outcomes by final verification status">
        {labels.map(([label, value]) => <ResultMetric key={label} label={label} value={value} />)}
      </section>
      <dl className="grid gap-3 rounded-lg border border-border bg-muted/20 p-4 sm:grid-cols-3">
        <div><dt className="font-mono text-xs uppercase text-muted-foreground">Verification policy</dt><dd className="mt-1 break-words font-mono text-xs">{coverage.verificationPolicyVersion ?? "Not configured for this run"}</dd></div>
        <div><dt className="font-mono text-xs uppercase text-muted-foreground">Aggregation policy</dt><dd className="mt-1 break-words font-mono text-xs">{coverage.aggregationPolicyVersion ?? "Not configured for this run"}</dd></div>
        <div><dt className="font-mono text-xs uppercase text-muted-foreground">Pinned thresholds</dt><dd className="mt-1 break-words font-mono text-xs">{coverage.thresholds ? Object.entries(coverage.thresholds).map(([key, value]) => `${key}: ${value.toFixed(2)}`).join(" · ") : "Not configured for this run"}</dd></div>
      </dl>
      {view === "summary" && report.referenceResolution.entries.length > 0 && (
        <section className="space-y-3" aria-labelledby="claim-outcomes-heading">
          <h4 id="claim-outcomes-heading" className="font-heading font-semibold">Claim-level results</h4>
          <VerificationResults runId={report.analysisRunId} report={report} view="retrieve" />
        </section>
      )}
    </div>
  );
}

function VerificationStageResults({ run, report }: { run: AnalysisRun; report: ReferenceResolutionReportResponse }) {
  const executionStatus = report.evidenceCoverage.executionStatus;
  if (executionStatus === "PENDING") {
    return (
      <Alert>
        <AlertTitle>Evidence assessment in progress</AlertTitle>
        <AlertDescription>The worker is recording Evidence Judgements and applying the run-pinned aggregation policy where configured. Per-reference work status is shown above.</AlertDescription>
      </Alert>
    );
  }

  if (executionStatus === "NOT_RUN") {
    const evaluationOnly = run.configuration.systemOne.provider === "laya";
    return (
      <div className="space-y-4">
        <Alert>
          <AlertTitle>{evaluationOnly ? "Local Laya evaluation only" : "Claim–Paper Verification not configured"}</AlertTitle>
          <AlertDescription>
            {evaluationOnly
              ? "Laya judgements in this run are uncalibrated evaluation outputs and are not aggregated into final Claim–Paper Verification statuses."
              : "This Analysis Run did not configure a final Claim–Paper Verification policy, so no Evidence Coverage Report outcomes were produced."}
          </AlertDescription>
        </Alert>
        {evaluationOnly && report.referenceResolution.entries.length > 0 && (
          <VerificationResults runId={report.analysisRunId} report={report} view="judge" />
        )}
      </div>
    );
  }

  return <ReportResults report={report} view="summary" />;
}

export function AnalysisRunStageResults({
  run,
  selectedStage,
  backHref,
  parsedDocument,
  report,
  parsedLoading,
  reportLoading,
  parsedError,
  reportError,
  onSelectStage,
}: {
  run: AnalysisRun;
  selectedStage: PipelineStageId;
  backHref: string;
  parsedDocument: ParsedDocument | null;
  report: ReferenceResolutionReportResponse | null;
  parsedLoading: boolean;
  reportLoading: boolean;
  parsedError: string | null;
  reportError: string | null;
  onSelectStage: (stage: PipelineStageId) => void;
}) {
  const resultsScopeRef = useRef<HTMLElement>(null);
  const stageDetailsHeaderRef = useRef<HTMLElement>(null);
  const stickyNavigationRef = useRef<HTMLDivElement>(null);
  const stageSelectionRequestRef = useRef(0);
  const [showStickyNavigation, setShowStickyNavigation] = useState(false);
  const stage = PIPELINE_STAGES.find((candidate) => candidate.id === selectedStage) ?? PIPELINE_STAGES[0];
  const stageState = pipelineStageState(run, stage.id);
  const needsParsedDocument = stage.id === "source";
  const needsReport = ["references", "access", "evidence", "verification"].includes(stage.id);
  const loading = needsParsedDocument ? parsedLoading : needsReport && reportLoading;
  const error = needsParsedDocument ? parsedError : needsReport ? reportError : null;

  async function selectStageFromStickyNavigation(nextStage: PipelineStageId) {
    const requestId = ++stageSelectionRequestRef.current;
    const pipelineStages = document.getElementById("analysis-pipeline-stages");
    if (pipelineStages) await scrollIntoViewAndWaitForCompletion(pipelineStages);

    if (requestId === stageSelectionRequestRef.current) onSelectStage(nextStage);
  }

  useEffect(() => () => {
    stageSelectionRequestRef.current += 1;
  }, []);

  useEffect(() => {
    let frame = 0;

    const updateStickyNavigation = () => {
      const resultsScope = resultsScopeRef.current;
      const stageDetailsHeader = stageDetailsHeaderRef.current;
      if (!resultsScope || !stageDetailsHeader) {
        setShowStickyNavigation(false);
        return;
      }

      const resultsBounds = resultsScope.getBoundingClientRect();
      const detailsHeaderBounds = stageDetailsHeader.getBoundingClientRect();
      const stickyHeight = stickyNavigationRef.current?.getBoundingClientRect().height ?? 0;
      const shouldShow = detailsHeaderBounds.bottom <= 0
        && resultsBounds.top < 0
        && resultsBounds.bottom > stickyHeight + 1;

      setShowStickyNavigation((current) => current === shouldShow ? current : shouldShow);
    };

    const scheduleUpdate = () => {
      if (frame !== 0) return;
      frame = window.requestAnimationFrame(() => {
        frame = 0;
        updateStickyNavigation();
      });
    };

    const resizeObserver = new ResizeObserver(scheduleUpdate);
    if (resultsScopeRef.current) resizeObserver.observe(resultsScopeRef.current);
    if (stageDetailsHeaderRef.current) resizeObserver.observe(stageDetailsHeaderRef.current);
    if (stickyNavigationRef.current) resizeObserver.observe(stickyNavigationRef.current);

    window.addEventListener("scroll", scheduleUpdate, { passive: true });
    window.addEventListener("resize", scheduleUpdate);
    updateStickyNavigation();

    return () => {
      window.removeEventListener("scroll", scheduleUpdate);
      window.removeEventListener("resize", scheduleUpdate);
      resizeObserver.disconnect();
      if (frame !== 0) window.cancelAnimationFrame(frame);
    };
  }, [selectedStage, showStickyNavigation]);

  return (
    <section ref={resultsScopeRef} id="pipeline-results" className="pipeline-results space-y-4 rounded-xl border border-border bg-card p-4 shadow-sm sm:p-6" aria-labelledby="pipeline-result-heading" key={stage.id}>
      <div
        ref={stickyNavigationRef}
        data-sticky-step-navigation
        aria-hidden={!showStickyNavigation}
        inert={!showStickyNavigation}
        className={cn(
          "fixed inset-x-0 top-0 z-50 border-b border-border/70 bg-card/95 px-2 shadow-lg backdrop-blur transition-[opacity,translate] duration-300 ease-out supports-[backdrop-filter]:bg-card/90 motion-reduce:transition-none sm:px-4",
          showStickyNavigation ? "translate-y-0 opacity-100" : "-translate-y-full pointer-events-none opacity-0",
        )}
      >
        <div className="mx-auto grid max-w-6xl grid-cols-[2rem_minmax(0,1fr)_2rem] items-center gap-1 py-1">
          <BackLink
            href={backHref}
            label="Back to Analysis Runs"
          />
          <PipelineStageQuickNavigation selectedStage={stage.id} onSelectStage={selectStageFromStickyNavigation} />
          <span aria-hidden="true" className="size-8" />
        </div>
      </div>

      <header ref={stageDetailsHeaderRef} className="space-y-2">
        <p className="font-mono text-xs tracking-[0.12em] text-muted-foreground uppercase">Pipeline stage {stage.number}</p>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0 space-y-1">
            <h3 id="pipeline-result-heading" className="font-heading text-xl font-semibold tracking-tight">{stage.label}</h3>
            <p className="max-w-3xl text-sm leading-relaxed text-muted-foreground">{stage.description}</p>
          </div>
          <Badge variant="outline" className={cn(stageState === "failed" ? "border-destructive/30 bg-destructive/5 text-destructive" : stageState === "active" ? "border-primary/25 bg-primary/10 text-primary" : "")}>{PIPELINE_STAGE_STATE_LABELS[stageState]}</Badge>
        </div>
      </header>

      <Separator />

      <PipelineConfiguration run={run} stageId={stage.id} />
      <PipelineWorkProgress run={run} stageId={stage.id} />

      {loading && <p className="flex items-center gap-2 text-sm text-muted-foreground" role="status"><Spinner aria-hidden="true" /> Loading persisted results…</p>}
      {error && <Alert variant="destructive"><AlertTitle>Results unavailable</AlertTitle><AlertDescription>{error}</AlertDescription></Alert>}
      {!loading && !error && needsParsedDocument && !parsedDocument && <RunResultsUnavailable stage={stage.id} run={run} />}
      {!loading && !error && needsReport && !report && <RunResultsUnavailable stage={stage.id} run={run} />}

      {!loading && !error && parsedDocument && stage.id === "source" && (
        <div className="space-y-5">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <ResultMetric label="Sections" value={parsedDocument.sections.length} />
            <ResultMetric label="Citation Contexts" value={parsedDocument.citationContexts.length} />
            <ResultMetric label="Atomic Claims" value={parsedDocument.citationContexts.reduce((count, context) => count + context.atomicClaims.length, 0)} />
            <ResultMetric label="Bibliography Entries" value={parsedDocument.bibliographyEntries.length} />
          </div>
          <p className="rounded-md bg-muted/40 px-3 py-2 text-xs leading-relaxed text-muted-foreground">
            Parser {parsedDocument.parser.provider} {parsedDocument.parser.version}. Source offsets are zero-based, end-exclusive UTF-16 indexes in the normalized Source Document text.
          </p>
          <div className="space-y-8">
            <AnnotationResults parsedDocument={parsedDocument} view="sections" />
            <AnnotationResults parsedDocument={parsedDocument} view="annotations" />
            <AnnotationResults parsedDocument={parsedDocument} view="claims" />
          </div>
        </div>
      )}

      {!loading && !error && report && stage.id === "references" && <ReferenceMatchResults report={report} view="all" />}
      {!loading && !error && report && stage.id === "access" && <AccessResults report={report} view="all" />}
      {!loading && !error && report && stage.id === "evidence" && <IndexingResults report={report} view="all" />}
      {!loading && !error && report && stage.id === "verification" && (
        <VerificationStageResults run={run} report={report} />
      )}
    </section>
  );
}
