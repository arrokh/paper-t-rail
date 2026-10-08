"use client";

import { useEffect, useRef, useState, type Ref } from "react";
import { RotateCcw } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Spinner } from "@/components/ui/spinner";
import { ClaimEvidencePassages } from "@/features/reference-resolution/components/claim-evidence-passages";
import { CitedPaperAccessSummary } from "@/features/reference-resolution/components/cited-paper-access-summary";
import { ReferenceResolutionBadge } from "@/features/reference-resolution/components/reference-resolution-badge";
import { BackLink } from "@/features/workspace/components/back-link";
import { PIPELINE_STAGES, PIPELINE_STAGE_STATE_LABELS, pipelineStage, pipelineStageState, type PipelineStageId } from "@/features/analysis-runs/pipeline";
import { PIPELINE_STAGE_SELECTED_CLASSES, PIPELINE_STAGE_STATE_CLASSES } from "@/features/analysis-runs/components/analysis-pipeline-chart";
import { PipelineResultMetricFilters, type PipelineResultFilterOption } from "@/features/analysis-runs/components/pipeline-result-metric-filters";
import { usePipelineResultFilter, usePipelineStageFilterReset } from "@/features/analysis-runs/hooks/use-pipeline-result-filter";
import type { AnalysisRun, ParsedDocument, ReferenceResolutionReportResponse } from "@/features/analysis-runs/types";
import { displayReferenceKey } from "@/lib/display-reference-key";
import { cn } from "@/lib/utils";

type ReportEntry = ReferenceResolutionReportResponse["referenceResolution"]["entries"][number];
type VerificationOutcome = ReportEntry["verificationOutcomes"][number];

const SOURCE_RESULT_FILTERS = [
  { id: "sections", label: "Sections", description: "Document headings and their source-text spans persisted by the PDF parser." },
  { id: "citations", label: "Citation Contexts", description: "Source passages where citation markers were found, with their parsed citation occurrences." },
  { id: "claims", label: "Atomic Claims", description: "Claims extracted from citation contexts. Links from claims to references are inferred and provisional." },
  { id: "bibliography", label: "Bibliography Entries", description: "Reference entries parsed from the document’s bibliography." },
] as const;
const SOURCE_RESULT_FILTER_IDS = SOURCE_RESULT_FILTERS.map(({ id }) => id);

const RESOLUTION_STATUS_FILTERS = ["RESOLVED", "UNRESOLVED", "UNSUPPORTED_REFERENCE_TYPE", "NOT_ATTEMPTED", "RESOLUTION_FAILED"] as const;
const ACCESS_STATUS_FILTERS = ["FULL_TEXT_AVAILABLE", "ABSTRACT_ONLY", "METADATA_ONLY", "UNAVAILABLE", "NO_ACCESS_RESULT"] as const;
const INDEXING_STATUS_FILTERS = ["NOT_STARTED", "WAITING", "IN_PROGRESS", "COMPLETED", "SKIPPED", "FAILED"] as const;
const VERIFICATION_STATUS_FILTERS = [
  "SUPPORTED",
  "PARTIALLY_SUPPORTED",
  "CONTRADICTED",
  "INSUFFICIENT_EVIDENCE",
  "INACCESSIBLE",
  "UNRESOLVED",
  "UNSUPPORTED_REFERENCE_TYPE",
  "INCOMPLETE",
] as const;
const VERIFICATION_SUMMARY_FILTERS = ["ALL_PAIRS", "COMPLETED_PAIRS", "COMPARABLE_CONFLICTS"] as const;
const VERIFICATION_RESULTS_FILTERS = ["ALL_PAIRS", "WITH_EVIDENCE_PASSAGES", "WITH_JUDGEMENTS", "INCOMPLETE_PAIRS"] as const;

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

function matchesSelectedFilter(selectedValues: ReadonlySet<string>, value: string): boolean {
  return selectedValues.size === 0 || selectedValues.has(value);
}

function matchesVerificationSummaryFilter(selectedValues: ReadonlySet<string>, outcome: VerificationOutcome): boolean {
  return selectedValues.size === 0
    || selectedValues.has("ALL_PAIRS")
    || (selectedValues.has("COMPLETED_PAIRS") && outcome.processingStatus === "COMPLETED")
    || (selectedValues.has("COMPARABLE_CONFLICTS") && outcome.evidenceConflict);
}

function matchesVerificationResultsFilter(selectedValues: ReadonlySet<string>, outcome: VerificationOutcome): boolean {
  return selectedValues.size === 0
    || selectedValues.has("ALL_PAIRS")
    || (selectedValues.has("WITH_EVIDENCE_PASSAGES") && outcome.evidencePassages.length > 0)
    || (selectedValues.has("WITH_JUDGEMENTS") && outcome.evidencePassages.some((passage) => passage.evidenceJudgement))
    || (selectedValues.has("INCOMPLETE_PAIRS") && outcome.processingStatus !== "COMPLETED");
}

