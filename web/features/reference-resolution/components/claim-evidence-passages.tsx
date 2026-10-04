import { ChevronDown } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import type {
  ClaimReferenceVerificationOutcome,
  EvidencePassageSpanDiagnostic,
  EvidenceRetrievalProfile,
  ReferenceResolutionReportResponse,
} from "@/features/analysis-runs/types";
import { EvidencePassageCard, type EvidencePresentation } from "@/features/reference-resolution/components/evidence-passage-card";
import { HumanReviewPanel } from "@/features/reference-resolution/components/human-review-panel";

type VerificationOutcome = ClaimReferenceVerificationOutcome;
type IndexingStatus = NonNullable<ReferenceResolutionReportResponse["referenceResolution"]["entries"][number]["citedPaperAccess"]>["evidenceIndexing"] extends infer Indexing
  ? Indexing extends { status: infer Status } ? Status : never
  : never;
type EvidencePassage = VerificationOutcome["evidencePassages"][number];

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

function resultExplanation(outcome: VerificationOutcome, hasJudgements: boolean): string {
  if (outcome.processingStatus === "PENDING") return "No result yet. This pair is waiting for a saved machine assessment.";
  if (outcome.processingStatus === "INCOMPLETE") return "Verification did not finish, so no final machine result was assigned.";
  if (!outcome.finalStatus) return "No final machine result is available for this pair.";
  if (outcome.finalStatus === "INACCESSIBLE") return "No usable cited-paper evidence was available to assess this claim.";
  if (outcome.finalStatus === "UNRESOLVED") return "The cited reference was not resolved to a paper, so no evidence assessment was produced.";
  if (outcome.finalStatus === "UNSUPPORTED_REFERENCE_TYPE") return "This reference type is outside the evidence-verification flow supported by this run.";
  if (!hasJudgements) return "A machine result is saved, but no passage-level judgement is available to explain it.";

  switch (outcome.finalStatus) {
    case "SUPPORTED":
      return "The strongest supporting passage outweighs any conflicting evidence under this run's rules.";
    case "PARTIALLY_SUPPORTED":
      return "Some evidence supports part of the claim, but not enough to support it fully; no stronger conflict changed the result.";
    case "CONTRADICTED":
      return "The strongest conflicting evidence outweighs any support under this run's rules.";
    case "INSUFFICIENT_EVIDENCE":
      return outcome.evidenceConflict
        ? "Supporting and conflicting evidence are similarly strong, so this run could not choose one."
        : "The available evidence was not strong enough to support or contradict the claim decisively.";
  }
}

function distinctValues<T>(values: T[], key: (value: T) => string): T[] {
  const seen = new Set<string>();
  return values.filter((value) => {
    const valueKey = key(value);
    if (seen.has(valueKey)) return false;
    seen.add(valueKey);
    return true;
  });
}

type JudgementProvider = { providerId: string; modelId: string | null; providerVersion: string };

function judgementProviders(outcome: VerificationOutcome): JudgementProvider[] {
  return distinctValues(outcome.evidencePassages.flatMap((passage) => [
    ...(passage.evidenceJudgement ? [passage.evidenceJudgement] : []),
    ...passage.diagnosticSpans.map(({ providerId, modelId, providerVersion }) => ({ providerId, modelId, providerVersion })),
  ]), (provider) => `${provider.providerId}\u0000${provider.modelId ?? ""}\u0000${provider.providerVersion}`);
}

function judgementProviderLabels(outcome: VerificationOutcome): string[] {
  return judgementProviders(outcome).map((provider) =>
    `${provider.providerId} · ${provider.modelId ?? "model not recorded"} · ${provider.providerVersion}`,
  );
}

function spanRubricVersions(outcome: VerificationOutcome): string[] {
  return [...new Set(outcome.evidencePassages.flatMap((passage) =>
    passage.diagnosticSpans.map((span: EvidencePassageSpanDiagnostic) => span.judgementRubricVersion),
  ))];
}

function retrievalProfiles(outcome: VerificationOutcome): EvidenceRetrievalProfile[] {
  return distinctValues(outcome.evidencePassages.map((passage) => passage.retrievalProfile), (profile) =>
    [profile.profileId, profile.vectorCandidateLimit, profile.lexicalCandidateLimit, profile.finalCandidateLimit,
      profile.reciprocalRankFusionConstant, profile.embeddingProvider, profile.embeddingModel,
      profile.embeddingVersion, profile.embeddingDimension, profile.embeddingProfileHash].join("\u0000"),
  );
}

