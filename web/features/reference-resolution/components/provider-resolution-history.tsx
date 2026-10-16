import { ChevronDown } from "lucide-react";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import type { ReferenceResolutionReportResponse } from "@/features/analysis-runs/types";
import { cn } from "@/lib/utils";

type ProviderHistoryEntry = ReferenceResolutionReportResponse["referenceResolution"]["entries"][number];

export function ProviderResolutionHistory({ entry, className }: { entry: ProviderHistoryEntry; className?: string }) {
  return (
    <Collapsible className={cn("group/provider-history", className)}>
      <CollapsibleTrigger className="flex min-h-9 w-full items-center justify-between gap-2 py-1 text-left text-xs font-medium text-muted-foreground focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
        Provider resolution history
        <ChevronDown className="size-4 transition-transform group-data-[open]/provider-history:rotate-180" aria-hidden="true" />
      </CollapsibleTrigger>
      <CollapsibleContent className="mt-3 space-y-3 text-sm">
        <dl className="grid gap-2 sm:grid-cols-2">
          <div><dt className="text-xs text-muted-foreground">Provider-chain outcome</dt><dd className="m-0">{entry.resolutionOutcome != null ? entry.resolutionOutcome.replaceAll("_", " ").toLowerCase() : "UNKNOWN"}</dd></div>
          {entry.metadataOriginProviderId != null && (
            <div><dt className="text-xs text-muted-foreground">Metadata selected by the matcher</dt><dd className="m-0">{entry.metadataOriginProviderId}</dd></div>
          )}
        </dl>
        {(entry.providerLookups?.length ?? 0) > 0 && (
          <div>
            <h5 className="m-0 text-xs font-medium text-muted-foreground">Logical lookups</h5>
            <ul className="mt-1 space-y-1">
              {entry.providerLookups?.map((lookup) => (
                <li key={`${lookup.providerId}-${lookup.lookupOrdinal}`}>
                  {lookup.providerId} · lookup {lookup.lookupOrdinal} · {lookup.source.toLowerCase()} · {lookup.outcomeCode.replaceAll("_", " ").toLowerCase()}
                </li>
              ))}
            </ul>
          </div>
        )}
        {(entry.providerAttempts?.length ?? 0) > 0 && (
          <div>
            <h5 className="m-0 text-xs font-medium text-muted-foreground">Wire attempts</h5>
            <ul className="mt-1 space-y-1">
              {entry.providerAttempts?.map((attempt) => (
                <li key={`${attempt.providerId}-${attempt.attemptOrdinal}`} className="break-words">
                  {attempt.providerId} · attempt {attempt.attemptOrdinal} · {attempt.state.toLowerCase()}
                  {attempt.outcomeCode != null ? ` · ${attempt.outcomeCode.replaceAll("_", " ").toLowerCase()}` : ""}
                  {attempt.httpStatus != null ? ` · HTTP ${attempt.httpStatus}` : ""}
                  {attempt.durationMillis != null ? ` · ${attempt.durationMillis} ms` : ""}
                  {attempt.responseBytes != null ? ` · ${attempt.responseBytes} response bytes` : ""}
                  {` · reserved ${attempt.reservedAt}`}
                  {attempt.startedAt != null ? ` · started ${attempt.startedAt}` : ""}
                  {attempt.completedAt != null ? ` · completed ${attempt.completedAt}` : ""}
                  {attempt.unknownAt != null ? ` · unknown since ${attempt.unknownAt}` : ""}
                  {attempt.nextAttemptNotBefore != null ? ` · retry not before ${attempt.nextAttemptNotBefore}` : ""}
                </li>
              ))}
            </ul>
          </div>
        )}
        <p className="m-0 text-xs text-muted-foreground">This history contains provider identifiers and bounded result codes only; cached lookups are not wire attempts.</p>
      </CollapsibleContent>
    </Collapsible>
  );
}
