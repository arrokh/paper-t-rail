"use client";

import { Download, RotateCcw } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { sourceDocumentPdfAccessQueryOptions } from "@/features/analysis-runs/queries/analysis-run-queries";
import { useQuery } from "@tanstack/react-query";
import { cn } from "@/lib/utils";

function buildPdfTextFragmentUrl(viewUrl: string, text: string | null) {
  const normalizedText = text?.replace(/\s+/g, " ").trim();
  if (!normalizedText) return viewUrl;

  const textCharacters = Array.from(normalizedText);
  const matchText = textCharacters.length > 120
    ? textCharacters.slice(0, 120).join("").replace(/\s+\S*$/, "")
    : normalizedText;
  const documentUrl = viewUrl.split("#", 1)[0];
  return `${documentUrl}#:~:text=${encodeURIComponent(matchText)}`;
}

export function SourceDocumentPdfViewer({ analysisRunId, filename, highlightText }: { analysisRunId: string; filename: string; highlightText: string | null }) {
  const pdfQuery = useQuery(sourceDocumentPdfAccessQueryOptions(analysisRunId));

  return (
    <section aria-labelledby="original-paper-heading" className="min-w-0 space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="min-w-0">
          <h3 id="original-paper-heading" className="m-0 truncate text-sm font-semibold">Original uploaded paper</h3>
          <p className="m-0 truncate text-xs text-muted-foreground">{filename}</p>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          {pdfQuery.data && (
            <Button type="button" variant="outline" size="sm" onClick={() => void pdfQuery.refetch()} disabled={pdfQuery.isFetching}>
              <RotateCcw aria-hidden="true" />
              {pdfQuery.isFetching ? "Refreshing…" : "Refresh PDF"}
            </Button>
          )}
          <a
            href={pdfQuery.data?.downloadUrl ?? "#"}
            download={filename}
            referrerPolicy="no-referrer"
            aria-disabled={!pdfQuery.data}
            tabIndex={pdfQuery.data ? undefined : -1}
            onClick={(event) => {
              if (!pdfQuery.data) event.preventDefault();
            }}
            className={cn(buttonVariants({ variant: "outline", size: "sm" }), !pdfQuery.data && "pointer-events-none opacity-50")}
          >
            <Download aria-hidden="true" />
            Download
          </a>
        </div>
      </div>

      <p className="m-0 text-xs leading-relaxed text-muted-foreground">
        Selecting a bibliography entry or citing passage searches its text in the PDF. The match can be approximate because parsed data does not include PDF page coordinates, and browser support varies.
      </p>

      {pdfQuery.isPending && (
        <div role="status" aria-label="Loading original paper" className="space-y-2">
          <span className="sr-only">Loading original paper…</span>
          <Skeleton className="source-document-pdf-size w-full rounded-lg" />
        </div>
      )}

      {pdfQuery.isError && (
        <Alert variant="destructive">
          <AlertTitle>Original PDF unavailable</AlertTitle>
          <AlertDescription className="space-y-3">
            <p>{pdfQuery.error instanceof Error ? pdfQuery.error.message : "The original uploaded PDF could not be loaded."}</p>
            <Button type="button" variant="outline" size="sm" onClick={() => void pdfQuery.refetch()} disabled={pdfQuery.isFetching}>
              <RotateCcw aria-hidden="true" />
              {pdfQuery.isFetching ? "Retrying…" : "Retry"}
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {pdfQuery.data && (
        <iframe
          key={`${analysisRunId}:${highlightText ?? "original"}`}
          src={buildPdfTextFragmentUrl(pdfQuery.data.viewUrl, highlightText)}
          title={`Original uploaded PDF: ${filename}`}
          referrerPolicy="no-referrer"
          className="source-document-pdf-size w-full rounded-lg border border-border bg-muted/20"
        />
      )}
    </section>
  );
}
