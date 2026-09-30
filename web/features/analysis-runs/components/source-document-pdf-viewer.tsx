"use client";

import { useEffect, useRef } from "react";
import { Download, RotateCcw } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { sourceDocumentPdfQueryOptions } from "@/features/analysis-runs/queries/analysis-run-queries";
import { useQuery } from "@tanstack/react-query";
import { cn } from "@/lib/utils";

export function SourceDocumentPdfViewer({ analysisRunId, filename }: { analysisRunId: string; filename: string }) {
  const pdfQuery = useQuery(sourceDocumentPdfQueryOptions(analysisRunId));
  const pdfFrameRef = useRef<HTMLIFrameElement>(null);
  const downloadHref = `/api/v1/analysis-runs/${encodeURIComponent(analysisRunId)}/source-document`;

  useEffect(() => {
    if (!pdfQuery.data) return;

    const url = URL.createObjectURL(pdfQuery.data);
    const frame = pdfFrameRef.current;
    frame?.setAttribute("src", url);
    return () => {
      if (frame?.getAttribute("src") === url) frame.removeAttribute("src");
      URL.revokeObjectURL(url);
    };
  }, [pdfQuery.data]);

  return (
    <section aria-labelledby="original-paper-heading" className="min-w-0 space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="min-w-0">
          <h3 id="original-paper-heading" className="m-0 truncate text-sm font-semibold">Original uploaded paper</h3>
          <p className="m-0 truncate text-xs text-muted-foreground">{filename}</p>
        </div>
        <a
          href={downloadHref}
          download={filename}
          className={cn(buttonVariants({ variant: "outline", size: "sm" }))}
        >
          <Download aria-hidden="true" />
          Download
        </a>
      </div>

      <p className="m-0 text-xs leading-relaxed text-muted-foreground">
        Citation links use parsed source text. The parser does not provide reliable PDF page coordinates for exact jumps or highlights.
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
          ref={pdfFrameRef}
          title={`Original uploaded PDF: ${filename}`}
          className="source-document-pdf-size w-full rounded-lg border border-border bg-muted/20"
        />
      )}
    </section>
  );
}
