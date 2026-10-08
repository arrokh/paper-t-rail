import type { ReferenceResolutionReportResponse } from "@/features/analysis-runs/types";
import { Badge } from "@/components/ui/badge";
import { LocalDateTime } from "@/components/local-date-time";
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

const ACCESS_CAUSE_LABELS: Record<CitedPaperAccess["accessReasons"][number], string> = {
  NO_ACCESSIBLE_METADATA: "The provider returned no usable metadata.",
  NO_FULL_TEXT_LOCATION_RETURNED: "The provider returned no full-text location.",
  FULL_TEXT_LOCATION_LICENSE_MISSING: "A full-text location did not include a license that permits processing.",
  FULL_TEXT_LOCATION_LICENSE_REJECTED: "A full-text location's license does not permit processing.",
  FULL_TEXT_LOCATION_URL_REJECTED: "A full-text location URL did not pass the safe-location policy.",
  FULL_TEXT_DOWNLOAD_FAILED: "A permitted full-text download failed.",
  FULL_TEXT_FORMAT_UNSUPPORTED: "The returned file format is not supported for text extraction.",
  FULL_TEXT_PARSE_FAILED: "The returned PDF or text file could not be parsed into usable text.",
  LANGUAGE_UNSUPPORTED: "Full text was acquired, but its language was not confirmed as supported English; semantic assessment was not run."
};

const ACCESS_PROGRESS_REASON_LABELS: Record<string, string> = {
  ACCESS_SKIPPED_IDENTITY_UNRESOLVED: "Access was skipped because the Bibliography Entry has no resolved identity.",
  ACCESS_SKIPPED_UNSUPPORTED_REFERENCE_TYPE: "Access was skipped because this reference type is unsupported.",
  ACCESS_PATH_NOT_CONFIGURED: "Access lookup was not configured for this Analysis Run.",
  REFERENCE_NOT_ELIGIBLE_FOR_ACCESS: "Access lookup was skipped because the reference was not eligible.",
  REFERENCE_RESOLUTION_RETRIES_EXHAUSTED: "Reference resolution failed after its allowed retries; access was not attempted.",
  CITED_PAPER_ACCESS_RETRIES_EXHAUSTED: "Access processing failed after its allowed retries.",
};

const ACCESS_STYLES: Record<CitedPaperAccess["accessStatus"], string> = {
  FULL_TEXT_AVAILABLE: "border-success-foreground/20 bg-success text-success-foreground",
  ABSTRACT_ONLY: "border-warning-foreground/20 bg-warning text-warning-foreground",
  METADATA_ONLY: "border-warning-foreground/20 bg-warning text-warning-foreground",
  UNAVAILABLE: "border-destructive/25 bg-destructive/10 text-destructive",
};

export function CitedPaperAccessSummary({
  access,
  progressStatus,
  progressReason,
}: {
  access: CitedPaperAccess | null;
  progressStatus: string | null;
  progressReason: string | null;
}) {
  if (!access) {
    const status = progressStatus?.toUpperCase();
    const statusLabel = status === "SKIPPED" ? "Access skipped" : status === "FAILED" ? "Access failed" : status === "WAITING" ? "Access pending" : status === "IN_PROGRESS" ? "Access in progress" : "No access result";
    const reason = progressReason ? ACCESS_PROGRESS_REASON_LABELS[progressReason] ?? progressReason.replaceAll("_", " ").toLowerCase() : null;
    const detail = status === "SKIPPED"
      ? reason ?? "Access was skipped; no more specific cause was recorded."
      : status === "FAILED"
        ? reason ?? "Access processing failed; no more specific cause was recorded."
        : status === "WAITING"
          ? "The access lookup has not started."
          : status === "IN_PROGRESS"
            ? "The access lookup is in progress."
            : reason ?? "No access outcome was recorded; no specific cause is available.";

    return (
      <section className="space-y-2 border-t border-border bg-muted/10 p-4" aria-label="Cited Paper access status">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h5 className="font-mono text-[0.65rem] tracking-wide text-muted-foreground uppercase">Cited Paper access</h5>
          <Badge variant="outline">{statusLabel}</Badge>
        </div>
        <p className="m-0 text-sm text-muted-foreground">{detail}</p>
      </section>
    );
  }

  const accessReasons = [...new Set([
    ...(access.accessReason === "ABSTRACT_ONLY" ? [access.accessReason] : []),
    ...(access.accessReasons.length > 0 ? access.accessReasons : access.accessReason ? [access.accessReason] : []),
  ])];

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
          <dd className="m-0"><LocalDateTime value={access.discoveredAt} /></dd>
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
        {accessReasons.length > 0 && (
          <div className="space-y-1">
            <dt className="font-mono text-[0.65rem] tracking-wide text-muted-foreground uppercase">Access explanation</dt>
            <dd className="m-0">
              <ul className="m-0 list-disc space-y-1 pl-5">
                {accessReasons.map((reason) => (
                  <li key={reason} className="break-words">{ACCESS_CAUSE_LABELS[reason as CitedPaperAccess["accessReasons"][number]] ?? ACCESS_REASON_LABELS[reason as NonNullable<CitedPaperAccess["accessReason"]>] ?? reason.replaceAll("_", " ").toLowerCase()}</li>
                ))}
              </ul>
            </dd>
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

      {access.accessStatus === "FULL_TEXT_AVAILABLE" && (
        <p className="m-0 text-sm text-muted-foreground">
          {access.accessReasons.includes("LANGUAGE_UNSUPPORTED")
            ? "Full text was acquired and parsed, but its language was not confirmed as supported English; semantic assessment was not run. Availability is not evidence of support."
            : "Full-text availability alone does not show whether semantic assessment ran. Availability is not evidence of support."}
        </p>
      )}

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

    </section>
  );
}