function retrievalProfileLabel(profile: EvidenceRetrievalProfile): string {
  return `${profile.profileId} · vector top ${profile.vectorCandidateLimit} · lexical top ${profile.lexicalCandidateLimit} · final ${profile.finalCandidateLimit} · RRF ${profile.reciprocalRankFusionConstant}`;
}

function embeddingProfileLabel(profile: EvidenceRetrievalProfile): string {
  return `${profile.embeddingProvider} · ${profile.embeddingModel} · ${profile.embeddingVersion} · ${profile.embeddingDimension} dimensions`;
}

function hasProviderAndRetrievalDetails(outcome: VerificationOutcome): boolean {
  return judgementProviders(outcome).length > 0
    || outcome.aggregatorVersion !== null
    || spanRubricVersions(outcome).length > 0
    || retrievalProfiles(outcome).length > 0;
}

function ProviderAndRetrievalDetails({ outcome }: { outcome: VerificationOutcome }) {
  const providers = judgementProviderLabels(outcome);
  const profiles = retrievalProfiles(outcome);
  const rubricVersions = spanRubricVersions(outcome);

  return (
    <Collapsible className="group/provenance rounded-md border border-border">
      <CollapsibleTrigger className="flex min-h-11 w-full items-center justify-between gap-2 rounded-md px-3 py-2 text-left text-sm font-medium focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
        Provider and retrieval details
        <ChevronDown className="size-4 shrink-0 text-muted-foreground transition-transform group-data-[open]/provenance:rotate-180 motion-reduce:transition-none" aria-hidden="true" />
      </CollapsibleTrigger>
      <CollapsibleContent className="border-t border-border p-3">
        <dl className="grid gap-3 text-xs sm:grid-cols-2">
          <div className="min-w-0 space-y-1">
            <dt className="font-mono uppercase text-muted-foreground">Judgement provider · model · version</dt>
            <dd className="m-0 break-words">{providers.length > 0 ? providers.join("; ") : "Not recorded"}</dd>
          </div>
          {outcome.aggregatorVersion && (
            <div className="min-w-0 space-y-1">
              <dt className="font-mono uppercase text-muted-foreground">Aggregation policy</dt>
              <dd className="m-0 break-words">{outcome.aggregatorVersion}</dd>
            </div>
          )}
          {rubricVersions.length > 0 && (
            <div className="min-w-0 space-y-1">
              <dt className="font-mono uppercase text-muted-foreground">Diagnostic rubric</dt>
              <dd className="m-0 break-words">{rubricVersions.join("; ")}</dd>
            </div>
          )}
          {profiles.map((profile) => (
            <div key={`${profile.profileId}-${profile.embeddingProfileHash}`} className="min-w-0 space-y-1">
              <dt className="font-mono uppercase text-muted-foreground">Retrieval profile</dt>
              <dd className="m-0 break-words">{retrievalProfileLabel(profile)}</dd>
              <dt className="mt-2 font-mono uppercase text-muted-foreground">Embedding profile</dt>
              <dd className="m-0 break-words">{embeddingProfileLabel(profile)}</dd>
              <dt className="mt-2 font-mono uppercase text-muted-foreground">Embedding profile SHA-256</dt>
              <dd className="m-0 break-all font-mono">{profile.embeddingProfileHash}</dd>
            </div>
          ))}
        </dl>
      </CollapsibleContent>
    </Collapsible>
  );
}

function ClaimPairSourceDetails({ outcome }: { outcome: VerificationOutcome }) {
  return (
    <Collapsible className="group/source-details rounded-md border border-border">
      <CollapsibleTrigger className="flex min-h-11 w-full items-center justify-between gap-2 rounded-md px-3 py-2 text-left text-xs font-medium focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
        Claim-to-reference source details
        <ChevronDown className="size-4 shrink-0 text-muted-foreground transition-transform group-data-[open]/source-details:rotate-180 motion-reduce:transition-none" aria-hidden="true" />
      </CollapsibleTrigger>
      <CollapsibleContent className="border-t border-border p-3">
        <dl className="grid gap-3 text-xs sm:grid-cols-2">
          <div className="space-y-1">
            <dt className="font-mono uppercase text-muted-foreground">Claim source span (0-based)</dt>
            <dd className="m-0 font-mono">{outcome.claimSourceStartOffset}–{outcome.claimSourceEndOffset}</dd>
          </div>
          <div className="space-y-1 sm:col-span-2">
            <dt className="font-mono uppercase text-muted-foreground">Claim-to-reference association</dt>
            <dd className="m-0">{outcome.associationKind.replaceAll("_", " ").toLowerCase()} — inferred, not author-confirmed</dd>
          </div>
        </dl>
      </CollapsibleContent>
    </Collapsible>
  );
}

