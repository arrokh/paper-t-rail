"use client";

import { useMemo, useState } from "react";
import { ArrowUpRight, FileText, Link2 } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { AnalysisRunPaperReviewFilters, PAPER_REVIEW_FILTER_OPTIONS } from "@/features/analysis-runs/components/analysis-run-paper-review-filters";
import { SourceDocumentPdfViewer } from "@/features/analysis-runs/components/source-document-pdf-viewer";
import { usePipelineResultFilter } from "@/features/analysis-runs/hooks/use-pipeline-result-filter";
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
  onSelectDetailSection: (section: "results" | "citations") => void;
}) {
  const [mobilePane, setMobilePane] = useState<"paper" | "details">("paper");
  const entries = report?.referenceResolution.entries ?? EMPTY_REFERENCE_ENTRIES;
  const outcomeGroups = useMemo(() => buildOutcomeGroups(entries), [entries]);
  const references = useMemo(() => sourceReferences(parsedDocument, report), [parsedDocument, report]);
  const allOutcomes = useMemo(() => entries.flatMap((entry) => entry.verificationOutcomes.map((outcome) => ({ entry, outcome }))), [entries]);
  const selectedPair = allOutcomes.find(({ outcome }) => outcome.id === selectedOutcomeId) ?? null;
  const selectedReference = references.find((reference) => reference.localReferenceKey === selectedReferenceKey) ?? null;
  const citationContexts = useMemo(() => {
    if (!parsedDocument || !selectedReferenceKey) return [];
    return parsedDocument.citationContexts.filter((context) =>
      context.occurrences.some((occurrence) => occurrence.bibliographyReferenceKeys.includes(selectedReferenceKey))
      || context.atomicClaims.some((claim) => claim.citationTargets.some((target) => target.bibliographyReferenceKey === selectedReferenceKey)),
    );
  }, [parsedDocument, selectedReferenceKey]);

  function chooseOutcome(outcomeId: string, referenceKey: string) {
    setMobilePane("details");
    onSelectOutcome(outcomeId, referenceKey);
  }

  function chooseReference(referenceKey: string) {
    setMobilePane("details");
    onSelectReference(referenceKey);
  }

  return (
    <section aria-labelledby="paper-review-heading" className="space-y-4">
      <header className="space-y-1">
        <h2 id="paper-review-heading" className="m-0 text-lg font-semibold">Paper Review</h2>
        <p className="m-0 text-sm text-muted-foreground">Read the uploaded paper alongside parsed citations, bibliography links, and recorded AI judgements.</p>
      </header>

      <div className="flex gap-2 md:hidden" role="group" aria-label="Choose Paper Review panel">
        <Button type="button" variant={mobilePane === "paper" ? "secondary" : "outline"} aria-pressed={mobilePane === "paper"} onClick={() => setMobilePane("paper")} className="flex-1">
          <FileText aria-hidden="true" /> Paper
        </Button>
        <Button type="button" variant={mobilePane === "details" ? "secondary" : "outline"} aria-pressed={mobilePane === "details"} onClick={() => setMobilePane("details")} className="flex-1">
          <Link2 aria-hidden="true" /> Details
        </Button>
      </div>

      <div className="grid min-w-0 gap-4 md:grid-cols-[minmax(0,1.35fr)_minmax(19rem,0.9fr)] md:items-start">
        <div className={cn("min-w-0", mobilePane === "paper" ? "block" : "hidden", "md:block")}>
          <SourceDocumentPdfViewer analysisRunId={run.id} filename={run.filename} />
        </div>
        <aside className={cn("min-w-0", mobilePane === "details" ? "block" : "hidden", "md:block")} aria-label="Paper Review details">
          <div className="mb-3 grid grid-cols-2 gap-2" role="group" aria-label="Review detail type">
            <Button type="button" variant={selectedDetailSection === "results" ? "secondary" : "outline"} aria-pressed={selectedDetailSection === "results"} onClick={() => onSelectDetailSection("results")}>AI results</Button>
            <Button type="button" variant={selectedDetailSection === "citations" ? "secondary" : "outline"} aria-pressed={selectedDetailSection === "citations"} onClick={() => onSelectDetailSection("citations")}>Citations & bibliography</Button>
          </div>
          <div className="min-h-96 space-y-3 md:max-h-[calc(100vh-16rem)] md:overflow-y-auto md:pr-1">
            {selectedDetailSection === "results" ? (
              <ResultsDetails
                run={run}
                groups={outcomeGroups}
                allOutcomes={allOutcomes}
                selectedPair={selectedPair}
                selectedOutcomeId={selectedOutcomeId}
                parsedLoading={parsedLoading}
                reportLoading={reportLoading}
                parsedError={parsedError}
                reportError={reportError}
                onSelectOutcome={chooseOutcome}
                onSelectReference={chooseReference}
              />
            ) : (
              <CitationDetails
                references={references}
                parsedDocument={parsedDocument}
                parsedLoading={parsedLoading}
                parsedError={parsedError}
                selectedReference={selectedReference}
                citationContexts={citationContexts}
                report={report}
                onSelectReference={onSelectReference}
                onSelectOutcome={chooseOutcome}
              />
            )}
          </div>
        </aside>
      </div>
    </section>
  );
}

