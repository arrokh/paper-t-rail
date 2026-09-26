import { ChevronDown } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import type { ReferenceResolutionReportResponse } from "@/features/analysis-runs/types";

type CitedPaperAccess = NonNullable<ReferenceResolutionReportResponse["referenceResolution"]["entries"][number]["citedPaperAccess"]>;
type VerificationOutcome = CitedPaperAccess["verificationOutcomes"][number];

export function ClaimEvidencePassages({ outcome, indexingStatus }: { outcome: VerificationOutcome; indexingStatus: NonNullable<CitedPaperAccess["evidenceIndexing"]>["status"] | null }) {
  return (
    <li className="space-y-3 rounded-lg border border-border bg-card p-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-mono text-xs text-muted-foreground">Claim {outcome.atomicClaimId.slice(0, 8)}</span>
        <Badge variant="outline" className="capitalize">
          {outcome.finalStatus ? outcome.finalStatus.replaceAll("_", " ").toLowerCase() : "pending semantic assessment"}
        </Badge>
        <span className="text-xs text-muted-foreground">Scope: {outcome.verificationScope.replaceAll("_", " ").toLowerCase()}</span>
      </div>
      <p className="m-0 break-words text-sm leading-relaxed text-foreground">{outcome.claimText}</p>
      {outcome.terminalReason && <p className="m-0 text-xs text-muted-foreground">{outcome.terminalReason.replaceAll("_", " ").toLowerCase()}</p>}
      {outcome.evidencePassages.length > 0 ? (
        <ol className="space-y-2" aria-label={`Ranked Evidence Passages for Atomic Claim ${outcome.atomicClaimId}`}>
          {outcome.evidencePassages.map((passage) => (
            <li key={passage.id} className="rounded-md border border-border/80 bg-muted/10">
              <Collapsible className="group/passage">
                <CollapsibleTrigger className="flex min-h-11 w-full flex-wrap items-center justify-between gap-2 rounded-md px-3 py-2 text-left text-sm focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
                  <span className="font-medium">Evidence Passage · fused rank {passage.fusedRank}</span>
                  <span className="flex items-center gap-2 text-xs text-muted-foreground">
                    Vector {passage.vectorRank ?? "—"} · lexical {passage.lexicalRank ?? "—"}
                    <ChevronDown className="size-4 transition-transform group-data-[open]/passage:rotate-180" aria-hidden="true" />
                  </span>
                </CollapsibleTrigger>
                <CollapsibleContent className="space-y-3 border-t border-border/70 p-3">
                  <blockquote className="m-0 whitespace-pre-wrap break-words text-sm leading-relaxed text-foreground">{passage.text}</blockquote>
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
          {outcome.verificationScope !== "FULL_TEXT"
            ? "Evidence retrieval was not run for this verification scope."
            : indexingStatus === "PENDING"
              ? "Evidence Passage retrieval is pending."
              : indexingStatus === "FAILED"
                ? "Evidence Passage indexing failed; see the cited-paper access note."
                : indexingStatus === "COMPLETED"
                  ? "No ranked passages matched this Atomic Claim in this Cited Paper."
                  : "No Evidence Passages were retrieved for this Cited Paper."}
        </p>
      )}
    </li>
  );
}