export function ClaimEvidencePassages({
  analysisRunId,
  outcome,
  indexingStatus,
  presentation = "pipeline",
}: {
  analysisRunId: string;
  outcome: VerificationOutcome;
  indexingStatus: IndexingStatus | null;
  presentation?: EvidencePresentation;
}) {
  const judgedPassages = outcome.evidencePassages.filter((passage) => passage.evidenceJudgement !== null);

  return (
    <li className="space-y-3 rounded-lg border border-border bg-card p-3">
      <div className="space-y-2">
        <span className="font-mono text-xs text-muted-foreground">Atomic Claim {outcome.atomicClaimId.slice(0, 8)}</span>
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant="outline" className="capitalize">{statusLabel(outcome)}</Badge>
          <Badge variant="secondary">{outcome.verificationScope.replaceAll("_", " ").toLowerCase()} scope</Badge>
          {outcome.evidenceConflict && <Badge variant="destructive">Comparable conflicting evidence</Badge>}
        </div>
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
          <dt className="font-mono uppercase text-muted-foreground">Source citation context</dt>
          <dd className="m-0 break-words leading-relaxed">{outcome.citationContextText}</dd>
        </div>
        <div className="space-y-1">
          <dt className="font-mono uppercase text-muted-foreground">Citation marker(s)</dt>
          <dd className="m-0 break-words">{outcome.citationMarkers.length > 0 ? outcome.citationMarkers.join(", ") : "No marker recorded"}</dd>
        </div>
      </dl>
      {presentation === "paper-review" && <ClaimPairSourceDetails outcome={outcome} />}
      {outcome.terminalReason && <p className="m-0 text-xs text-muted-foreground">Terminal reason: {outcome.terminalReason.replaceAll("_", " ").toLowerCase()}</p>}
      {outcome.processingFailureReason && (
        <Alert variant="destructive">
          <AlertTitle>Verification pair incomplete</AlertTitle>
          <AlertDescription>{failureDescription(outcome.processingFailureReason)}</AlertDescription>
        </Alert>
      )}
      <section aria-labelledby={`why-result-${outcome.id}`} className="space-y-2 border-t border-border pt-3">
        <h4 id={`why-result-${outcome.id}`} className="m-0 text-sm font-semibold">Why this result</h4>
        <p className="m-0 text-sm leading-relaxed text-muted-foreground">{resultExplanation(outcome, judgedPassages.length > 0)}</p>
        {outcome.processingStatus === "COMPLETED" && outcome.evidencePassages.length > 0 && judgedPassages.length === 0 && (
          <p className="m-0 text-xs text-muted-foreground">{noJudgementMessage(outcome.processingFailureReason)}</p>
        )}
      </section>
      {presentation === "paper-review" && hasProviderAndRetrievalDetails(outcome) && <ProviderAndRetrievalDetails outcome={outcome} />}
      <section aria-labelledby={`evidence-passages-${outcome.id}`} className="space-y-2 border-t border-border pt-3">
        <h4 id={`evidence-passages-${outcome.id}`} className="m-0 text-sm font-semibold">Evidence passages</h4>
        {outcome.evidencePassages.length > 0 ? (
          <ol className="m-0 list-none space-y-2 p-0" aria-label={`Evidence Passages for Atomic Claim ${outcome.atomicClaimId}`}>
            {outcome.evidencePassages.map((passage: EvidencePassage) => (
              <EvidencePassageCard key={passage.id} passage={passage} presentation={presentation} />
            ))}
          </ol>
        ) : (
          <p className="m-0 text-sm text-muted-foreground">
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
      </section>
      <HumanReviewPanel
        analysisRunId={analysisRunId}
        verificationId={outcome.id}
        machineStatus={outcome.finalStatus}
        reviews={outcome.humanReviews}
      />
    </li>
  );
}