function ResultsDetails({
  run,
  groups,
  allOutcomes,
  selectedPair,
  selectedOutcomeId,
  parsedLoading,
  reportLoading,
  parsedError,
  reportError,
  onSelectOutcome,
  onSelectReference,
}: {
  run: AnalysisRun;
  groups: OutcomeGroup[];
  allOutcomes: Array<{ entry: ReferenceEntry; outcome: VerificationOutcome }>;
  selectedPair: { entry: ReferenceEntry; outcome: VerificationOutcome } | null;
  selectedOutcomeId: string | null;
  parsedLoading: boolean;
  reportLoading: boolean;
  parsedError: string | null;
  reportError: string | null;
  onSelectOutcome: (outcomeId: string, localReferenceKey: string) => void;
  onSelectReference: (localReferenceKey: string) => void;
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
              {filteredGroups.map((group) => (
                <Card key={group.claimId} size="sm">
                  <CardHeader>
                    <p className="m-0 font-mono text-[0.65rem] uppercase tracking-wide text-muted-foreground">Atomic Claim {group.claimId.slice(0, 8)}</p>
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
              ))}
            </div>
          )}
        </section>
      )}

      {selectedPair && (
        <section aria-labelledby="selected-ai-result-heading" className="space-y-2 border-t border-border pt-3">
          <div className="flex items-center justify-between gap-2">
            <h3 id="selected-ai-result-heading" className="m-0 text-sm font-semibold">Selected pair</h3>
            <Button type="button" variant="link" size="sm" onClick={() => onSelectReference(selectedPair.entry.localReferenceKey)}>
              View {selectedPair.entry.localReferenceKey} bibliography
              <ArrowUpRight aria-hidden="true" />
            </Button>
          </div>
          <Card size="sm">
            <CardContent>
              <div className="space-y-2 text-sm">
                <p className="m-0 font-medium">{selectedPair.entry.title ?? selectedPair.entry.rawText}</p>
                <p className="m-0 text-xs text-muted-foreground">
                  {selectedPair.entry.authors.join(", ") || "Authors not recorded"}{selectedPair.entry.year ? ` · ${selectedPair.entry.year}` : ""}
                </p>
                <ReferenceResolutionBadge status={selectedPair.entry.status} />
              </div>
            </CardContent>
          </Card>
          {selectedPair.outcome.processingStatus === "PENDING" && <p className="m-0 text-xs text-muted-foreground">This pair is still waiting for a saved AI assessment.</p>}
          <ol className="m-0 list-none space-y-2 p-0">
            <ClaimEvidencePassages
              analysisRunId={run.id}
              outcome={selectedPair.outcome}
              indexingStatus={selectedPair.entry.citedPaperAccess?.evidenceIndexing?.status ?? null}
            />
          </ol>
        </section>
      )}
      {!selectedPair && <p className="m-0 text-xs text-muted-foreground">Select a claim–reference pair to inspect its AI result, evidence passages, and any separate human review.</p>}
    </div>
  );
}

