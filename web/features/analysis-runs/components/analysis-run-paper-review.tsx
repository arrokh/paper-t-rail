"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { ArrowLeft, ArrowRight, ChevronDown, FileText, Highlighter, Link2, RotateCcw, Search, X } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Skeleton } from "@/components/ui/skeleton";
import { AnalysisRunPaperReviewFilters, PAPER_REVIEW_FILTER_OPTIONS } from "@/features/analysis-runs/components/analysis-run-paper-review-filters";
import { SourceDocumentPdfViewer } from "@/features/analysis-runs/components/source-document-pdf-viewer";
import { usePipelineResultFilter } from "@/features/analysis-runs/hooks/use-pipeline-result-filter";
import { scrollToPaperReviewCard } from "@/features/analysis-runs/scroll-to-paper-review-card";
import { scrollToReviewItem } from "@/features/analysis-runs/scroll-to-review-item";
import { ClaimEvidencePassages } from "@/features/reference-resolution/components/claim-evidence-passages";
import { ReferenceResolutionBadge } from "@/features/reference-resolution/components/reference-resolution-badge";
import type {
  AnalysisRun,
  ClaimReferenceVerificationOutcome,
  ParsedDocument,
  ReferenceResolutionReportResponse,
} from "@/features/analysis-runs/types";
import { cn } from "@/lib/utils";

type ReferenceEntry = ReferenceResolutionReportResponse["referenceResolution"]["entries"][number];
type VerificationOutcome = ClaimReferenceVerificationOutcome;
type OutcomeGroup = { claimId: string; claimText: string; outcomes: Array<{ entry: ReferenceEntry; outcome: VerificationOutcome }> };
const EMPTY_REFERENCE_ENTRIES: ReferenceEntry[] = [];

function formatStatus(outcome: VerificationOutcome): string {
  if (outcome.finalStatus) return outcome.finalStatus;
  if (outcome.processingStatus === "PENDING" || outcome.processingStatus === "INCOMPLETE") return outcome.processingStatus;
  return "NO_FINAL_STATUS";
}

function statusLabel(status: string): string {
  return status.replaceAll("_", " ").toLowerCase();
}

function buildOutcomeGroups(entries: ReferenceEntry[]): OutcomeGroup[] {
  const groups = new Map<string, OutcomeGroup>();
  for (const entry of entries) {
    for (const outcome of entry.verificationOutcomes) {
      const current = groups.get(outcome.atomicClaimId);
      if (current) {
        current.outcomes.push({ entry, outcome });
      } else {
        groups.set(outcome.atomicClaimId, {
          claimId: outcome.atomicClaimId,
          claimText: outcome.claimText,
          outcomes: [{ entry, outcome }],
        });
      }
    }
  }
  return [...groups.values()];
}

function sourceReferences(parsedDocument: ParsedDocument | null, report: ReferenceResolutionReportResponse | null): ParsedDocument["bibliographyEntries"] {
  const references = new Map<string, ParsedDocument["bibliographyEntries"][number]>();
  for (const reference of parsedDocument?.bibliographyEntries ?? []) references.set(reference.localReferenceKey, reference);
  for (const entry of report?.referenceResolution.entries ?? []) {
    if (!references.has(entry.localReferenceKey)) {
      references.set(entry.localReferenceKey, {
        entryOrder: entry.entryOrder,
        localReferenceKey: entry.localReferenceKey,
        rawText: entry.rawText,
        title: entry.title,
        authors: entry.authors,
        year: entry.year,
        doi: entry.doi,
        referenceType: entry.referenceType,
        resolutionStatus: entry.status,
      });
    }
  }
  return [...references.values()].sort((first, second) => first.entryOrder - second.entryOrder);
}

