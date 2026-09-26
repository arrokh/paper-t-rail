import type { ReferenceResolutionReportResponse } from "@/features/analysis-runs/types";
import { Badge } from "@/components/ui/badge";
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

      <div className="space-y-2 border-t border-border/70 pt-3">
        <h6 className="font-mono text-[0.65rem] tracking-wide text-muted-foreground uppercase">Terminal verification status</h6>
        {access.verificationOutcomes.length === 0 ? (
          <p className="text-sm text-muted-foreground">No Atomic Claim is linked to this Cited Reference.</p>
        ) : (
          <ul className="space-y-2">
            {access.verificationOutcomes.map((outcome) => (
              <li key={outcome.atomicClaimId} className="flex flex-wrap items-center gap-2 text-sm">
                <span className="font-mono text-xs text-muted-foreground">Claim {outcome.atomicClaimId.slice(0, 8)}</span>
                <Badge variant="outline" className="capitalize">
                  {outcome.finalStatus
                    ? outcome.finalStatus.replaceAll("_", " ").toLowerCase()
                    : "pending semantic assessment"}
                </Badge>
                <span className="text-muted-foreground">Scope: {outcome.verificationScope.replaceAll("_", " ").toLowerCase()}</span>
                {outcome.terminalReason && (
                  <span className="text-muted-foreground">· {outcome.terminalReason.replaceAll("_", " ").toLowerCase()}</span>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}