function CitationDetails({
  references,
  parsedDocument,
  parsedLoading,
  parsedError,
  selectedReference,
  citationContexts,
  report,
  onSelectReference,
  onSelectOutcome,
}: {
  references: ParsedDocument["bibliographyEntries"];
  parsedDocument: ParsedDocument | null;
  parsedLoading: boolean;
  parsedError: string | null;
  selectedReference: ParsedDocument["bibliographyEntries"][number] | null;
  citationContexts: ParsedDocument["citationContexts"];
  report: ReferenceResolutionReportResponse | null;
  onSelectReference: (localReferenceKey: string) => void;
  onSelectOutcome: (outcomeId: string, localReferenceKey: string) => void;
}) {
  const entry = selectedReference
    ? report?.referenceResolution.entries.find((candidate) => candidate.localReferenceKey === selectedReference.localReferenceKey) ?? null
    : null;

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
                  onClick={() => onSelectReference(reference.localReferenceKey)}
                  className={cn(
                    "h-auto w-full justify-start whitespace-normal px-3 py-2 text-left",
                    selectedReference?.localReferenceKey === reference.localReferenceKey && "border-primary/70 bg-primary/10 hover:bg-primary/15",
                  )}
                >
                  <span className="min-w-0">
                    <span className="block font-mono text-xs text-muted-foreground">{reference.localReferenceKey} · {reference.year ?? "year unknown"}</span>
                    <span className="block text-xs leading-relaxed">{reference.title ?? reference.rawText}</span>
                  </span>
                </Button>
              </li>
            ))}
          </ul>
        )}
      </section>

      {selectedReference && (
        <section aria-labelledby="selected-bibliography-heading" className="space-y-3 border-t border-border pt-3">
          <Card size="sm">
            <CardHeader>
              <p className="m-0 font-mono text-[0.65rem] uppercase tracking-wide text-muted-foreground">{selectedReference.localReferenceKey} · original parsed entry</p>
              <CardTitle id="selected-bibliography-heading" className="text-sm leading-relaxed">Selected bibliography entry</CardTitle>
              <p className="m-0 text-xs leading-relaxed">{selectedReference.title ?? selectedReference.rawText}</p>
            </CardHeader>
            <CardContent className="space-y-2 text-xs">
              <p className="m-0">{selectedReference.rawText}</p>
              <p className="m-0 text-muted-foreground">{selectedReference.authors.join(", ") || "Authors not recorded"}{selectedReference.year ? ` · ${selectedReference.year}` : ""}{selectedReference.doi ? ` · DOI ${selectedReference.doi}` : ""}</p>
              {entry && <ReferenceResolutionBadge status={entry.status} />}
            </CardContent>
          </Card>

          <section aria-labelledby="citation-occurrences-heading" className="space-y-2">
            <div className="flex items-center justify-between gap-2">
              <h3 id="citation-occurrences-heading" className="m-0 text-sm font-semibold">Citing contexts</h3>
              <Badge variant="outline">{citationContexts.length}</Badge>
            </div>
            {citationContexts.length === 0 ? (
              <p className="rounded-lg border border-border bg-card p-3 text-sm text-muted-foreground">No parsed citation context currently points to this bibliography entry.</p>
            ) : citationContexts.map((context) => (
              <Card key={context.id} size="sm">
                <CardContent className="space-y-2">
                  <p className="m-0 text-xs leading-relaxed">{context.text}</p>
                  <p className="m-0 font-mono text-[0.65rem] text-muted-foreground">Source offsets {context.startOffset}–{context.endOffset}</p>
                  {context.atomicClaims.flatMap((claim) => claim.citationTargets
                    .filter((target) => target.bibliographyReferenceKey === selectedReference.localReferenceKey)
                    .map((target) => {
                      const relatedOutcomes = entry?.verificationOutcomes.filter((outcome) => outcome.atomicClaimId === claim.id) ?? [];
                      return (
                        <div key={target.id} className="rounded-md bg-muted/40 p-2">
                          <p className="m-0 text-xs"><strong>Parsed Atomic Claim:</strong> {claim.text}</p>
                          <p className="m-1 text-[0.65rem] text-muted-foreground">{target.markerText} · inferred provisional association</p>
                          {relatedOutcomes.length > 0 ? relatedOutcomes.map((outcome) => (
                            <Button key={outcome.id} type="button" variant="link" size="sm" className="h-auto whitespace-normal p-0 text-left" onClick={() => onSelectOutcome(outcome.id, selectedReference.localReferenceKey)}>
                              Review AI result: {statusLabel(formatStatus(outcome))}
                              <ArrowUpRight aria-hidden="true" />
                            </Button>
                          )) : <p className="m-0 text-xs text-muted-foreground">No saved AI pair is linked to this parsed claim and reference.</p>}
                        </div>
                      );
                    }))}
                </CardContent>
              </Card>
            ))}
          </section>
        </section>
      )}
    </div>
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
