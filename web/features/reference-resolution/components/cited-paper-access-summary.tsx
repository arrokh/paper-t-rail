import type { ReferenceResolutionReportResponse } from "@/features/analysis-runs/types";
import { Badge } from "@/components/ui/badge";
import { ClaimEvidencePassages } from "@/features/reference-resolution/components/claim-evidence-passages";
import { cn } from "@/lib/utils";

type CitedPaperAccess = NonNullable<ReferenceResolutionReportResponse["referenceResolution"]["entries"][number]["citedPaperAccess"]>;

function isHttpsUrl(value: string): boolean {
  try {
    return new URL(value).protocol === "https:";
  } catch {
    return false;
  }
}

const ACCESS_REASON_LABELS: Record<NonNullable<CitedPaperAccess["accessReason"]>, string> = {
  ABSTRACT_ONLY: "Only an abstract was available.",
  NO_LEGAL_FULL_TEXT_LOCATION: "No legally usable full-text location was found.",
  NO_ACCESSIBLE_METADATA: "The provider returned no usable metadata for this Cited Paper.",
  FULL_TEXT_ACQUISITION_FAILED: "Legal full-text locations were found, but none could be acquired as bounded PDF or plain text.",
};

const ACCESS_STYLES: Record<CitedPaperAccess["accessStatus"], string> = {
  FULL_TEXT_AVAILABLE: "border-primary/20 bg-primary/5 text-primary",
  ABSTRACT_ONLY: "border-warning/40 bg-warning/10 text-warning-foreground",
  METADATA_ONLY: "border-warning/40 bg-warning/10 text-warning-foreground",
  UNAVAILABLE: "border-destructive/25 bg-destructive/10 text-destructive",
};