export function AnalysisRunPaperReview({
  run,
  parsedDocument,
  report,
  parsedLoading,
  reportLoading,
  parsedError,
  reportError,
  selectedOutcomeId,
  selectedReferenceKey,
  selectedDetailSection,
  onSelectOutcome,
  onSelectReference,
  onClearReviewPair,
  onClearSelectedReference,
  hasReviewState = false,
  onClearReviewState,
  onSelectDetailSection,
}: {
  run: AnalysisRun;
  parsedDocument: ParsedDocument | null;
  report: ReferenceResolutionReportResponse | null;
  parsedLoading: boolean;
  reportLoading: boolean;
  parsedError: string | null;
  reportError: string | null;
  selectedOutcomeId: string | null;
  selectedReferenceKey: string | null;
  selectedDetailSection: "results" | "citations";
  onSelectOutcome: (outcomeId: string, localReferenceKey: string) => void;
  onSelectReference: (localReferenceKey: string) => void;
  onClearReviewPair: () => void;
  onClearSelectedReference: () => void;
  hasReviewState?: boolean;
  onClearReviewState?: () => void;
  onSelectDetailSection: (section: "results" | "citations") => void;
}) {
  const [mobilePane, setMobilePane] = useState<"paper" | "details">("paper");
  const [collapsedQuickAccessKey, setCollapsedQuickAccessKey] = useState<string | null>(null);
  const [pdfHighlightOverride, setPdfHighlightOverride] = useState<{ requestId: number; text: string[]; contextText: string | null } | null>(null);
  const pdfHighlightRequestSequence = useRef(0);
  const entries = report?.referenceResolution.entries ?? EMPTY_REFERENCE_ENTRIES;
  const outcomeGroups = useMemo(() => buildOutcomeGroups(entries), [entries]);
  const references = useMemo(() => sourceReferences(parsedDocument, report), [parsedDocument, report]);
  const allOutcomes = useMemo(() => entries.flatMap((entry) => entry.verificationOutcomes.map((outcome) => ({ entry, outcome }))), [entries]);
  const selectedReference = references.find((reference) => reference.localReferenceKey === selectedReferenceKey) ?? null;
  const selectedQuickAccessKey = selectedDetailSection === "results" && selectedOutcomeId
    ? `results:${selectedOutcomeId}`
    : selectedDetailSection === "citations" && selectedReferenceKey
      ? `citations:${selectedReferenceKey}`
      : null;
  const previousDetailSection = useRef(selectedDetailSection);

  useEffect(() => {
    const enteredDetailSection = previousDetailSection.current !== selectedDetailSection;
    previousDetailSection.current = selectedDetailSection;

    if (!selectedQuickAccessKey) return;
    if (selectedDetailSection === "results" && !enteredDetailSection) return;
    if (mobilePane === "paper" && window.matchMedia("(max-width: 47.99rem)").matches) return;

    const triggerId = selectedDetailSection === "results"
      ? `review-pair-trigger-${selectedOutcomeId}`
      : `review-bibliography-trigger-${selectedReferenceKey}`;
    let cancelScroll = () => {};
    const frame = window.requestAnimationFrame(() => {
      cancelScroll = scrollToReviewItem(document.getElementById(triggerId));
    });

    return () => {
      window.cancelAnimationFrame(frame);
      cancelScroll();
    };
  }, [selectedQuickAccessKey, selectedDetailSection, selectedOutcomeId, selectedReferenceKey, allOutcomes, references, mobilePane]);

  const pdfHighlightText = pdfHighlightOverride?.text ?? null;
  const pdfHighlightContextText = pdfHighlightOverride?.contextText ?? null;
  function chooseOutcome(outcomeId: string, referenceKey: string) {
    setMobilePane("details");
    setCollapsedQuickAccessKey(null);
    onSelectOutcome(outcomeId, referenceKey);
  }

  function chooseReference(referenceKey: string) {
    setMobilePane("details");
    setCollapsedQuickAccessKey(null);
    onSelectReference(referenceKey);
  }

  function highlightInPdf(text: string | string[], contextText: string | null = null) {
    setPdfHighlightOverride({
      requestId: ++pdfHighlightRequestSequence.current,
      text: uniqueSearchCandidates(...(Array.isArray(text) ? text : [text])),
      contextText,
    });
    setMobilePane("paper");
    scrollToPaperReviewCard();
  }

  return (
    <section aria-labelledby="paper-review-heading" className="analysis-run-paper-review space-y-4">
      <header className="space-y-1">
        <h2 id="paper-review-heading" className="m-0 text-lg font-semibold">Paper Review</h2>
        <p className="m-0 text-sm text-muted-foreground">Read the uploaded paper alongside parsed citations, bibliography links, and recorded AI judgements.</p>
      </header>

      <div className="relative left-1/2 w-[100cqw] -translate-x-1/2 px-4 sm:px-5 lg:px-6">
        <Card id="paper-review-card" className="w-full scroll-mt-2 py-0 shadow-sm md:h-[calc(100svh-1rem)] md:min-h-[32rem]">
          <CardContent className="space-y-4 px-4 py-3 sm:px-6 sm:py-4 md:flex md:h-full md:flex-col md:space-y-0">
            <div className="flex gap-2 md:hidden" role="group" aria-label="Choose Paper Review panel">
              <Button type="button" variant={mobilePane === "paper" ? "secondary" : "outline"} aria-pressed={mobilePane === "paper"} onClick={() => setMobilePane("paper")} className="flex-1">
                <FileText aria-hidden="true" /> Paper
              </Button>
              <Button type="button" variant={mobilePane === "details" ? "secondary" : "outline"} aria-pressed={mobilePane === "details"} onClick={() => setMobilePane("details")} className="flex-1">
                <Link2 aria-hidden="true" /> Details
              </Button>
            </div>

            <div className="grid min-w-0 gap-4 md:min-h-0 md:flex-1 md:grid-cols-[minmax(0,1.35fr)_minmax(19rem,0.9fr)] md:grid-rows-1 md:items-stretch md:overflow-hidden">
              <div className={cn("min-w-0", mobilePane === "paper" ? "block" : "hidden", "md:h-full md:min-h-0 md:block")}>
                <SourceDocumentPdfViewer
                  analysisRunId={run.id}
                  filename={run.filename}
                  highlightText={pdfHighlightText}
                  highlightContextText={pdfHighlightContextText}
                  highlightRequestId={pdfHighlightOverride?.requestId ?? 0}
                />
              </div>
              <aside className={cn("min-w-0", mobilePane === "details" ? "block" : "hidden", "md:flex md:h-full md:min-h-0 md:flex-col")} aria-label="Paper Review details">
                <div className="mb-3 flex items-center gap-1 rounded-xl border border-border bg-muted/60 p-1">
                  <div className="grid min-w-0 flex-1 grid-cols-2 gap-1" role="group" aria-label="Review detail type">
                    <Button
                      type="button"
                      variant={selectedDetailSection === "results" ? "default" : "ghost"}
                      aria-pressed={selectedDetailSection === "results"}
                      onClick={() => { setCollapsedQuickAccessKey(null); onSelectDetailSection("results"); }}
                      className={cn("h-auto min-h-9 min-w-0 whitespace-normal px-2 py-2 text-center text-xs leading-tight font-medium sm:text-sm", selectedDetailSection === "results" && "font-semibold shadow-sm")}
                    >
                      AI results
                    </Button>
                    <Button
                      type="button"
                      variant={selectedDetailSection === "citations" ? "default" : "ghost"}
                      aria-pressed={selectedDetailSection === "citations"}
                      onClick={() => { setCollapsedQuickAccessKey(null); onSelectDetailSection("citations"); }}
                      className={cn("h-auto min-h-9 min-w-0 whitespace-normal px-2 py-2 text-center text-xs leading-tight font-medium sm:text-sm", selectedDetailSection === "citations" && "font-semibold shadow-sm")}
                    >
                      Citations & bibliography
                    </Button>
                  </div>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    aria-label="Clear Paper Review filters and selections"
                    title="Clear Paper Review filters and selections"
                    disabled={!hasReviewState}
                    onClick={() => { setCollapsedQuickAccessKey(null); onClearReviewState?.(); }}
                  >
                    <RotateCcw aria-hidden="true" />
                  </Button>
                </div>
                <div data-review-items-viewport="" className="min-h-96 space-y-3 md:min-h-0 md:flex-1 md:overflow-y-auto md:p-2">
                  {selectedDetailSection === "results" ? (
                    <ResultsDetails
                      run={run}
                      groups={outcomeGroups}
                      allOutcomes={allOutcomes}
                      selectedOutcomeId={selectedOutcomeId}
                      parsedLoading={parsedLoading}
                      reportLoading={reportLoading}
                      parsedError={parsedError}
                      reportError={reportError}
                      onSelectOutcome={chooseOutcome}
                      onClearReviewPair={onClearReviewPair}
                      onSelectReference={chooseReference}
                      onHighlightInPdf={highlightInPdf}
                      collapsedQuickAccessKey={collapsedQuickAccessKey}
                      onCollapsedQuickAccessKeyChange={setCollapsedQuickAccessKey}
                    />
                  ) : (
                    <CitationDetails
                      references={references}
                      parsedDocument={parsedDocument}
                      parsedLoading={parsedLoading}
                      parsedError={parsedError}
                      selectedReference={selectedReference}
                      referenceResults={entries}
                      onClearSelectedReference={onClearSelectedReference}
                      onSelectReference={chooseReference}
                      onSelectOutcome={chooseOutcome}
                      onHighlightInPdf={highlightInPdf}
                      collapsedQuickAccessKey={collapsedQuickAccessKey}
                      onCollapsedQuickAccessKeyChange={setCollapsedQuickAccessKey}
                    />
                  )}
                </div>
              </aside>
            </div>
          </CardContent>
        </Card>
      </div>
    </section>
  );
}

