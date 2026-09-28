import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import type { ReferenceResolutionReportResponse } from "@/features/analysis-runs/types";
import { HumanReviewPanel } from "@/features/reference-resolution/components/human-review-panel";

type VerificationOutcome = ReferenceResolutionReportResponse["referenceResolution"]["entries"][number]["verificationOutcomes"][number];
type IndexingStatus = NonNullable<ReferenceResolutionReportResponse["referenceResolution"]["entries"][number]["citedPaperAccess"]>["evidenceIndexing"] extends infer Indexing
  ? Indexing extends { status: infer Status } ? Status : never
  : never;

function statusLabel(outcome: VerificationOutcome): string {
  if (outcome.processingStatus === "INCOMPLETE") return "Machine result unavailable · incomplete pair";
  if (outcome.processingStatus === "PENDING") return "Machine result pending";
  return `Machine result: ${outcome.finalStatus?.replaceAll("_", " ").toLowerCase() ?? "missing domain status"}`;
}

function failureDescription(reason: string): string {
  if (reason === "SYSTEM_ONE_CONTEXT_LIMIT_EXCEEDED") {
    return "The complete Laya request exceeded the 1,024-token context limit. The evidence was not truncated, and no complete judgement set was stored for this pair.";
  }
  if (reason === "SYSTEM_ONE_REQUEST_REJECTED") {
    return "System One rejected the request before producing a judgement. No fallback provider was used.";
  }
  return reason.replaceAll("_", " ").toLowerCase();
}

function noJudgementMessage(reason: string | null): string {
  if (reason === "SYSTEM_ONE_CONTEXT_LIMIT_EXCEEDED") {
    return "No complete System One judgement set was stored because the request exceeded Laya's 1,024-token context limit; evidence was not truncated.";
  }
  if (reason === "SYSTEM_ONE_REQUEST_REJECTED") {
    return "No System One judgement was recorded because the provider rejected the request.";
  }
  return "No System One Evidence Judgement was recorded for this Claim–Reference pair.";
}

