import { useEffect, useRef, useState } from "react";
import type { MouseEvent } from "react";
import { ArrowRight, ChevronDown } from "lucide-react";
import type { ReferenceResolutionReportResponse } from "@/lib/types";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { ReferenceResolutionBadge } from "@/components/reference-resolution-badge";

type ReferenceResolutionEntry = ReferenceResolutionReportResponse["referenceResolution"]["entries"][number];

export function ReferenceResolutionEntryCard({
  entry,
  anchorId,
  parsedEntryHref,
  parsedEntryAvailable,
  onViewParsedEntry,
}: {
  entry: ReferenceResolutionEntry;
  anchorId: string;
  parsedEntryHref: string;
  parsedEntryAvailable: boolean;
  onViewParsedEntry: (event: MouseEvent<HTMLAnchorElement>, referenceKey: string) => void;
}) {
  const sourcePreviewRef = useRef<HTMLParagraphElement>(null);
  const [hasLongSourceReference, setHasLongSourceReference] = useState(false);

  useEffect(() => {
    if (hasLongSourceReference) return;
    const preview = sourcePreviewRef.current;
    if (!preview) return;

    const detectOverflow = () => {
      if (preview.scrollHeight > preview.clientHeight + 1) setHasLongSourceReference(true);
    };
    detectOverflow();
    const resizeObserver = new ResizeObserver(detectOverflow);
    resizeObserver.observe(preview);
    return () => resizeObserver.disconnect();
  }, [entry.rawText, hasLongSourceReference]);

  return (
    <li id={anchorId} className="reference-resolution-anchor scroll-mt-24">
      <article className="overflow-hidden rounded-xl border border-border bg-card">
        <header className="flex flex-col gap-3 bg-muted/20 p-4 sm:flex-row sm:items-start sm:justify-between">
          <div className="min-w-0 space-y-2">
            <p className="break-words font-mono text-xs tracking-wide text-muted-foreground">
              {entry.localReferenceKey} · {entry.referenceType.toLowerCase().replaceAll("_", " ")}{entry.year ? ` · ${entry.year}` : ""}
            </p>
            <h4 className="break-words font-heading text-base font-semibold leading-relaxed">
              {entry.title || entry.localReferenceKey}
            </h4>
            {entry.authors.length > 0 && (
              <p className="break-words text-sm text-muted-foreground">{entry.authors.join(", ")}</p>
            )}
          </div>
          <ReferenceResolutionBadge status={entry.status} />
        </header>

        <div className="grid gap-4 border-t border-border p-4 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-center">
          <dl className="grid gap-3 sm:grid-cols-2">
            {entry.reasonCode && (
              <div className="min-w-0 space-y-1">
                <dt className="font-mono text-[0.65rem] tracking-wide text-muted-foreground uppercase">Resolution</dt>
                <dd className="m-0 break-words text-sm text-foreground">{entry.reasonCode.replaceAll("_", " ").toLowerCase()}</dd>
              </div>
            )}
            {entry.confidenceScore !== null && (
              <div className="space-y-1">
                <dt className="font-mono text-[0.65rem] tracking-wide text-muted-foreground uppercase">Match confidence</dt>
                <dd className="m-0 font-mono text-sm text-foreground">{entry.confidenceScore.toFixed(3)}</dd>
              </div>
            )}
            {entry.matchMethod && (
              <div className="space-y-1">
                <dt className="font-mono text-[0.65rem] tracking-wide text-muted-foreground uppercase">Resolution method</dt>
                <dd className="m-0 break-words text-sm text-foreground">{entry.matchMethod.replaceAll("_", " ").toLowerCase()}</dd>
              </div>
            )}
            {entry.doi && (
              <div className="min-w-0 space-y-1">
                <dt className="font-mono text-[0.65rem] tracking-wide text-muted-foreground uppercase">Source DOI</dt>
                <dd className="m-0 break-all font-mono text-xs text-foreground">{entry.doi}</dd>
              </div>
            )}
          </dl>

          {parsedEntryAvailable && (
            <a
              href={parsedEntryHref}
              className="inline-flex min-h-11 items-center justify-start gap-2 rounded-md text-sm font-medium text-primary underline underline-offset-4 hover:text-primary/80 focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50 sm:justify-end"
              onClick={(event) => onViewParsedEntry(event, entry.localReferenceKey)}
            >
              View parsed entry <ArrowRight className="size-4" aria-hidden="true" />
            </a>
          )}
        </div>

        <section className="space-y-2 border-t border-border p-4" aria-label={`Original bibliography entry ${entry.localReferenceKey}`}>
          <h5 className="font-mono text-[0.65rem] tracking-wide text-muted-foreground uppercase">Original bibliography entry</h5>
          {hasLongSourceReference ? (
            <Collapsible className="group/reference space-y-2">
              <p className="line-clamp-3 break-words text-sm leading-relaxed group-data-[open]/reference:hidden">{entry.rawText}</p>
              <CollapsibleContent>
                <p className="whitespace-pre-wrap break-words text-sm leading-relaxed">{entry.rawText}</p>
              </CollapsibleContent>
              <CollapsibleTrigger className="inline-flex min-h-11 items-center gap-1.5 rounded-md text-sm font-medium text-primary underline underline-offset-4 focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
                <span className="group-data-[open]/reference:hidden">Show full reference</span>
                <span className="hidden group-data-[open]/reference:inline">Show less</span>
                <ChevronDown className="size-4 transition-transform group-data-[open]/reference:rotate-180" aria-hidden="true" />
              </CollapsibleTrigger>
            </Collapsible>
          ) : (
            <p ref={sourcePreviewRef} className="line-clamp-3 break-words text-sm leading-relaxed">{entry.rawText}</p>
          )}
        </section>

        {entry.canonicalPaper && (
          <section className="space-y-2 border-t border-border bg-primary/5 p-4" aria-label={`Matched Canonical Paper for ${entry.localReferenceKey}`}>
            <p className="font-mono text-[0.65rem] tracking-wide text-primary uppercase">Matched Canonical Paper</p>
            <h5 className="break-words font-medium leading-relaxed text-foreground">{entry.canonicalPaper.title}</h5>
            {entry.canonicalPaper.authors.length > 0 && (
              <p className="break-words text-sm text-muted-foreground">{entry.canonicalPaper.authors.join(", ")}</p>
            )}
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
              {entry.canonicalPaper.year && <span>{entry.canonicalPaper.year}</span>}
              {entry.canonicalPaper.doi && (
                <a
                  className="break-all text-primary underline underline-offset-4 hover:text-primary/80"
                  href={`https://doi.org/${entry.canonicalPaper.doi.split("/").map(encodeURIComponent).join("/")}`}
                  target="_blank"
                  rel="noreferrer"
                >
                  Canonical DOI: {entry.canonicalPaper.doi}
                </a>
              )}
              <span className="break-all font-mono">Canonical ID {entry.canonicalPaper.id}</span>
            </div>
          </section>
        )}
      </article>
    </li>
  );
}