function ResultsDetails({
  run,
  groups,
  allOutcomes,
  selectedOutcomeId,
  parsedLoading,
  reportLoading,
  parsedError,
  reportError,
  onSelectOutcome,
  onClearReviewPair,
  onSelectReference,
  onHighlightInPdf,
  collapsedQuickAccessKey,
  onCollapsedQuickAccessKeyChange,
}: {
  run: AnalysisRun;
  groups: OutcomeGroup[];
  allOutcomes: Array<{ entry: ReferenceEntry; outcome: VerificationOutcome }>;
  selectedOutcomeId: string | null;
  parsedLoading: boolean;
  reportLoading: boolean;
  parsedError: string | null;
  reportError: string | null;
  onSelectOutcome: (outcomeId: string, localReferenceKey: string) => void;
  onClearReviewPair: () => void;
  onSelectReference: (localReferenceKey: string) => void;
  onHighlightInPdf: (text: string | string[], contextText?: string | null) => void;
  collapsedQuickAccessKey: string | null;
  onCollapsedQuickAccessKeyChange: (key: string | null) => void;
}) {
  const statuses = PAPER_REVIEW_FILTER_OPTIONS.map((option) => option.id);
  const { selectedValues, toggleValue, reset } = usePipelineResultFilter("verification", statuses, "reviewFilter");
  const counts = new Map<string, number>(statuses.map((status) => [status, 0]));
  for (const { outcome } of allOutcomes) {
    const status = formatStatus(outcome);
    counts.set(status, (counts.get(status) ?? 0) + 1);
  }
  const selectedOutcome = allOutcomes.find(({ outcome }) => outcome.id === selectedOutcomeId) ?? null;
  const selectedOutcomeOutsideFilter = selectedOutcome !== null
    && selectedValues.size > 0
    && !selectedValues.has(formatStatus(selectedOutcome.outcome));
  const filteredGroups = groups.map((group) => ({
    ...group,
    outcomes: group.outcomes.filter(({ outcome }) => selectedValues.size === 0 || selectedValues.has(formatStatus(outcome))),
  })).filter((group) => group.outcomes.length > 0);

  return (
    <div className="space-y-3">
      <AnalysisRunPaperReviewFilters
        selectedValues={selectedValues}
        onToggle={toggleValue}
        onReset={reset}
        options={PAPER_REVIEW_FILTER_OPTIONS.map((option) => ({ ...option, value: counts.get(option.id) ?? 0 }))}
      />

      {selectedOutcomeOutsideFilter && selectedOutcome && (
        <section aria-label="Selected pair outside active filters" className="space-y-2 rounded-xl border border-primary/20 bg-primary/5 p-3">
          <p className="m-0 text-xs text-muted-foreground">This selected pair does not match the active status filters, so it remains available here for review.</p>
          <ReviewPairQuickAccess
            run={run}
            entry={selectedOutcome.entry}
            outcome={selectedOutcome.outcome}
            selected
            collapsedQuickAccessKey={collapsedQuickAccessKey}
            onCollapsedQuickAccessKeyChange={onCollapsedQuickAccessKeyChange}
            onSelectOutcome={onSelectOutcome}
            onSelectReference={onSelectReference}
            onHighlightInPdf={onHighlightInPdf}
            onClear={onClearReviewPair}
          />
        </section>
      )}

      {reportLoading && !allOutcomes.length && <ReviewLoadingState label="Loading saved AI results" />}
      {reportError && !allOutcomes.length && <ReviewError title="AI results unavailable" message={reportError} />}
      {!reportLoading && !reportError && !allOutcomes.length && (
        <p className="rounded-lg border border-border bg-card p-4 text-sm text-muted-foreground">
          {run.status === "QUEUED" || run.status === "PROCESSING"
            ? "AI result pairs will appear here as the worker records them."
            : "No AI result pairs were recorded for this Analysis Run."}
        </p>
      )}
      {parsedError && <ReviewError title="Parsed source unavailable" message={parsedError} />}
      {parsedLoading && <ReviewLoadingState label="Loading parsed source details" />}

      {allOutcomes.length > 0 && (
        <section aria-labelledby="review-result-index-heading" className="space-y-3">
          <div className="flex items-center justify-between gap-2">
            <h3 id="review-result-index-heading" className="m-0 text-sm font-semibold">Claim results</h3>
            <Badge variant="outline">{filteredGroups.reduce((count, group) => count + group.outcomes.length, 0)} pairs</Badge>
          </div>
          {filteredGroups.length === 0 ? (
            <p className="rounded-lg border border-border bg-card p-3 text-sm text-muted-foreground">No claim pairs match the selected statuses.</p>
          ) : (
            <div className="space-y-3">
              {filteredGroups.map((group) => {
                const sourceHighlightCandidates = uniqueSearchCandidates(
                  group.claimText,
                  ...group.outcomes.flatMap(({ outcome }) => outcome.citationMarkers),
                );
                const sourceContextText = group.outcomes[0]?.outcome.citationContextText ?? null;
                return (
                  <Card key={group.claimId} size="sm">
                    <CardHeader className="grid-cols-1 gap-2">
                      <div className="flex min-w-0 items-center justify-between gap-2">
                        <p className="m-0 min-w-0 font-mono text-[0.65rem] uppercase tracking-wide text-muted-foreground">Atomic Claim {group.claimId.slice(0, 8)}</p>
                        <Button
                          type="button"
                          variant="secondary"
                          size="sm"
                          aria-label={`Show atomic claim in PDF: ${group.claimText}`}
                          title={sourceHighlightCandidates.length > 0 ? "Highlight the Atomic Claim and citation markers in the PDF" : "No claim or citation text is available to find in the PDF"}
                          disabled={sourceHighlightCandidates.length === 0}
                          onClick={() => onHighlightInPdf(sourceHighlightCandidates, sourceContextText)}
                        >
                          <Highlighter aria-hidden="true" />
                          Show in PDF
                        </Button>
                      </div>
                      <CardTitle className="min-w-0 break-words text-sm leading-relaxed">{group.claimText}</CardTitle>
                    </CardHeader>
                    <CardContent className="space-y-2">
                      {group.outcomes.map(({ entry, outcome }) => (
                        <ReviewPairQuickAccess
                          key={outcome.id}
                          run={run}
                          entry={entry}
                          outcome={outcome}
                          selected={selectedOutcomeId === outcome.id}
                          collapsedQuickAccessKey={collapsedQuickAccessKey}
                          onCollapsedQuickAccessKeyChange={onCollapsedQuickAccessKeyChange}
                          onSelectOutcome={onSelectOutcome}
                          onSelectReference={onSelectReference}
                          onHighlightInPdf={onHighlightInPdf}
                          onClear={onClearReviewPair}
                        />
                      ))}
                    </CardContent>
                  </Card>
                );
              })}
            </div>
          )}
        </section>
      )}

    </div>
  );
}