export function CitedPaperAccessSummary({ access }: { access: CitedPaperAccess | null }) {
  if (!access) return null;

  return (
    <section className="space-y-3 border-t border-border bg-muted/10 p-4" aria-label="Cited Paper access and verification status">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h5 className="font-mono text-[0.65rem] tracking-wide text-muted-foreground uppercase">Cited Paper access</h5>
        <Badge variant="outline" className={cn("capitalize", ACCESS_STYLES[access.accessStatus])}>
          {access.accessStatus.replaceAll("_", " ").toLowerCase()}
        </Badge>
      </div>

      <dl className="grid gap-3 text-sm sm:grid-cols-2">
        <div className="min-w-0 space-y-1">
          <dt className="font-mono text-[0.65rem] tracking-wide text-muted-foreground uppercase">Discovery provider</dt>
          <dd className="m-0 break-words">{access.providerId}</dd>
        </div>
        <div className="space-y-1">
          <dt className="font-mono text-[0.65rem] tracking-wide text-muted-foreground uppercase">Discovered</dt>
          <dd className="m-0">{new Date(access.discoveredAt).toLocaleString()}</dd>
        </div>
        {access.sourceUrl && (
          <div className="min-w-0 space-y-1">
            <dt className="font-mono text-[0.65rem] tracking-wide text-muted-foreground uppercase">Discovered legal full-text location</dt>
            <dd className="m-0 break-all">
              {isHttpsUrl(access.sourceUrl) ? (
                <a href={access.sourceUrl} target="_blank" rel="noreferrer" className="text-primary underline underline-offset-4 hover:text-primary/80">
                  {access.sourceUrl}
                </a>
              ) : access.sourceUrl}
            </dd>
          </div>
        )}
        {access.accessReason && (
          <div className="space-y-1">
            <dt className="font-mono text-[0.65rem] tracking-wide text-muted-foreground uppercase">Access note</dt>
            <dd className="m-0 break-words">{ACCESS_REASON_LABELS[access.accessReason]}</dd>
          </div>
        )}
        {access.license && (
          <div className="space-y-1">
            <dt className="font-mono text-[0.65rem] tracking-wide text-muted-foreground uppercase">License</dt>
            <dd className="m-0 break-words">{access.license}</dd>
          </div>
        )}
        {access.version && (
          <div className="space-y-1">
            <dt className="font-mono text-[0.65rem] tracking-wide text-muted-foreground uppercase">Full-text version</dt>
            <dd className="m-0 break-words">{access.version}</dd>
          </div>
        )}
        {access.language && (
          <div className="space-y-1">
            <dt className="font-mono text-[0.65rem] tracking-wide text-muted-foreground uppercase">Detected language</dt>
            <dd className="m-0">{access.language.toLowerCase()}</dd>
          </div>
        )}
        {access.languageDetectorVersion && (
          <div className="space-y-1">
            <dt className="font-mono text-[0.65rem] tracking-wide text-muted-foreground uppercase">Language detector</dt>
            <dd className="m-0">{access.languageDetectorVersion}</dd>
          </div>
        )}
        {access.contentSha256 && (
          <div className="min-w-0 space-y-1 sm:col-span-2">
            <dt className="font-mono text-[0.65rem] tracking-wide text-muted-foreground uppercase">Acquired content SHA-256</dt>
            <dd className="m-0 break-all font-mono text-xs">{access.contentSha256}</dd>
          </div>
        )}
      </dl>

      {access.evidenceIndexing && (
        <div className="space-y-2 border-t border-border/70 pt-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h6 className="font-mono text-[0.65rem] tracking-wide text-muted-foreground uppercase">Evidence Passage indexing</h6>
            <Badge variant="outline" className="capitalize">{access.evidenceIndexing.status.toLowerCase()}</Badge>
          </div>
          {access.evidenceIndexing.failureReason && (
            <p className="m-0 text-sm text-destructive">Indexing failure: {access.evidenceIndexing.failureReason.replaceAll("_", " ").toLowerCase()}</p>
          )}
          <dl className="grid gap-3 text-xs sm:grid-cols-2">
            {access.evidenceIndexing.assetId && (
              <div className="min-w-0 space-y-1">
                <dt className="font-mono uppercase text-muted-foreground">Pinned source asset ID</dt>
                <dd className="m-0 break-all font-mono">{access.evidenceIndexing.assetId}</dd>
              </div>
            )}
            {access.evidenceIndexing.parserProvider && access.evidenceIndexing.parserVersion && (
              <div className="space-y-1">
                <dt className="font-mono uppercase text-muted-foreground">Cited Paper parser</dt>
                <dd className="m-0">{access.evidenceIndexing.parserProvider} · {access.evidenceIndexing.parserVersion}</dd>
              </div>
            )}
            <div className="space-y-1">
              <dt className="font-mono uppercase text-muted-foreground">Hybrid retrieval profile</dt>
              <dd className="m-0 break-words">{access.evidenceIndexing.retrievalProfile.profileId} · vector top {access.evidenceIndexing.retrievalProfile.vectorCandidateLimit} · lexical top {access.evidenceIndexing.retrievalProfile.lexicalCandidateLimit} · final {access.evidenceIndexing.retrievalProfile.finalCandidateLimit} · RRF {access.evidenceIndexing.retrievalProfile.reciprocalRankFusionConstant}</dd>
            </div>
            <div className="space-y-1">
              <dt className="font-mono uppercase text-muted-foreground">Embedding model</dt>
              <dd className="m-0 break-words">{access.evidenceIndexing.retrievalProfile.embeddingProvider} · {access.evidenceIndexing.retrievalProfile.embeddingModel} · {access.evidenceIndexing.retrievalProfile.embeddingVersion} · {access.evidenceIndexing.retrievalProfile.embeddingDimension} dimensions</dd>
            </div>
            <div className="min-w-0 space-y-1 sm:col-span-2">
              <dt className="font-mono uppercase text-muted-foreground">Embedding profile SHA-256</dt>
              <dd className="m-0 break-all font-mono">{access.evidenceIndexing.retrievalProfile.embeddingProfileHash}</dd>
            </div>
          </dl>
        </div>
      )}

      <div className="space-y-2 border-t border-border/70 pt-3">
        <h6 className="font-mono text-[0.65rem] tracking-wide text-muted-foreground uppercase">Claim–Paper Verification and Evidence Passages</h6>
        {access.verificationOutcomes.length === 0 ? (
          <p className="text-sm text-muted-foreground">No Atomic Claim is linked to this Cited Reference.</p>
        ) : (
          <ul className="space-y-2">
            {access.verificationOutcomes.map((outcome) => (
              <ClaimEvidencePassages
                key={outcome.atomicClaimId}
                outcome={outcome}
                indexingStatus={access.evidenceIndexing?.status ?? null}
              />
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}