function displayCitationTargetKey(parsedDocument: ParsedDocument, referenceKey: string): string {
  const reference = parsedDocument.bibliographyEntries.find((entry) => entry.localReferenceKey === referenceKey);
  return displayReferenceKey(reference ?? { localReferenceKey: referenceKey }, parsedDocument.bibliographyEntries);
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
  run,
  selectedStage,
  onSelectStage,
}: {
  run: AnalysisRun;
  selectedStage: PipelineStageId;
  onSelectStage: (stage: PipelineStageId) => void;
}) {
  return (
    <nav className="mx-auto w-full min-w-0 max-w-full overflow-x-auto overflow-y-hidden px-1 py-2 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden" aria-label="Analysis pipeline stages">
      <ol className="mx-auto flex w-fit min-w-max items-center justify-center gap-1">
        {PIPELINE_STAGES.map((pipelineStage) => {
          const selected = pipelineStage.id === selectedStage;
          const state = pipelineStageState(run, pipelineStage.id);
          return (
            <li key={pipelineStage.id}>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                aria-current={selected ? "step" : undefined}
                aria-label={`${pipelineStage.number} ${pipelineStage.label}: ${PIPELINE_STAGE_STATE_LABELS[state]}${selected ? ", selected stage" : ""}`}
                className={cn(
                  "h-8 shrink-0 gap-1.5 rounded-lg border px-2 text-xs transition-all hover:-translate-y-0.5 hover:border-info-foreground hover:shadow-md motion-reduce:transition-none motion-reduce:hover:translate-y-0",
                  PIPELINE_STAGE_STATE_CLASSES[state],
                  selected && PIPELINE_STAGE_SELECTED_CLASSES,
                )}
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

function persistedExecutionStatus(status: string | undefined, loading: boolean, error: string | null): string {
  if (status) return status;
  if (loading) return "Loading";
  return error ? "Unavailable" : "Not reported";
}

function PipelineConfiguration({
  run,
  stageId,
  report,
  reportLoading,
  reportError,
  stickyBoundaryRef,
}: {
  run: AnalysisRun;
  stageId: PipelineStageId;
  report: ReferenceResolutionReportResponse | null;
  reportLoading: boolean;
  reportError: string | null;
  stickyBoundaryRef: Ref<HTMLElement>;
}) {
  const configuration = run.configuration;
  const stageProgress = run.pipeline?.stages.find((candidate) => candidate.id === stageId);
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
          ["Execution status", persistedExecutionStatus(report?.referenceResolution.executionStatus, reportLoading, reportError)],
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
              ["Cited Paper parser", providerLabel(configuration.citedPaperParser ?? configuration.sourceParser)],
              ["Embedding provider", providerLabel(configuration.embedding)],
              ["Retrieval profile", configuration.retrieval.profileId],
              ["Candidate limits", `vector ${configuration.retrieval.vectorCandidateLimit} · lexical ${configuration.retrieval.lexicalCandidateLimit} · final ${configuration.retrieval.finalCandidateLimit}`],
              ["Rank fusion constant", String(configuration.retrieval.reciprocalRankFusionConstant)],
            ]
          : [
              ["System One", providerLabel(configuration.systemOne)],
              ["Aggregation status", persistedExecutionStatus(report?.evidenceCoverage.executionStatus, reportLoading, reportError)],
              ["Verification policy", configuration.aggregation?.verificationPolicyVersion ?? "Not configured"],
              ["Aggregation policy", configuration.aggregation?.aggregationPolicyVersion ?? "Not configured"],
              ["Pinned thresholds", thresholdLabel],
            ];

  return (
    <section ref={stickyBoundaryRef} className="space-y-3 rounded-lg border border-border bg-muted/20 p-4" aria-label={`${pipelineStage(stageId).label} configuration and persisted progress`}>
      <div>
        <h3 className="font-medium">Run-pinned configuration</h3>
        <p className="mt-1 text-xs text-muted-foreground">Provider and policy selections are pinned to this immutable Analysis Run. Execution statuses come from persisted results.</p>
      </div>
      <dl className="grid gap-3 text-sm sm:grid-cols-2 xl:grid-cols-3">
        {rows.map(([label, value]) => (
          <div key={label} className="min-w-0 space-y-1">
            <dt className="font-mono text-[0.65rem] tracking-wide text-muted-foreground uppercase">{label}</dt>
            <dd className="m-0 break-words">{value}</dd>
          </div>
        ))}
      </dl>
      {stageProgress && (
        <p className="border-t border-border/70 pt-3 text-xs text-muted-foreground">
          {stageProgress.counts.total} items · {stageProgress.counts.completed} complete · {stageProgress.counts.inProgress} in progress · {stageProgress.counts.waiting} waiting · {stageProgress.counts.skipped} skipped · {stageProgress.counts.failed} failed
        </p>
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
                      {target.markerText} · {target.bibliographyTitle || displayCitationTargetKey(parsedDocument, target.bibliographyReferenceKey)} · inferred
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

function ParsedBibliographyResults({ parsedDocument }: { parsedDocument: ParsedDocument }) {
  return (
    <section className="space-y-3" aria-labelledby="parsed-bibliography-heading">
      <div className="flex items-center justify-between gap-3">
        <h4 id="parsed-bibliography-heading" className="font-heading font-semibold">Parsed bibliography entries</h4>
        <Badge variant="outline">{parsedDocument.bibliographyEntries.length}</Badge>
      </div>
      {parsedDocument.bibliographyEntries.length === 0 ? <p className="text-sm text-muted-foreground">No Bibliography Entries were parsed.</p> : (
        <ol className="space-y-2">
          {parsedDocument.bibliographyEntries.map((entry) => (
            <li key={entry.localReferenceKey} className="rounded-lg border border-border bg-card p-4">
              <div className="flex flex-wrap items-start justify-between gap-2">
                <p className="font-mono text-xs text-muted-foreground">{displayReferenceKey(entry, parsedDocument.bibliographyEntries)} · {entry.referenceType.replaceAll("_", " ").toLowerCase()}</p>
                {entry.year && <span className="font-mono text-xs text-muted-foreground">{entry.year}</span>}
              </div>
              <h5 className="mt-1 break-words font-medium">{entry.title || entry.rawText}</h5>
              {entry.authors.length > 0 && <p className="mt-1 break-words text-sm text-muted-foreground">{entry.authors.join(", ")}</p>}
              {entry.title && (
                <details className="mt-3 border-t border-border pt-3">
                  <summary className="cursor-pointer text-xs font-medium text-muted-foreground">Original bibliography text</summary>
                  <p className="mt-2 break-words text-sm leading-relaxed text-muted-foreground">{entry.rawText}</p>
                </details>
              )}
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

function ReferenceMatchResults({ report, view }: { report: ReferenceResolutionReportResponse; view: string }) {
  const resolution = report.referenceResolution;
  const { selectedValues, toggleValue, reset } = usePipelineResultFilter("references", RESOLUTION_STATUS_FILTERS);
  const filterOptions: PipelineResultFilterOption[] = [
    { id: "RESOLVED", label: "Resolved", description: "Matched to a Canonical Paper under this run’s reference-matching policy.", value: resolution.summary.resolved },
    { id: "UNRESOLVED", label: "Unresolved", description: "Matching ran, but no candidate met the configured threshold.", value: resolution.summary.unresolved },
    { id: "UNSUPPORTED_REFERENCE_TYPE", label: "Unsupported reference type", description: "The Bibliography Entry type is not supported for automatic matching.", value: resolution.summary.unsupportedReferenceType },
    { id: "NOT_ATTEMPTED", label: "Not attempted", description: "Matching was skipped because the entry was outside the configured policy.", value: resolution.summary.notAttempted },
    { id: "RESOLUTION_FAILED", label: "Resolution failed", description: "The matching attempt failed before a result could be stored.", value: resolution.summary.failed },
  ];
  const displayedEntries = resolution.entries.filter((entry) => matchesSelectedFilter(selectedValues, entry.status));

  return (
    <div className="space-y-5">
      <PipelineResultMetricFilters
        label="Filter bibliography entries by resolution status"
        options={filterOptions}
        selectedValues={selectedValues}
        onToggle={toggleValue}
        onReset={reset}
        className="sm:grid-cols-3"
      />
      {resolution.entries.length === 0 ? <p className="text-sm text-muted-foreground">No Bibliography Entries were available for resolution.</p> : displayedEntries.length === 0 ? <p className="text-sm text-muted-foreground">No entries match the selected resolution statuses.</p> : (
        <ol className="space-y-2">
          {displayedEntries.map((entry) => <ReferenceMatchCard key={entry.localReferenceKey} entry={entry} references={resolution.entries} view={view} />)}
        </ol>
      )}
    </div>
  );
}

function ReferenceMatchCard({ entry, references, view }: { entry: ReportEntry; references: readonly ReportEntry[]; view: string }) {
  return (
    <li className="rounded-lg border border-border bg-card p-4">
      <div className="flex items-start justify-between gap-3">
        <p className="min-w-0 break-words font-mono text-xs text-muted-foreground">{displayReferenceKey(entry, references)}{entry.year ? ` · ${entry.year}` : ""}</p>
        <ReferenceResolutionBadge status={entry.status} />
      </div>
      <div className="mt-1 min-w-0 space-y-1">
        <h4 className="break-words font-medium">{entry.title || displayReferenceKey(entry, references)}</h4>
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
  const { selectedValues, toggleValue, reset } = usePipelineResultFilter("access", ACCESS_STATUS_FILTERS);
  const accessStates = ["FULL_TEXT_AVAILABLE", "ABSTRACT_ONLY", "METADATA_ONLY", "UNAVAILABLE", "NO_ACCESS_RESULT"] as const;
  if (view === "acquire") {
    return (
      <div className="space-y-4">
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {accessStates.map((state) => (
            <ResultMetric key={state} label={state.replaceAll("_", " ").toLowerCase()} value={entries.filter((entry) => (entry.citedPaperAccess?.accessStatus ?? "NO_ACCESS_RESULT") === state).length} />
          ))}
        </div>
        <ol className="space-y-3">
          {entries.map((entry) => (
            <li key={entry.localReferenceKey} className="rounded-lg border border-border bg-card p-4">
              <h4 className="break-words font-medium">{entry.title || displayReferenceKey(entry, entries)}</h4>
              <p className="mt-1 text-xs text-muted-foreground">{displayReferenceKey(entry, entries)} · {entry.status.replaceAll("_", " ").toLowerCase()}</p>
              <CitedPaperAccessSummary
                access={entry.citedPaperAccess}
                progressStatus={entry.accessProgressStatus}
                progressReason={entry.accessProgressReason}
              />
            </li>
          ))}
        </ol>
      </div>
    );
  }

  if (view === "discover") {
    const discovered = entries.filter((entry) => entry.citedPaperAccess);
    const withoutOutcome = entries.filter((entry) => !entry.citedPaperAccess);
    return (
      <div className="space-y-4">
        <p className="text-sm leading-relaxed text-muted-foreground">These are the persisted legal access discovery outcomes. Entries without a resolved Canonical Paper can skip this worker operation.</p>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <ResultMetric label="Access outcomes" value={discovered.length} />
          <ResultMetric label="Full text acquired" value={discovered.filter((entry) => entry.citedPaperAccess?.accessStatus === "FULL_TEXT_AVAILABLE").length} />
          <ResultMetric label="Abstract only" value={discovered.filter((entry) => entry.citedPaperAccess?.accessStatus === "ABSTRACT_ONLY").length} />
          <ResultMetric label="No full text" value={discovered.filter((entry) => entry.citedPaperAccess?.accessStatus === "METADATA_ONLY" || entry.citedPaperAccess?.accessStatus === "UNAVAILABLE").length} />
          <ResultMetric label="No access outcome" value={withoutOutcome.length} />
        </div>
        {discovered.length === 0 ? <p className="text-sm text-muted-foreground">No access discovery result was persisted.</p> : (
          <ol className="space-y-2">
            {discovered.map((entry) => (
              <li key={entry.localReferenceKey} className="rounded-lg border border-border bg-card p-4">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0"><p className="font-mono text-xs text-muted-foreground">{displayReferenceKey(entry, entries)} · {entry.citedPaperAccess?.providerId}</p><h4 className="break-words font-medium">{entry.title || displayReferenceKey(entry, entries)}</h4></div>
                  <Badge variant="outline">{entry.citedPaperAccess?.accessStatus.replaceAll("_", " ").toLowerCase()}</Badge>
                </div>
                <CitedPaperAccessSummary
                  access={entry.citedPaperAccess}
                  progressStatus={entry.accessProgressStatus}
                  progressReason={entry.accessProgressReason}
                />
              </li>
            ))}
          </ol>
        )}
        {withoutOutcome.length > 0 && (
          <section className="space-y-2 border-t border-border pt-4" aria-label="References without an access outcome">
            <h4 className="font-heading font-semibold">No access outcome</h4>
            <ol className="space-y-2">
              {withoutOutcome.map((entry) => (
                <li key={entry.localReferenceKey} className="rounded-lg border border-border bg-card p-3">
                  <p className="mb-2 break-words text-sm font-medium">{entry.title || displayReferenceKey(entry, entries)}</p>
                  <CitedPaperAccessSummary
                    access={null}
                    progressStatus={entry.accessProgressStatus}
                    progressReason={entry.accessProgressReason}
                  />
                </li>
              ))}
            </ol>
          </section>
        )}
      </div>
    );
  }

  const accessFilterOptions: PipelineResultFilterOption[] = [
    { id: "FULL_TEXT_AVAILABLE", label: "Full text available", description: "A permitted full-text source was acquired and parsed; language eligibility is separate.", value: entries.filter((entry) => entry.citedPaperAccess?.accessStatus === "FULL_TEXT_AVAILABLE").length },
    { id: "ABSTRACT_ONLY", label: "Abstract only", description: "An abstract is available, but no usable full-text source was recorded.", value: entries.filter((entry) => entry.citedPaperAccess?.accessStatus === "ABSTRACT_ONLY").length },
    { id: "METADATA_ONLY", label: "Metadata only", description: "Bibliographic metadata is available, but no abstract or full-text source was found.", value: entries.filter((entry) => entry.citedPaperAccess?.accessStatus === "METADATA_ONLY").length },
    { id: "UNAVAILABLE", label: "Unavailable", description: "Access lookup ran but found no usable full text or abstract.", value: entries.filter((entry) => entry.citedPaperAccess?.accessStatus === "UNAVAILABLE").length },
    { id: "NO_ACCESS_RESULT", label: "No access result", description: "No access lookup result was persisted, often because an earlier stage skipped the lookup.", value: entries.filter((entry) => !entry.citedPaperAccess).length },
  ];
  const displayedEntries = entries.filter((entry) => matchesSelectedFilter(selectedValues, entry.citedPaperAccess?.accessStatus ?? "NO_ACCESS_RESULT"));
  return (
    <div className="space-y-4">
      <PipelineResultMetricFilters
        label="Filter cited sources by access status"
        options={accessFilterOptions}
        selectedValues={selectedValues}
        onToggle={toggleValue}
        onReset={reset}
        className="sm:grid-cols-3"
      />
      {entries.length === 0 ? <p className="text-sm text-muted-foreground">No Cited Papers were available for access lookup.</p> : displayedEntries.length === 0 ? <p className="text-sm text-muted-foreground">No references match the selected access statuses.</p> : (
        <ol className="space-y-3">
          {displayedEntries.map((entry) => (
            <li key={entry.localReferenceKey} className="rounded-lg border border-border bg-card p-4">
              <div className="mb-2 flex flex-wrap items-start justify-between gap-3">
                <div><p className="font-mono text-xs text-muted-foreground">{displayReferenceKey(entry, entries)}</p><h4 className="break-words font-medium">{entry.title || displayReferenceKey(entry, entries)}</h4></div>
                {entry.citedPaperAccess && <Badge variant="outline">{entry.citedPaperAccess.language?.toLowerCase() ?? "language not recorded"}</Badge>}
              </div>
              <CitedPaperAccessSummary
                access={entry.citedPaperAccess}
                progressStatus={entry.accessProgressStatus}
                progressReason={entry.accessProgressReason}
              />
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

function IndexingResults({ run, report, view }: { run: AnalysisRun; report: ReferenceResolutionReportResponse; view: string }) {
  const entries = report.referenceResolution.entries;
  const { selectedValues, toggleValue, reset } = usePipelineResultFilter("evidence", INDEXING_STATUS_FILTERS);
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
                  <div className="min-w-0"><p className="font-mono text-xs text-muted-foreground">{displayReferenceKey(entry, entries)}</p><p className="break-words text-sm font-medium">{entry.title || displayReferenceKey(entry, entries)}</p></div>
                  <div className="flex flex-wrap items-center gap-2"><Badge variant="outline">{access.accessStatus.replaceAll("_", " ").toLowerCase()}</Badge><Badge variant="secondary">{access.language?.toLowerCase() ?? "language not recorded"}</Badge></div>
                  <p className="w-full text-xs text-muted-foreground">
                    Detector {access.languageDetectorVersion ?? "not recorded"}
                    {access.accessReasons.includes("LANGUAGE_UNSUPPORTED")
                      ? " · full text is available, but language eligibility prevents semantic assessment"
                      : " · full-text language eligibility was satisfied"}
                  </p>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    );
  }

  const evidenceStageItems = run.pipeline?.stages
    .find((stage) => stage.id === "evidence")
    ?.steps.flatMap((step) => step.items) ?? [];
  const evidenceStatusByReference = new Map(evidenceStageItems.map((item) => [item.label, item.status]));
  const indexingStatus = (entry: ReportEntry) => {
    const persistedStatus = evidenceStatusByReference.get(entry.localReferenceKey);
    if (persistedStatus) return persistedStatus;

    const indexing = entry.citedPaperAccess?.evidenceIndexing;
    if (indexing?.status === "PENDING") return "WAITING";
    return indexing?.status ?? "NOT_STARTED";
  };
  const indexingFilterOptions: PipelineResultFilterOption[] = [
    { id: "NOT_STARTED", label: "Not started", description: "No evidence-indexing work has started for this Cited Paper.", value: entries.filter((entry) => indexingStatus(entry) === "NOT_STARTED").length },
    { id: "WAITING", label: "Waiting", description: "Evidence-indexing work is queued and has not started yet.", value: entries.filter((entry) => indexingStatus(entry) === "WAITING").length },
    { id: "IN_PROGRESS", label: "In progress", description: "The worker is processing eligible full text for evidence retrieval.", value: entries.filter((entry) => indexingStatus(entry) === "IN_PROGRESS").length },
    { id: "COMPLETED", label: "Complete", description: "Indexing finished and evidence passages are available for retrieval.", value: entries.filter((entry) => indexingStatus(entry) === "COMPLETED").length },
    { id: "SKIPPED", label: "Skipped", description: "Indexing was bypassed, usually because no eligible acquired full text was available.", value: entries.filter((entry) => indexingStatus(entry) === "SKIPPED").length },
    { id: "FAILED", label: "Failed", description: "Indexing was attempted but failed; review the item’s failure details.", value: entries.filter((entry) => indexingStatus(entry) === "FAILED").length },
  ];
  const displayedEntries = entries.filter((entry) => matchesSelectedFilter(selectedValues, indexingStatus(entry)));

  return (
    <div className="space-y-4">
      <PipelineResultMetricFilters
        label="Filter cited sources by evidence indexing status"
        options={indexingFilterOptions}
        selectedValues={selectedValues}
        onToggle={toggleValue}
        onReset={reset}
        className="sm:grid-cols-3 xl:grid-cols-6"
      />
      {entries.length === 0 ? <p className="text-sm text-muted-foreground">No Cited Paper entries are available for evidence indexing.</p> : displayedEntries.length === 0 ? <p className="text-sm text-muted-foreground">No references match the selected indexing statuses.</p> : (
        <ol className="space-y-2">
        {displayedEntries.map((entry) => {
          const access = entry.citedPaperAccess;
          const indexing = access?.evidenceIndexing;
          return (
            <li key={entry.localReferenceKey} className="rounded-lg border border-border bg-card p-4">
              <div className="flex flex-wrap items-start justify-between gap-3"><div><p className="font-mono text-xs text-muted-foreground">{displayReferenceKey(entry, entries)}</p><h4 className="break-words font-medium">{entry.title || displayReferenceKey(entry, entries)}</h4></div><Badge variant="outline" className="capitalize">{indexingStatus(entry).replaceAll("_", " ").toLowerCase()}</Badge></div>
              {indexing ? (
                <dl className="mt-3 grid gap-3 border-t border-border pt-3 text-xs sm:grid-cols-2">
                  <div><dt className="font-mono uppercase text-muted-foreground">Cited Paper parser</dt><dd className="mt-1">{indexing.parserProvider ?? "Not recorded"} {indexing.parserVersion ?? ""}</dd></div>
                  <div><dt className="font-mono uppercase text-muted-foreground">Detected language</dt><dd className="mt-1">{indexing.language?.toLowerCase() ?? "Not recorded"} · {indexing.languageDetectorVersion ?? "detector not recorded"}</dd></div>
                  <div className="min-w-0"><dt className="font-mono uppercase text-muted-foreground">Pinned asset</dt><dd className="mt-1 break-all font-mono">{indexing.assetId ?? "Not recorded"}</dd></div>
                  <div><dt className="font-mono uppercase text-muted-foreground">Indexing result</dt><dd className="mt-1 break-words">{indexing.failureReason?.replaceAll("_", " ").toLowerCase() ?? "Evidence index persisted"}</dd></div>
                </dl>
              ) : <p className="mt-3 text-sm text-muted-foreground">No evidence indexing record is available for this reference.</p>}
            </li>
          );
        })}
        </ol>
      )}
    </div>
  );
}

function VerificationResults({
  runId,
  report,
  view,
  statusFilter,
  summaryFilter,
}: {
  runId: string;
  report: ReferenceResolutionReportResponse;
  view: string;
  statusFilter?: ReadonlySet<string>;
  summaryFilter?: ReadonlySet<string>;
}) {
  const outcomes: Array<{ outcome: VerificationOutcome; entry: ReportEntry }> = report.referenceResolution.entries.flatMap((entry) =>
    entry.verificationOutcomes.map((outcome) => ({ outcome, entry })),
  );
  const { selectedValues: resultMetricFilters, toggleValue, reset } = usePipelineResultFilter(
    "verification",
    VERIFICATION_RESULTS_FILTERS,
    "verificationResultsFilter",
  );
  const displayedOutcomes = outcomes.filter(({ outcome }) => {
    if (view === "judge" && !outcome.evidencePassages.some((passage) => passage.evidenceJudgement) && !outcome.processingFailureReason) return false;
    const statusMatches = !statusFilter || matchesSelectedFilter(statusFilter, outcome.finalStatus ?? "INCOMPLETE");
    const summaryMatches = !summaryFilter || matchesVerificationSummaryFilter(summaryFilter, outcome);
    return statusMatches && summaryMatches && matchesVerificationResultsFilter(resultMetricFilters, outcome);
  });
  const judged = outcomes.filter(({ outcome }) => outcome.evidencePassages.some((passage) => passage.evidenceJudgement)).length;
  const passages = outcomes.reduce((count, { outcome }) => count + outcome.evidencePassages.length, 0);
  const incomplete = outcomes.filter(({ outcome }) => outcome.processingStatus !== "COMPLETED").length;
  const resultMetricOptions: PipelineResultFilterOption[] = [
    { id: "ALL_PAIRS", label: "Claim × Reference pairs", description: "All Claim–Reference verification pairs created for this Analysis Run.", value: outcomes.length },
    { id: "WITH_EVIDENCE_PASSAGES", label: "Evidence Passages", description: "Pairs that have one or more ranked Evidence Passages available for review.", value: passages },
    { id: "WITH_JUDGEMENTS", label: "Pairs with judgements", description: "Pairs with at least one persisted System One judgement on an Evidence Passage.", value: judged },
    { id: "INCOMPLETE_PAIRS", label: "Incomplete pairs", description: "Pairs whose verification processing has not reached a completed state.", value: incomplete },
  ];
  return (
    <div className="space-y-4">
      <PipelineResultMetricFilters
        label="Filter claim-reference results by available evidence"
        options={resultMetricOptions}
        selectedValues={resultMetricFilters}
        onToggle={toggleValue}
        onReset={reset}
        className="sm:grid-cols-4"
      />
      {displayedOutcomes.length === 0 ? <p className="text-sm text-muted-foreground">{statusFilter?.size || summaryFilter?.size || resultMetricFilters.size ? "No verification pairs match the selected filters." : view === "judge" ? "No persisted System One judgement is available for this run." : "No Claim–Reference verification pairs were created for this run."}</p> : (
        <ol className="space-y-3">
          {displayedOutcomes.map(({ outcome, entry }) => (
            <li key={outcome.id} className="rounded-lg border border-border bg-muted/10 p-3">
              <div className="mb-2 flex flex-wrap items-center gap-2">
                <span className="font-mono text-xs text-muted-foreground">{displayReferenceKey(entry, report.referenceResolution.entries)}</span>
                <ReferenceResolutionBadge status={outcome.finalStatus ?? outcome.processingStatus} />
              </div>
              <ol className="space-y-2">
                <ClaimEvidencePassages
                  analysisRunId={runId}
                  outcome={outcome}
                  indexingStatus={entry.citedPaperAccess?.evidenceIndexing?.status ?? null}
                  presentation="pipeline"
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
  const { selectedValues, toggleValue, reset } = usePipelineResultFilter("verification", VERIFICATION_STATUS_FILTERS);
  const {
    selectedValues: selectedSummaryFilters,
    toggleValue: toggleSummaryFilter,
    reset: resetSummaryFilters,
  } = usePipelineResultFilter("verification", VERIFICATION_SUMMARY_FILTERS, "verificationSummaryFilter");
  const outcomeFilterOptions: PipelineResultFilterOption[] = [
    { id: "SUPPORTED", label: "Supported", description: "Evidence directly supports the claim within the cited reference.", value: counts.supported },
    { id: "PARTIALLY_SUPPORTED", label: "Partially supported", description: "Evidence supports part of the claim, but not its full scope.", value: counts.partiallySupported },
    { id: "CONTRADICTED", label: "Contradicted", description: "Evidence in the cited reference conflicts with the claim.", value: counts.contradicted },
    { id: "INSUFFICIENT_EVIDENCE", label: "Insufficient evidence", description: "Available evidence passages are not enough to assess the claim.", value: counts.insufficientEvidence },
    { id: "INACCESSIBLE", label: "Inaccessible", description: "The claim could not be assessed because usable evidence was unavailable.", value: counts.inaccessible },
    { id: "UNRESOLVED", label: "Unresolved", description: "Reference matching did not identify a Canonical Paper, so no evidence judgement was produced.", value: counts.unresolved },
    { id: "UNSUPPORTED_REFERENCE_TYPE", label: "Unsupported reference type", description: "This reference type is outside the verification flow supported by this run.", value: counts.unsupportedReferenceType },
    { id: "INCOMPLETE", label: "Incomplete", description: "Verification did not finish, so no final outcome was assigned.", value: counts.incompleteVerifications },
  ];
  const summaryFilterOptions: PipelineResultFilterOption[] = [
    { id: "ALL_PAIRS", label: "Claim–Reference pairs", description: "Show all persisted Claim–Reference verification pairs.", value: counts.totalVerifications },
    { id: "COMPLETED_PAIRS", label: "Completed pairs", description: "Show pairs whose verification processing reached a completed state.", value: counts.completedVerifications },
    { id: "COMPARABLE_CONFLICTS", label: "Comparable conflicts", description: "Show pairs with comparable evidence that conflicts across passages.", value: counts.evidenceConflicts },
  ];
  return (
    <div className="space-y-5">
      <Alert>
        <AlertTitle>{view === "aggregate" ? "Deterministic aggregation" : "Research triage, not certification"}</AlertTitle>
        <AlertDescription>{view === "aggregate" ? "Final statuses use the policy and thresholds pinned to this Analysis Run. Incomplete pairs do not receive fabricated domain statuses." : coverage.triageDisclaimer}</AlertDescription>
      </Alert>
      <PipelineResultMetricFilters
        label="Filter verification pairs by completion and conflict"
        options={summaryFilterOptions}
        selectedValues={selectedSummaryFilters}
        onToggle={toggleSummaryFilter}
        onReset={resetSummaryFilters}
        className="sm:grid-cols-3"
      />
      <PipelineResultMetricFilters
        label="Filter claim-reference pairs by final verification status"
        options={outcomeFilterOptions}
        selectedValues={selectedValues}
        onToggle={toggleValue}
        onReset={reset}
        className="sm:grid-cols-4"
      />
      {view === "summary" && report.referenceResolution.entries.length > 0 && (
        <section className="space-y-3" aria-labelledby="claim-outcomes-heading">
          <h4 id="claim-outcomes-heading" className="font-heading font-semibold">Claim-level results</h4>
          <VerificationResults
            runId={report.analysisRunId}
            report={report}
            view="retrieve"
            statusFilter={selectedValues}
            summaryFilter={selectedSummaryFilters}
          />
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
    const evaluationOnly = run.configuration.systemOne.provider !== "mock";
    return (
      <div className="space-y-4">
        <Alert>
          <AlertTitle>{evaluationOnly ? "System One evaluation only" : "Claim–Paper Verification not configured"}</AlertTitle>
          <AlertDescription>
            {evaluationOnly
              ? "System One judgements in this run are uncalibrated evaluation outputs and are not aggregated into final Claim–Paper Verification statuses."
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
  const stickyBoundaryRef = useRef<HTMLElement>(null);
  const stickyNavigationRef = useRef<HTMLDivElement>(null);
  const [showStickyNavigation, setShowStickyNavigation] = useState(false);
  const stage = PIPELINE_STAGES.find((candidate) => candidate.id === selectedStage) ?? PIPELINE_STAGES[0];
  const stageFilterReset = usePipelineStageFilterReset(stage.id);
  const sourceResultFilter = usePipelineResultFilter("source", SOURCE_RESULT_FILTER_IDS);
  const needsParsedDocument = stage.id === "source";
  const needsReport = ["references", "access", "evidence", "verification"].includes(stage.id);
  const loading = needsParsedDocument ? parsedLoading : needsReport && reportLoading;
  const error = needsParsedDocument ? parsedError : needsReport ? reportError : null;
  const sourceClaimCount = parsedDocument?.citationContexts.reduce((count, context) => count + context.atomicClaims.length, 0) ?? 0;
  const sourceResultFilterOptions: PipelineResultFilterOption[] = [
    { id: "sections", label: "Sections", description: "Document headings and their source-text spans persisted by the PDF parser.", value: parsedDocument?.sections.length ?? 0 },
    { id: "citations", label: "Citation Contexts", description: "Source passages where citation markers were found, with their parsed citation occurrences.", value: parsedDocument?.citationContexts.length ?? 0 },
    { id: "claims", label: "Atomic Claims", description: "Claims extracted from citation contexts. Links from claims to references are inferred and provisional.", value: sourceClaimCount },
    { id: "bibliography", label: "Bibliography Entries", description: "Reference entries parsed from the document’s bibliography.", value: parsedDocument?.bibliographyEntries.length ?? 0 },
  ];

  useEffect(() => {
    let frame = 0;

    const updateStickyNavigation = () => {
      const resultsScope = resultsScopeRef.current;
      const stickyBoundary = stickyBoundaryRef.current;
      if (!resultsScope || !stickyBoundary) {
        setShowStickyNavigation(false);
        return;
      }

      const resultsBounds = resultsScope.getBoundingClientRect();
      const stickyBoundaryBounds = stickyBoundary.getBoundingClientRect();
      const stickyHeight = stickyNavigationRef.current?.getBoundingClientRect().height ?? 0;
      const shouldShow = stickyBoundaryBounds.bottom <= 0
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
    if (stickyBoundaryRef.current) resizeObserver.observe(stickyBoundaryRef.current);
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
    <section ref={resultsScopeRef} id="pipeline-results" className="pipeline-results space-y-4 rounded-xl border border-border bg-card p-4 shadow-sm sm:p-6" aria-label={`${stage.label} pipeline results`} key={stage.id}>
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
          <PipelineStageQuickNavigation run={run} selectedStage={stage.id} onSelectStage={onSelectStage} />
          {stageFilterReset.hasActiveFilters ? (
            <Button
              type="button"
              variant="ghost"
              size="icon"
              aria-label="Reset filters for this pipeline stage"
              title="Reset filters for this pipeline stage"
              className="size-8 rounded-full text-muted-foreground hover:text-foreground"
              onClick={stageFilterReset.reset}
            >
              <RotateCcw aria-hidden="true" />
            </Button>
          ) : <span aria-hidden="true" className="size-8" />}
        </div>
      </div>

      <PipelineConfiguration
        run={run}
        stageId={stage.id}
        report={report}
        reportLoading={reportLoading}
        reportError={reportError}
        stickyBoundaryRef={stickyBoundaryRef}
      />

      {loading && <p className="flex items-center gap-2 text-sm text-muted-foreground" role="status"><Spinner aria-hidden="true" /> Loading persisted results…</p>}
      {error && <Alert variant="destructive"><AlertTitle>Results unavailable</AlertTitle><AlertDescription>{error}</AlertDescription></Alert>}
      {!loading && !error && needsParsedDocument && !parsedDocument && <RunResultsUnavailable stage={stage.id} run={run} />}
      {!loading && !error && needsReport && !report && <RunResultsUnavailable stage={stage.id} run={run} />}

      {!loading && !error && parsedDocument && stage.id === "source" && (
        <div className="space-y-5">
          <PipelineResultMetricFilters
            label="Filter parsed source data"
            options={sourceResultFilterOptions}
            selectedValues={sourceResultFilter.selectedValues}
            onToggle={sourceResultFilter.toggleValue}
            onReset={sourceResultFilter.reset}
          />
          <div className="space-y-8">
            {matchesSelectedFilter(sourceResultFilter.selectedValues, "sections") && <AnnotationResults parsedDocument={parsedDocument} view="sections" />}
            {matchesSelectedFilter(sourceResultFilter.selectedValues, "citations") && <AnnotationResults parsedDocument={parsedDocument} view="annotations" />}
            {matchesSelectedFilter(sourceResultFilter.selectedValues, "claims") && <AnnotationResults parsedDocument={parsedDocument} view="claims" />}
            {matchesSelectedFilter(sourceResultFilter.selectedValues, "bibliography") && <ParsedBibliographyResults parsedDocument={parsedDocument} />}
          </div>
        </div>
      )}

      {!loading && !error && report && stage.id === "references" && <ReferenceMatchResults report={report} view="all" />}
      {!loading && !error && report && stage.id === "access" && <AccessResults report={report} view="all" />}
      {!loading && !error && report && stage.id === "evidence" && <IndexingResults run={run} report={report} view="all" />}
      {!loading && !error && report && stage.id === "verification" && (
        <VerificationStageResults run={run} report={report} />
      )}
    </section>
  );
}