function SelectedPairQuickAccess({
  run,
  entry,
  outcome,
  onHighlightInPdf,
  onSelectReference,
  onClear,
}: {
  run: AnalysisRun;
  entry: ReferenceEntry;
  outcome: VerificationOutcome;
  onHighlightInPdf: (text: string | string[], contextText?: string | null) => void;
  onSelectReference: (localReferenceKey: string) => void;
  onClear: () => void;
}) {
  const searchText = uniqueSearchCandidates(outcome.claimText, ...outcome.citationMarkers);

  return (
    <section aria-label="Selected pair quick access" className="space-y-3 rounded-xl border border-primary/25 bg-primary/5 p-3">
      <div className="space-y-2">
        <div className="flex flex-wrap items-center justify-between gap-x-2 gap-y-2">
          <p className="m-0 font-mono text-[0.65rem] uppercase tracking-wide text-muted-foreground">Selected pair · {entry.localReferenceKey}</p>
          <div className="flex shrink-0 flex-nowrap items-center gap-1">
            <Button
              type="button"
              variant="secondary"
              size="sm"
              onClick={() => onHighlightInPdf(searchText, outcome.citationContextText)}
            >
              <Search aria-hidden="true" />
              Show in PDF
            </Button>
            <Button type="button" variant="secondary" size="sm" onClick={() => onSelectReference(entry.localReferenceKey)}>
              <ArrowRight aria-hidden="true" />
              View bibliography
            </Button>
            <Button type="button" variant="ghost" size="icon" aria-label="Clear selected pair" title="Clear selected pair" onClick={onClear}>
              <X aria-hidden="true" />
            </Button>
          </div>
        </div>
        <div className="min-w-0 space-y-1">
          <p className="m-0 break-words text-sm font-medium">{entry.title ?? entry.rawText}</p>
          <p className="m-0 text-xs text-muted-foreground">
            {entry.authors.join(", ") || "Authors not recorded"}{entry.year ? ` · ${entry.year}` : ""} · {statusLabel(entry.status)}
          </p>
        </div>
      </div>
      <section aria-labelledby="selected-ai-result-heading" className="space-y-2 border-t border-primary/15 pt-3">
        <h3 id="selected-ai-result-heading" className="m-0 text-sm font-semibold">Evidence for selected pair</h3>
        {outcome.processingStatus === "PENDING" && <p className="m-0 text-xs text-muted-foreground">This pair is still waiting for a saved AI assessment.</p>}
        <ol className="m-0 list-none space-y-2 p-0">
          <ClaimEvidencePassages
            analysisRunId={run.id}
            outcome={outcome}
            indexingStatus={entry.citedPaperAccess?.evidenceIndexing?.status ?? null}
          />
        </ol>
      </section>
    </section>
  );
}