export function ClaimEvidencePassages({
  analysisRunId,
  outcome,
  indexingStatus,
}: {
  analysisRunId: string;
  outcome: VerificationOutcome;
  indexingStatus: IndexingStatus | null;
}) {
  const judgedPassages = outcome.evidencePassages.flatMap((passage) =>
    passage.evidenceJudgement ? [{ passage, judgement: passage.evidenceJudgement }] : [],
  );

  return (
    <li className="space-y-3 rounded-lg border border-border bg-card p-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-mono text-xs text-muted-foreground">Atomic Claim {outcome.atomicClaimId.slice(0, 8)}</span>
        <Badge variant="outline" className="capitalize">{statusLabel(outcome)}</Badge>
        <Badge variant="secondary">{outcome.verificationScope.replaceAll("_", " ").toLowerCase()} scope</Badge>
        {outcome.evidenceConflict && <Badge variant="destructive">Comparable conflicting evidence</Badge>}
      </div>
      {outcome.evidenceConflict && (
        <Alert>
          <AlertTitle>Support and contradiction are comparably strong</AlertTitle>
          <AlertDescription>Neither side clearly outweighs the other. The final status is insufficient evidence; both sides remain visible below.</AlertDescription>
        </Alert>
      )}
      <p className="m-0 break-words text-sm leading-relaxed text-foreground"><strong>Atomic Claim:</strong> {outcome.claimText}</p>
      <dl className="grid gap-3 rounded-md bg-muted/30 p-3 text-xs sm:grid-cols-2">
        <div className="min-w-0 space-y-1 sm:col-span-2">
          <dt className="font-mono uppercase text-muted-foreground">Source Citation Context</dt>
          <dd className="m-0 break-words leading-relaxed">{outcome.citationContextText}</dd>
        </div>
        <div className="space-y-1">
          <dt className="font-mono uppercase text-muted-foreground">Citation Marker(s)</dt>
          <dd className="m-0 break-words">{outcome.citationMarkers.length > 0 ? outcome.citationMarkers.join(", ") : "No marker recorded"}</dd>
        </div>
        <div className="space-y-1">
          <dt className="font-mono uppercase text-muted-foreground">Claim source span</dt>
          <dd className="m-0 font-mono">{outcome.claimSourceStartOffset}–{outcome.claimSourceEndOffset}</dd>
        </div>
        <div className="space-y-1 sm:col-span-2">
          <dt className="font-mono uppercase text-muted-foreground">Claim-to-reference association</dt>
          <dd className="m-0">{outcome.associationKind.replaceAll("_", " ").toLowerCase()} — inferred, not author-confirmed</dd>
        </div>
      </dl>
      {outcome.terminalReason && <p className="m-0 text-xs text-muted-foreground">Terminal reason: {outcome.terminalReason.replaceAll("_", " ").toLowerCase()}</p>}
      {outcome.processingFailureReason && (
        <Alert variant="destructive">
          <AlertTitle>Verification pair incomplete</AlertTitle>
          <AlertDescription>{failureDescription(outcome.processingFailureReason)}</AlertDescription>
        </Alert>
      )}
      <section aria-labelledby={`system-one-results-${outcome.id}`} className="space-y-2 border-t border-border pt-3">
        <h4 id={`system-one-results-${outcome.id}`} className="font-mono text-xs tracking-wide text-muted-foreground uppercase">System One results</h4>
        {judgedPassages.length > 0 ? (
          <ul className="space-y-2">
            {judgedPassages.map(({ passage, judgement }) => (
              <li key={passage.id} className="rounded-md border border-border/80 bg-muted/10 p-3">
                <p className="m-0 text-xs font-medium">Evidence Passage · fused rank {passage.fusedRank}</p>
                <div className="my-2 flex flex-wrap gap-2">
                  <Badge variant="outline">{judgement.judgement.replaceAll("_", " ").toLowerCase()}</Badge>
                  <Badge variant="secondary">role: {judgement.evidenceRole.replaceAll("_", " ").toLowerCase()}</Badge>
                </div>
                <p className="m-0 text-xs text-muted-foreground">Provider {judgement.providerId} · model {judgement.modelId ?? "not recorded"} · version {judgement.providerVersion}</p>
                <dl className="mt-2 grid grid-cols-2 gap-2 text-xs">
                  <div>
                    <dt className="font-mono text-muted-foreground">Provisional rubric strength</dt>
                    <dd className="m-0 font-mono">{judgement.calibratedStrength.toFixed(3)}</dd>
                  </div>
                  <div>
                    <dt className="font-mono text-muted-foreground">Judgement confidence</dt>
                    <dd className="m-0 font-mono">{judgement.confidence.toFixed(3)}</dd>
                  </div>
                </dl>
              </li>
            ))}
          </ul>
        ) : (
          <p className="m-0 text-sm text-muted-foreground">{noJudgementMessage(outcome.processingFailureReason)}</p>
        )}
      </section>
      {outcome.aggregatorVersion && <p className="m-0 text-xs text-muted-foreground">Deterministic aggregation policy: {outcome.aggregatorVersion}</p>}
      <HumanReviewPanel
        analysisRunId={analysisRunId}
        verificationId={outcome.id}
        machineStatus={outcome.finalStatus}
        reviews={outcome.humanReviews}
      />
      {outcome.evidencePassages.length > 0 ? (
        <ol className="space-y-2" aria-label={`Evidence Passages for Atomic Claim ${outcome.atomicClaimId}`}>
          {outcome.evidencePassages.map((passage) => (
            <li key={passage.id} className="rounded-md border border-border/80 bg-muted/10">
              <Collapsible className="group/passage">
                <CollapsibleTrigger className="flex min-h-11 w-full flex-wrap items-center justify-between gap-2 rounded-md px-3 py-2 text-left text-sm focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
                  <span className="font-medium">Evidence Passage · fused rank {passage.fusedRank}</span>
                  <span className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                    {passage.evidenceJudgement ? `${passage.evidenceJudgement.judgement.replaceAll("_", " ").toLowerCase()} · ${passage.evidenceJudgement.evidenceRole.replaceAll("_", " ").toLowerCase()}` : "not semantically assessed"}
                    <span>Vector {passage.vectorRank ?? "—"} · lexical {passage.lexicalRank ?? "—"}</span>
                  </span>
                </CollapsibleTrigger>
                <CollapsibleContent className="space-y-3 border-t border-border/70 p-3">
                  <blockquote className="m-0 whitespace-pre-wrap break-words text-sm leading-relaxed text-foreground">{passage.text}</blockquote>
                  {passage.evidenceJudgement && (
                    <div className="space-y-2 rounded-md border border-border bg-card p-3">
                      <p className="m-0 text-sm font-medium">Evidence Judgement · {passage.evidenceJudgement.providerId} {passage.evidenceJudgement.modelId ?? ""} {passage.evidenceJudgement.providerVersion}</p>
                      <p className="m-0 text-xs text-muted-foreground">{passage.evidenceJudgement.judgement.replaceAll("_", " ").toLowerCase()} · role: {passage.evidenceJudgement.evidenceRole.replaceAll("_", " ").toLowerCase()} · provisional rubric strength {passage.evidenceJudgement.calibratedStrength.toFixed(3)}</p>
                      <dl className="grid grid-cols-2 gap-2 text-xs sm:grid-cols-3">
                        {([
                          ["Judgement confidence", passage.evidenceJudgement.confidence],
                          ["Directness", passage.evidenceJudgement.directness],
                          ["Claim-scope match", passage.evidenceJudgement.claimScopeMatch],
                          ["Study-design quality", passage.evidenceJudgement.studyDesignQuality],
                          ["Relevance", passage.evidenceJudgement.relevance],
                        ] as const).map(([label, value]) => (
                          <div key={label}>
                            <dt className="font-mono text-muted-foreground">{label}</dt>
                            <dd className="m-0 font-mono">{value.toFixed(3)}</dd>
                          </div>
                        ))}
                      </dl>
                    </div>
                  )}
                  <dl className="grid gap-3 text-xs sm:grid-cols-2">
                    <div className="min-w-0 space-y-1">
                      <dt className="font-mono uppercase text-muted-foreground">Section</dt>
                      <dd className="m-0 break-words">Section {passage.sectionOrder + 1} · {passage.sectionHeading ?? "No heading in parser output"}</dd>
                    </div>
                    <div className="space-y-1">
                      <dt className="font-mono uppercase text-muted-foreground">Paragraph range</dt>
                      <dd className="m-0">{passage.paragraphStart}–{passage.paragraphEnd}{passage.pageNumber ? ` · page ${passage.pageNumber}` : ""}</dd>
                    </div>
                    <div className="min-w-0 space-y-1">
                      <dt className="font-mono uppercase text-muted-foreground">Source asset</dt>
                      <dd className="m-0 break-all font-mono">{passage.sourceAssetId}</dd>
                    </div>
                    <div className="min-w-0 space-y-1">
                      <dt className="font-mono uppercase text-muted-foreground">Asset SHA-256</dt>
                      <dd className="m-0 break-all font-mono">{passage.contentSha256}</dd>
                    </div>
                    <div className="space-y-1">
                      <dt className="font-mono uppercase text-muted-foreground">Parser provenance</dt>
                      <dd className="m-0 break-words">{passage.parserProvider} · {passage.parserVersion}</dd>
                    </div>
                    <div className="space-y-1">
                      <dt className="font-mono uppercase text-muted-foreground">Language detection</dt>
                      <dd className="m-0 break-words">{passage.language} · {passage.languageDetectorVersion}</dd>
                    </div>
                    <div className="space-y-1">
                      <dt className="font-mono uppercase text-muted-foreground">Retrieval profile</dt>
                      <dd className="m-0 break-words">{passage.retrievalProfile.profileId} · vector top {passage.retrievalProfile.vectorCandidateLimit} · lexical top {passage.retrievalProfile.lexicalCandidateLimit} · final {passage.retrievalProfile.finalCandidateLimit} · RRF {passage.retrievalProfile.reciprocalRankFusionConstant}</dd>
                    </div>
                    <div className="space-y-1">
                      <dt className="font-mono uppercase text-muted-foreground">Embedding profile</dt>
                      <dd className="m-0 break-words">{passage.retrievalProfile.embeddingProvider} · {passage.retrievalProfile.embeddingModel} · {passage.retrievalProfile.embeddingVersion} · {passage.retrievalProfile.embeddingDimension} dimensions</dd>
                    </div>
                    <div className="min-w-0 space-y-1 sm:col-span-2">
                      <dt className="font-mono uppercase text-muted-foreground">Embedding profile SHA-256</dt>
                      <dd className="m-0 break-all font-mono">{passage.retrievalProfile.embeddingProfileHash}</dd>
                    </div>
                    <div className="space-y-1">
                      <dt className="font-mono uppercase text-muted-foreground">Reciprocal-rank fusion score</dt>
                      <dd className="m-0 font-mono">{passage.fusionScore.toFixed(6)}</dd>
                    </div>
                  </dl>
                </CollapsibleContent>
              </Collapsible>
            </li>
          ))}
        </ol>
      ) : (
        <p className="m-0 text-xs text-muted-foreground">
          {outcome.processingStatus === "INCOMPLETE"
            ? "Processing stopped before this Claim–Reference Verification completed."
            : outcome.verificationScope !== "FULL_TEXT"
              ? "Evidence retrieval was not run for this verification scope."
              : indexingStatus === "PENDING"
                ? "Evidence Passage retrieval is pending."
                : indexingStatus === "FAILED"
                  ? "Evidence Passage indexing failed; this pair has no fabricated domain status."
                  : indexingStatus === "COMPLETED"
                    ? "No ranked passages matched this Atomic Claim in this Cited Paper."
                    : "No Evidence Passages were retrieved for this Cited Paper."}
        </p>
      )}
    </li>
  );
}
