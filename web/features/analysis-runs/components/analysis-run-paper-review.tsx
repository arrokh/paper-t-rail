"use client";

import { useMemo, useState } from "react";
import { ArrowLeft, ArrowRight, FileText, Highlighter, Link2, Search, X } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { AnalysisRunPaperReviewFilters, PAPER_REVIEW_FILTER_OPTIONS } from "@/features/analysis-runs/components/analysis-run-paper-review-filters";
import { SourceDocumentPdfViewer } from "@/features/analysis-runs/components/source-document-pdf-viewer";
import { usePipelineResultFilter } from "@/features/analysis-runs/hooks/use-pipeline-result-filter";
import { scrollToPaperReviewCard } from "@/features/analysis-runs/scroll-to-paper-review-card";
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
  onSelectDetailSection: (section: "results" | "citations") => void;
}) {
  const [mobilePane, setMobilePane] = useState<"paper" | "details">("paper");
  const [pdfHighlightOverride, setPdfHighlightOverride] = useState<{ selectionKey: string; text: string[]; contextText: string | null } | null>(null);
  const entries = report?.referenceResolution.entries ?? EMPTY_REFERENCE_ENTRIES;
  const outcomeGroups = useMemo(() => buildOutcomeGroups(entries), [entries]);
  const references = useMemo(() => sourceReferences(parsedDocument, report), [parsedDocument, report]);
  const allOutcomes = useMemo(() => entries.flatMap((entry) => entry.verificationOutcomes.map((outcome) => ({ entry, outcome }))), [entries]);
  const selectedPair = allOutcomes.find(({ outcome }) => outcome.id === selectedOutcomeId) ?? null;
  const selectedReference = references.find((reference) => reference.localReferenceKey === selectedReferenceKey) ?? null;
  const selectedReferenceResult = entries.find((entry) => entry.localReferenceKey === selectedReferenceKey) ?? null;
  const selectionKey = `${selectedDetailSection}:${selectedOutcomeId ?? ""}:${selectedReferenceKey ?? ""}`;
  const selectedPdfSearchText = useMemo(() => {
    if (selectedDetailSection === "citations") {
      return selectedReference ? uniqueSearchCandidates(selectedReference.title, selectedReference.rawText) : [];
    }
    if (!selectedPair) return [];

    return uniqueSearchCandidates(selectedPair.outcome.claimText, ...selectedPair.outcome.citationMarkers);
  }, [selectedDetailSection, selectedPair, selectedReference]);
  const pdfHighlightText = pdfHighlightOverride?.selectionKey === selectionKey
    ? pdfHighlightOverride.text
    : selectedPdfSearchText.length > 0 ? selectedPdfSearchText : null;
  const pdfHighlightContextText = pdfHighlightOverride?.selectionKey === selectionKey
    ? pdfHighlightOverride.contextText
    : selectedDetailSection === "results" ? selectedPair?.outcome.citationContextText ?? null : null;
  const citationContexts = useMemo(() => {
    if (!parsedDocument || !selectedReferenceKey) return [];
    return parsedDocument.citationContexts.filter((context) =>
      context.occurrences.some((occurrence) => occurrence.bibliographyReferenceKeys.includes(selectedReferenceKey))
      || context.atomicClaims.some((claim) => claim.citationTargets.some((target) => target.bibliographyReferenceKey === selectedReferenceKey)),
    );
  }, [parsedDocument, selectedReferenceKey]);

  function chooseOutcome(outcomeId: string, referenceKey: string) {
    setMobilePane("details");
    setPdfHighlightOverride(null);
    onSelectOutcome(outcomeId, referenceKey);
  }

  function chooseReference(referenceKey: string, highlightText?: string) {
    setMobilePane("details");
    if (highlightText) {
      const reference = references.find((candidate) => candidate.localReferenceKey === referenceKey);
      setPdfHighlightOverride({
        selectionKey: `${selectedDetailSection}:${selectedOutcomeId ?? ""}:${referenceKey}`,
        text: uniqueSearchCandidates(highlightText, reference?.rawText),
        contextText: null,
      });
    }
    onSelectReference(referenceKey);
  }

  function highlightInPdf(text: string | string[], contextText: string | null = null) {
    setPdfHighlightOverride({ selectionKey, text: uniqueSearchCandidates(...(Array.isArray(text) ? text : [text])), contextText });
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
                />
              </div>
              <aside className={cn("min-w-0", mobilePane === "details" ? "block" : "hidden", "md:flex md:h-full md:min-h-0 md:flex-col")} aria-label="Paper Review details">
                <div className="mb-3 grid grid-cols-2 gap-1 rounded-xl border border-border bg-muted/60 p-1" role="group" aria-label="Review detail type">
                  <Button
                    type="button"
                    variant={selectedDetailSection === "results" ? "default" : "ghost"}
                    aria-pressed={selectedDetailSection === "results"}
                    onClick={() => onSelectDetailSection("results")}
                    className={cn("h-auto min-h-9 min-w-0 whitespace-normal px-2 py-2 text-center text-xs leading-tight font-medium sm:text-sm", selectedDetailSection === "results" && "font-semibold shadow-sm")}
                  >
                    AI results
                  </Button>
                  <Button
                    type="button"
                    variant={selectedDetailSection === "citations" ? "default" : "ghost"}
                    aria-pressed={selectedDetailSection === "citations"}
                    onClick={() => onSelectDetailSection("citations")}
                    className={cn("h-auto min-h-9 min-w-0 whitespace-normal px-2 py-2 text-center text-xs leading-tight font-medium sm:text-sm", selectedDetailSection === "citations" && "font-semibold shadow-sm")}
                  >
                    Citations & bibliography
                  </Button>
                </div>
                {selectedDetailSection === "results" && selectedPair && (
                  <section aria-label="Selected pair quick access" className="mb-3 shrink-0 space-y-3 rounded-xl border border-primary/25 bg-primary/5 p-3 md:max-h-[45%] md:overflow-y-auto">
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <div className="min-w-0 flex-1 space-y-1">
                        <p className="m-0 font-mono text-[0.65rem] uppercase tracking-wide text-muted-foreground">Selected pair · {selectedPair.entry.localReferenceKey}</p>
                        <p className="m-0 break-words text-sm font-medium">{selectedPair.entry.title ?? selectedPair.entry.rawText}</p>
                        <p className="m-0 text-xs text-muted-foreground">
                          {selectedPair.entry.authors.join(", ") || "Authors not recorded"}{selectedPair.entry.year ? ` · ${selectedPair.entry.year}` : ""} · {statusLabel(selectedPair.entry.status)}
                        </p>
                      </div>
                      <div className="flex shrink-0 items-center gap-1">
                        <Button
                          type="button"
                          variant="secondary"
                          size="sm"
                          onClick={() => highlightInPdf(selectedPdfSearchText, selectedPair.outcome.citationContextText)}
                        >
                          <Search aria-hidden="true" />
                          Show in PDF
                        </Button>
                        <Button type="button" variant="secondary" size="sm" onClick={() => chooseReference(selectedPair.entry.localReferenceKey, selectedPair.entry.title ?? selectedPair.entry.rawText)}>
                          <ArrowRight aria-hidden="true" />
                          View bibliography
                        </Button>
                        <Button type="button" variant="ghost" size="icon" aria-label="Clear selected pair" title="Clear selected pair" onClick={onClearReviewPair}>
                          <X aria-hidden="true" />
                        </Button>
                      </div>
                    </div>
                    <section aria-labelledby="selected-ai-result-heading" className="space-y-2 border-t border-primary/15 pt-3">
                      <h3 id="selected-ai-result-heading" className="m-0 text-sm font-semibold">Evidence for selected pair</h3>
                      {selectedPair.outcome.processingStatus === "PENDING" && <p className="m-0 text-xs text-muted-foreground">This pair is still waiting for a saved AI assessment.</p>}
                      <ol className="m-0 list-none space-y-2 p-0">
                        <ClaimEvidencePassages
                          analysisRunId={run.id}
                          outcome={selectedPair.outcome}
                          indexingStatus={selectedPair.entry.citedPaperAccess?.evidenceIndexing?.status ?? null}
                        />
                      </ol>
                    </section>
                  </section>
                )}
                {selectedDetailSection === "citations" && selectedReference && (
                  <section aria-label="Selected bibliography quick access" className="mb-3 shrink-0 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-primary/25 bg-primary/5 p-3 md:max-h-[45%] md:overflow-y-auto">
                    <div className="min-w-0 flex-1 space-y-1">
                      <p className="m-0 font-mono text-[0.65rem] uppercase tracking-wide text-muted-foreground">Selected bibliography · {selectedReference.localReferenceKey}</p>
                      <p className="m-0 break-words text-sm font-medium">{selectedReference.title ?? selectedReference.rawText}</p>
                      <p className="m-0 text-xs text-muted-foreground">
                        {selectedReference.authors.join(", ") || "Authors not recorded"}{selectedReference.year ? ` · ${selectedReference.year}` : ""}{selectedReference.doi ? ` · DOI ${selectedReference.doi}` : ""}
                      </p>
                      <ReferenceResolutionBadge status={selectedReferenceResult?.status ?? selectedReference.resolutionStatus} />
                    </div>
                    <div className="flex shrink-0 items-center gap-1">
                      <Button type="button" variant="secondary" size="sm" onClick={() => highlightInPdf([selectedReference.title ?? "", selectedReference.rawText, selectedReference.authors.join(" ")])}>
                        <Search aria-hidden="true" />
                        Show in PDF
                      </Button>
                      <Button type="button" variant="ghost" size="icon" aria-label="Clear selected bibliography" title="Clear selected bibliography" onClick={onClearSelectedReference}>
                        <X aria-hidden="true" />
                      </Button>
                    </div>
                    <SelectedCitationContexts
                      referenceKey={selectedReference.localReferenceKey}
                      contexts={citationContexts}
                      referenceResult={selectedReferenceResult}
                      onSelectOutcome={chooseOutcome}
                      onHighlightInPdf={highlightInPdf}
                    />
                  </section>
                )}
                <div className="min-h-96 space-y-3 md:min-h-0 md:flex-1 md:overflow-y-auto md:p-2">
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
                      onHighlightInPdf={highlightInPdf}
                    />
                  ) : (
                    <CitationDetails
                      references={references}
                      parsedDocument={parsedDocument}
                      parsedLoading={parsedLoading}
                      parsedError={parsedError}
                      selectedReference={selectedReference}
                      onSelectReference={chooseReference}
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
  onHighlightInPdf,
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
  onHighlightInPdf: (text: string | string[], contextText?: string | null) => void;
}) {
  const statuses = PAPER_REVIEW_FILTER_OPTIONS.map((option) => option.id);
  const { selectedValues, toggleValue, reset } = usePipelineResultFilter("verification", statuses, "reviewFilter");
  const counts = new Map<string, number>(statuses.map((status) => [status, 0]));
  for (const { outcome } of allOutcomes) {
    const status = formatStatus(outcome);
    counts.set(status, (counts.get(status) ?? 0) + 1);
  }
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
                    <CardHeader>
                      <p className="m-0 font-mono text-[0.65rem] uppercase tracking-wide text-muted-foreground">Atomic Claim {group.claimId.slice(0, 8)}</p>
                      <CardAction>
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
                      </CardAction>
                      <CardTitle className="text-sm leading-relaxed">{group.claimText}</CardTitle>
                    </CardHeader>
                    <CardContent className="space-y-2">
                      {group.outcomes.map(({ entry, outcome }) => (
                        <Button
                          key={outcome.id}
                          type="button"
                          variant="outline"
                          aria-pressed={selectedOutcomeId === outcome.id}
                          aria-label={`Review claim against ${entry.localReferenceKey}: ${entry.title ?? entry.rawText}`}
                          onClick={() => onSelectOutcome(outcome.id, entry.localReferenceKey)}
                          className={cn(
                            "h-auto min-h-12 w-full justify-between gap-2 whitespace-normal px-3 py-2 text-left",
                            selectedOutcomeId === outcome.id && "border-primary/70 bg-primary/10 hover:bg-primary/15",
                          )}
                        >
                          <span className="min-w-0">
                            <span className="block text-xs font-medium">{entry.localReferenceKey} · {entry.title ?? "Untitled bibliography entry"}</span>
                            <span className="block truncate text-xs text-muted-foreground">{outcome.citationMarkers.join(", ") || "No citation marker recorded"}</span>
                          </span>
                          <ReferenceResolutionBadge status={formatStatus(outcome)} />
                        </Button>
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

function CitationDetails({
  references,
  parsedDocument,
  parsedLoading,
  parsedError,
  selectedReference,
  onSelectReference,
}: {
  references: ParsedDocument["bibliographyEntries"];
  parsedDocument: ParsedDocument | null;
  parsedLoading: boolean;
  parsedError: string | null;
  selectedReference: ParsedDocument["bibliographyEntries"][number] | null;
  onSelectReference: (localReferenceKey: string, highlightText?: string) => void;
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
            {references.map((reference) => (
              <li key={reference.localReferenceKey}>
                <Button
                  type="button"
                  variant="outline"
                  aria-pressed={selectedReference?.localReferenceKey === reference.localReferenceKey}
                  onClick={() => onSelectReference(reference.localReferenceKey, reference.title ?? reference.rawText)}
                  className={cn(
                    "h-auto w-full justify-start whitespace-normal px-3 py-2 text-left",
                    selectedReference?.localReferenceKey === reference.localReferenceKey && "border-primary/70 bg-primary/10 hover:bg-primary/15",
                  )}
                >
                  <span className="min-w-0">
                    <span className="block font-mono text-xs text-muted-foreground">{reference.localReferenceKey} · {reference.year ?? "year unknown"}</span>
                    <span className="block text-xs leading-relaxed">{reference.title ?? reference.rawText}</span>
                    {reference.authors.length > 0 && (
                      <span className="block break-words text-xs text-muted-foreground">{reference.authors.join(", ")}</span>
                    )}
                  </span>
                </Button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
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
  onHighlightInPdf: (text: string | string[]) => void;
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
                <Button type="button" variant="link" size="sm" className="h-auto whitespace-normal p-0 text-left" onClick={() => onHighlightInPdf(context.text)}>
                  <Search aria-hidden="true" />
                  Find in PDF
                </Button>
              </div>
              {context.atomicClaims.flatMap((claim) => claim.citationTargets
                .filter((target) => target.bibliographyReferenceKey === referenceKey)
                .map((target) => {
                  const relatedOutcomes = referenceResult?.verificationOutcomes.filter((outcome) => outcome.atomicClaimId === claim.id) ?? [];
                  return (
                    <div key={target.id} className="rounded-md bg-muted/40 p-2">
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