function ReviewPairQuickAccess({
  run,
  entry,
  outcome,
  selected,
  collapsedQuickAccessKey,
  onCollapsedQuickAccessKeyChange,
  onSelectOutcome,
  onSelectReference,
  onHighlightInPdf,
  onClear,
}: {
  run: AnalysisRun;
  entry: ReferenceEntry;
  outcome: VerificationOutcome;
  selected: boolean;
  collapsedQuickAccessKey: string | null;
  onCollapsedQuickAccessKeyChange: (key: string | null) => void;
  onSelectOutcome: (outcomeId: string, localReferenceKey: string) => void;
  onSelectReference: (localReferenceKey: string) => void;
  onHighlightInPdf: (text: string | string[], contextText?: string | null) => void;
  onClear: () => void;
}) {
  const quickAccessKey = `results:${outcome.id}`;
  const triggerId = `review-pair-trigger-${outcome.id}`;

  return (
    <div id={`review-pair-item-${outcome.id}`}>
      <Collapsible
        open={selected && collapsedQuickAccessKey !== quickAccessKey}
        onOpenChange={(open) => onCollapsedQuickAccessKeyChange(open ? null : quickAccessKey)}
        className="group/quick-access rounded-xl border border-border bg-card shadow-sm"
      >
        <CollapsibleTrigger
          id={triggerId}
          aria-pressed={selected}
          aria-label={`Review claim against ${entry.localReferenceKey}: ${entry.title ?? entry.rawText}`}
          onClick={() => { if (!selected) onCollapsedQuickAccessKeyChange(null); onSelectOutcome(outcome.id, entry.localReferenceKey); }}
          className={cn(
            "group flex min-h-12 w-full flex-col items-stretch gap-1 rounded-xl px-3 py-2 text-left focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50",
            selected && "bg-primary/10 hover:bg-primary/15",
          )}
        >
          <span className="flex min-w-0 items-center justify-between gap-2">
            <span className="shrink-0 font-mono text-xs font-medium text-muted-foreground">{entry.localReferenceKey}</span>
            <span className="flex shrink-0 items-center gap-2">
              <ReferenceResolutionBadge status={formatStatus(outcome)} />
              <ChevronDown className="size-4 text-muted-foreground transition-transform group-data-[open]/quick-access:rotate-180 motion-reduce:transition-none" aria-hidden="true" />
            </span>
          </span>
          <span className="block break-words text-xs leading-relaxed font-medium">{entry.title ?? "Untitled bibliography entry"}</span>
          <span className="block break-words text-xs text-muted-foreground">{outcome.citationMarkers.join(", ") || "No citation marker recorded"}</span>
        </CollapsibleTrigger>
        <CollapsibleContent className="h-[var(--collapsible-panel-height)] overflow-hidden transition-[height] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] data-[starting-style]:h-0 data-[ending-style]:h-0 motion-reduce:transition-none">
          <div className="translate-y-0 border-t border-border px-3 py-3 opacity-100 transition-[opacity,translate] duration-200 ease-out group-data-[closed]/quick-access:-translate-y-1 group-data-[closed]/quick-access:opacity-0 motion-reduce:transition-none">
            <SelectedPairQuickAccess
              run={run}
              entry={entry}
              outcome={outcome}
              onHighlightInPdf={onHighlightInPdf}
              onSelectReference={onSelectReference}
              onClear={onClear}
            />
          </div>
        </CollapsibleContent>
      </Collapsible>
    </div>
  );
}

function CitationDetails({
  references,
  parsedDocument,
  parsedLoading,
  parsedError,
  selectedReference,
  referenceResults,
  onClearSelectedReference,
  onSelectReference,
  onSelectOutcome,
  onHighlightInPdf,
  collapsedQuickAccessKey,
  onCollapsedQuickAccessKeyChange,
}: {
  references: ParsedDocument["bibliographyEntries"];
  parsedDocument: ParsedDocument | null;
  parsedLoading: boolean;
  parsedError: string | null;
  selectedReference: ParsedDocument["bibliographyEntries"][number] | null;
  referenceResults: ReferenceEntry[];
  onClearSelectedReference: () => void;
  onSelectReference: (localReferenceKey: string) => void;
  onSelectOutcome: (outcomeId: string, localReferenceKey: string) => void;
  onHighlightInPdf: (text: string | string[], contextText?: string | null) => void;
  collapsedQuickAccessKey: string | null;
  onCollapsedQuickAccessKeyChange: (key: string | null) => void;
}) {
  return (
    <div className="space-y-3">
      {parsedLoading && <ReviewLoadingState label="Loading citations and bibliography" />}
      {parsedError && <ReviewError title="Parsed citations unavailable" message={parsedError} />}
      {parsedDocument && (
        <p className="m-0 text-xs leading-relaxed text-muted-foreground">
          These citation links come from parsed text and inferred claim associations. They identify bibliography entries, but do not assert that the paper’s authors linked each claim this way.
        </p>
      )}
      <section aria-labelledby="bibliography-index-heading" className="space-y-2">
        <div className="flex items-center justify-between gap-2">
          <h3 id="bibliography-index-heading" className="m-0 text-sm font-semibold">Bibliography</h3>
          <Badge variant="outline">{references.length} entries</Badge>
        </div>
        {references.length === 0 && !parsedLoading ? (
          <p className="rounded-lg border border-border bg-card p-3 text-sm text-muted-foreground">Bibliography entries will appear after the source PDF is parsed.</p>
        ) : (
          <ul className="m-0 list-none space-y-2 p-0">
            {references.map((reference) => {
              const referenceKey = reference.localReferenceKey;
              const contexts = parsedDocument?.citationContexts.filter((context) =>
                context.occurrences.some((occurrence) => occurrence.bibliographyReferenceKeys.includes(referenceKey))
                || context.atomicClaims.some((claim) => claim.citationTargets.some((target) => target.bibliographyReferenceKey === referenceKey)),
              ) ?? [];
              const referenceResult = referenceResults.find((entry) => entry.localReferenceKey === referenceKey) ?? null;
              const selected = selectedReference?.localReferenceKey === referenceKey;
              const quickAccessKey = `citations:${referenceKey}`;
              const triggerId = `review-bibliography-trigger-${referenceKey}`;

              return (
                <li key={referenceKey}>
                  <Collapsible
                    open={selected && collapsedQuickAccessKey !== quickAccessKey}
                    onOpenChange={(open) => onCollapsedQuickAccessKeyChange(open ? null : quickAccessKey)}
                    className="group/quick-access rounded-xl border border-border bg-card shadow-sm"
                  >
                    <CollapsibleTrigger
                      id={triggerId}
                      aria-pressed={selected}
                      onClick={() => { if (!selected) onCollapsedQuickAccessKeyChange(null); onSelectReference(referenceKey); }}
                      className={cn(
                        "group flex min-h-12 w-full items-center justify-between gap-3 rounded-xl px-3 py-2 text-left focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50",
                        selected && "bg-primary/10 hover:bg-primary/15",
                      )}
                    >
                      <span className="min-w-0">
                        <span className="block font-mono text-xs text-muted-foreground">{reference.localReferenceKey} · {reference.year ?? "year unknown"}</span>
                        <span className="block text-xs leading-relaxed">{reference.title ?? reference.rawText}</span>
                        {reference.authors.length > 0 && (
                          <span className="block break-words text-xs text-muted-foreground">{reference.authors.join(", ")}</span>
                        )}
                      </span>
                      <ChevronDown className="size-4 shrink-0 text-muted-foreground transition-transform group-data-[open]/quick-access:rotate-180 motion-reduce:transition-none" aria-hidden="true" />
                    </CollapsibleTrigger>
                    <CollapsibleContent className="h-[var(--collapsible-panel-height)] overflow-hidden transition-[height] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] data-[starting-style]:h-0 data-[ending-style]:h-0 motion-reduce:transition-none">
                      <div className="translate-y-0 border-t border-border px-3 py-3 opacity-100 transition-[opacity,translate] duration-200 ease-out group-data-[closed]/quick-access:-translate-y-1 group-data-[closed]/quick-access:opacity-0 motion-reduce:transition-none">
                        <SelectedBibliographyQuickAccess
                          reference={reference}
                          referenceResult={referenceResult}
                          contexts={contexts}
                          onHighlightInPdf={onHighlightInPdf}
                          onClear={onClearSelectedReference}
                          onSelectOutcome={onSelectOutcome}
                        />
                      </div>
                    </CollapsibleContent>
                  </Collapsible>
                </li>
              );
            })}
          </ul>
        )}
      </section>
    </div>
  );
}

function SelectedBibliographyQuickAccess({
  reference,
  referenceResult,
  contexts,
  onHighlightInPdf,
  onClear,
  onSelectOutcome,
}: {
  reference: ParsedDocument["bibliographyEntries"][number];
  referenceResult: ReferenceEntry | null;
  contexts: ParsedDocument["citationContexts"];
  onHighlightInPdf: (text: string | string[], contextText?: string | null) => void;
  onClear: () => void;
  onSelectOutcome: (outcomeId: string, localReferenceKey: string) => void;
}) {
  return (
    <section aria-label="Selected bibliography quick access" className="space-y-3 rounded-xl border border-primary/25 bg-primary/5 p-3">
      <div className="flex min-w-0 items-center justify-between gap-2">
        <p className="m-0 min-w-0 break-words font-mono text-[0.65rem] uppercase tracking-wide text-muted-foreground">Selected bibliography · {reference.localReferenceKey}</p>
        <div className="flex shrink-0 items-center gap-1">
          <Button type="button" variant="secondary" size="sm" onClick={() => onHighlightInPdf(reference.title ?? reference.rawText)}>
            <Search aria-hidden="true" />
            Show in PDF
          </Button>
          <Button type="button" variant="ghost" size="icon" aria-label="Clear selected bibliography" title="Clear selected bibliography" onClick={onClear}>
            <X aria-hidden="true" />
          </Button>
        </div>
      </div>
      <div className="min-w-0 space-y-1">
        <p className="m-0 break-words text-sm font-medium">{reference.title ?? reference.rawText}</p>
        <p className="m-0 text-xs text-muted-foreground">
          {reference.authors.join(", ") || "Authors not recorded"}{reference.year ? ` · ${reference.year}` : ""}{reference.doi ? ` · DOI ${reference.doi}` : ""}
        </p>
        <ReferenceResolutionBadge status={referenceResult?.status ?? reference.resolutionStatus} />
      </div>
      <SelectedCitationContexts
        referenceKey={reference.localReferenceKey}
        contexts={contexts}
        referenceResult={referenceResult}
        onSelectOutcome={onSelectOutcome}
        onHighlightInPdf={onHighlightInPdf}
      />
    </section>
  );
}

function SelectedCitationContexts({
  referenceKey,
  contexts,
  referenceResult,
  onSelectOutcome,
  onHighlightInPdf,
}: {
  referenceKey: string;
  contexts: ParsedDocument["citationContexts"];
  referenceResult: ReferenceEntry | null;
  onSelectOutcome: (outcomeId: string, localReferenceKey: string) => void;
  onHighlightInPdf: (text: string | string[], contextText?: string | null) => void;
}) {
  return (
    <section aria-labelledby="selected-citation-contexts-heading" className="w-full border-t border-primary/15 pt-3">
      <div className="mb-2 flex items-center justify-between gap-2">
        <h3 id="selected-citation-contexts-heading" className="m-0 text-sm font-semibold">Citing contexts</h3>
        <Badge variant="outline">{contexts.length}</Badge>
      </div>
      {contexts.length === 0 ? (
        <p className="m-0 rounded-lg border border-border bg-card p-3 text-sm text-muted-foreground">No parsed citation context currently points to this bibliography entry.</p>
      ) : (
        <div className="space-y-2 md:max-h-64 md:overflow-y-auto md:overscroll-contain md:pr-1">
          {contexts.map((context) => (
            <article key={context.id} className="space-y-2 rounded-lg border border-border bg-card p-3">
              <p className="m-0 text-xs leading-relaxed">{context.text}</p>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <p className="m-0 font-mono text-[0.65rem] text-muted-foreground">Source offsets {context.startOffset}–{context.endOffset}</p>
                <Button type="button" variant="link" size="sm" className="h-auto whitespace-normal p-0 text-left" onClick={() => onHighlightInPdf(context.text, context.text)}>
                  <Search aria-hidden="true" />
                  Find in PDF
                </Button>
              </div>
              {context.atomicClaims.flatMap((claim) => claim.citationTargets
                .filter((target) => target.bibliographyReferenceKey === referenceKey)
                .map((target, targetIndex) => {
                  const relatedOutcomes = referenceResult?.verificationOutcomes.filter((outcome) => outcome.atomicClaimId === claim.id) ?? [];
                  return (
                    <div key={`${claim.id}:${target.id}:${targetIndex}`} className="rounded-md bg-muted/40 p-2">
                      <p className="m-0 text-xs"><strong>Parsed Atomic Claim:</strong> {claim.text}</p>
                      <p className="m-1 text-[0.65rem] text-muted-foreground">{target.markerText} · inferred provisional association</p>
                      {relatedOutcomes.length > 0 ? relatedOutcomes.map((outcome) => (
                        <Button key={outcome.id} type="button" variant="link" size="sm" className="h-auto whitespace-normal p-0 text-left" onClick={() => onSelectOutcome(outcome.id, referenceKey)}>
                          <ArrowLeft aria-hidden="true" />
                          Review AI result: {statusLabel(formatStatus(outcome))}
                        </Button>
                      )) : <p className="m-0 text-xs text-muted-foreground">No saved AI pair is linked to this parsed claim and reference.</p>}
                    </div>
                  );
                }))}
            </article>
          ))}
        </div>
      )}
    </section>
  );
}

function ReviewLoadingState({ label }: { label: string }) {
  return (
    <div role="status" aria-label={label} className="space-y-2 rounded-lg border border-border bg-card p-3">
      <span className="sr-only">{label}…</span>
      <Skeleton className="h-4 w-2/3" />
      <Skeleton className="h-12 w-full" />
    </div>
  );
}

function ReviewError({ title, message }: { title: string; message: string }) {
  return <Alert variant="destructive"><AlertTitle>{title}</AlertTitle><AlertDescription>{message}</AlertDescription></Alert>;
}

function uniqueSearchCandidates(...candidates: Array<string | null | undefined>) {
  return [...new Set(candidates.map((candidate) => candidate?.trim()).filter((candidate): candidate is string => Boolean(candidate)))];
}
