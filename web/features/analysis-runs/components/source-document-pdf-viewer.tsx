"use client";

import { useEffect, useRef, useState } from "react";
import type { PDFDocumentLoadingTask, PDFDocumentProxy, PDFPageProxy } from "pdfjs-dist";
import { ArrowUp, ChevronLeft, ChevronRight, Download, Focus, RotateCcw, ZoomIn, ZoomOut } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { sourceDocumentPdfAccessQueryOptions } from "@/features/analysis-runs/queries/analysis-run-queries";
import { scrollToPaperReviewCard } from "@/features/analysis-runs/scroll-to-paper-review-card";
import { useQuery } from "@tanstack/react-query";
import { cn } from "@/lib/utils";
import { scrollToPageTop } from "@/lib/scroll-to-page-top";
import { findPdfTextMatches, type PdfTextMatches } from "@/features/analysis-runs/components/pdf-text-search";
import { SourceDocumentPdfPage } from "@/features/analysis-runs/components/source-document-pdf-page";

type PdfJsLibrary = typeof import("pdfjs-dist");
type SearchResult = PdfTextMatches & { pageNumber: number };
type LoadedPdf = { url: string; library: PdfJsLibrary; document: PDFDocumentProxy };
type PdfSearchState = { key: string; result: SearchResult | null; message?: string };

export function SourceDocumentPdfViewer({
  analysisRunId,
  filename,
  highlightText,
  highlightContextText = null,
  highlightRequestId = 0,
}: {
  analysisRunId: string;
  filename: string;
  highlightText: string | null | string[];
  highlightContextText?: string | null;
  highlightRequestId?: number;
}) {
  const pdfQuery = useQuery(sourceDocumentPdfAccessQueryOptions(analysisRunId));
  const [loadedPdf, setLoadedPdf] = useState<LoadedPdf | null>(null);
  const [pageState, setPageState] = useState<{ url: string; pageNumber: number; pageInput: string } | null>(null);
  const [zoom, setZoom] = useState(1);
  const [documentErrorState, setDocumentErrorState] = useState<{ url: string; message: string } | null>(null);
  const [pageError, setPageError] = useState<string | null>(null);
  const [pageRendering, setPageRendering] = useState(false);
  const [searchState, setSearchState] = useState<PdfSearchState | null>(null);
  const textContentCache = useRef(new Map<number, Awaited<ReturnType<PDFPageProxy["getTextContent"]>>>());
  const searchRequestId = useRef(0);
  const pdfDocumentUrl = pdfQuery.data?.viewUrl ?? null;
  const searchCandidates = Array.isArray(highlightText) ? highlightText : highlightText ? [highlightText] : [];
  const searchTextKey = JSON.stringify(searchCandidates);
  const activePdf = loadedPdf?.url === pdfDocumentUrl ? loadedPdf : null;
  const pdfjs = activePdf?.library ?? null;
  const pdfDocument = activePdf?.document ?? null;
  const pageCount = pdfDocument?.numPages ?? 0;
  const pageNumber = pageState?.url === pdfDocumentUrl ? pageState.pageNumber : 1;
  const pageInput = pageState?.url === pdfDocumentUrl ? pageState.pageInput : "1";
  const documentError = documentErrorState?.url === pdfDocumentUrl ? documentErrorState.message : null;
  const searchKey = JSON.stringify([pdfDocumentUrl, searchCandidates, highlightContextText, highlightRequestId]);
  const activeSearch = searchState?.key === searchKey ? searchState : null;
  const searching = Boolean(pdfDocument && searchCandidates.length > 0 && !activeSearch);
  const searchResult = activeSearch?.result ?? null;
  const searchStatus = activeSearch
    ? activeSearch.message ?? (activeSearch.result
      ? activeSearch.result.matchedTargetCount === activeSearch.result.targetCount
        ? `Selected text found · page ${activeSearch.result.pageNumber}`
        : `${activeSearch.result.matchedTargetCount} of ${activeSearch.result.targetCount} selected passages found · page ${activeSearch.result.pageNumber}`
      : "No matching text found in the PDF")
    : searching ? "Finding selected text in the PDF…" : "";

  useEffect(() => {
    if (!pdfDocumentUrl) return;

    let active = true;
    let loadingTask: PDFDocumentLoadingTask | null = null;
    textContentCache.current.clear();

    void (async () => {
      try {
        const library = await import("pdfjs-dist");
        if (!active) return;
        library.GlobalWorkerOptions.workerSrc = new URL("pdfjs-dist/build/pdf.worker.min.mjs", import.meta.url).toString();
        loadingTask = library.getDocument({ url: pdfDocumentUrl, withCredentials: false });
        const document = await loadingTask.promise;
        if (!active) return;

        setLoadedPdf({ url: pdfDocumentUrl, library, document });
      } catch {
        if (active) setDocumentErrorState({ url: pdfDocumentUrl, message: "The original uploaded PDF could not be loaded. Refresh the PDF link and try again." });
      }
    })();

    return () => {
      active = false;
      if (loadingTask) void loadingTask.destroy();
    };
  }, [pdfDocumentUrl]);

  useEffect(() => {
    const candidates = JSON.parse(searchTextKey) as string[];
    if (!pdfDocument || candidates.length === 0) return;

    let active = true;
    const requestId = ++searchRequestId.current;

    void findTextInDocument(pdfDocument, candidates, highlightContextText, textContentCache.current).then((result) => {
      if (!active || requestId !== searchRequestId.current) return;
      setSearchState({ key: searchKey, result });
      if (result) {
        setPageState({ url: pdfDocumentUrl!, pageInput: String(result.pageNumber), pageNumber: result.pageNumber });
      }
    }).catch(() => {
      if (!active || requestId !== searchRequestId.current) return;
      setSearchState({ key: searchKey, result: null, message: "Could not search the PDF text" });
    });

    return () => {
      active = false;
    };
  }, [pdfDocument, pdfDocumentUrl, highlightContextText, searchKey, searchTextKey]);

  function goToPage(nextPage: number) {
    const validPage = Math.min(pageCount, Math.max(1, nextPage));
    if (validPage === pageNumber) {
      if (pdfDocumentUrl) setPageState({ url: pdfDocumentUrl, pageNumber: validPage, pageInput: String(validPage) });
      return;
    }
    if (pdfDocumentUrl) setPageState({ url: pdfDocumentUrl, pageNumber: validPage, pageInput: String(validPage) });
  }

  function commitPageInput() {
    const requestedPage = Number.parseInt(pageInput, 10);
    if (Number.isInteger(requestedPage)) goToPage(requestedPage);
    else if (pdfDocumentUrl) setPageState({ url: pdfDocumentUrl, pageNumber, pageInput: String(pageNumber) });
  }

  return (
    <section aria-labelledby="original-paper-heading" className="flex min-w-0 flex-col gap-3 md:h-full md:min-h-0">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="min-w-0">
          <h3 id="original-paper-heading" className="m-0 truncate text-sm font-semibold">Original uploaded paper</h3>
          <p className="m-0 truncate text-xs text-muted-foreground">{filename}</p>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <Button
            type="button"
            variant="outline"
            size="icon-sm"
            aria-label="Focus Paper Review"
            title="Focus Paper Review"
            onClick={scrollToPaperReviewCard}
          >
            <Focus aria-hidden="true" />
          </Button>
          <Button type="button" variant="outline" size="icon-sm" aria-label="Back to top" title="Back to top" onClick={scrollToPageTop}>
            <ArrowUp aria-hidden="true" />
          </Button>
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

      {pdfQuery.isPending && (
        <div role="status" aria-label="Loading original paper" className="space-y-2">
          <span className="sr-only">Loading original paper…</span>
          <Skeleton className="source-document-pdf-size w-full rounded-lg md:min-h-0 md:flex-1" />
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

      {pdfDocumentUrl && !documentError && (
        <div className="flex min-w-0 flex-1 flex-col overflow-hidden rounded-lg border border-border bg-card shadow-sm md:min-h-0">
          <div className="flex min-w-0 shrink-0 flex-wrap items-center gap-1 rounded-t-lg bg-foreground px-2 py-1.5 text-background sm:gap-2 sm:px-3">
            <Button type="button" variant="ghost" size="icon-sm" aria-label="Previous PDF page" title="Previous PDF page" disabled={!pdfDocument || pageNumber <= 1} onClick={() => goToPage(pageNumber - 1)} className="text-background hover:bg-background/15 hover:text-background">
              <ChevronLeft aria-hidden="true" />
            </Button>
            <label className="sr-only" htmlFor="source-pdf-page">PDF page number</label>
            <Input
              id="source-pdf-page"
              type="number"
              min={1}
              max={pageCount || undefined}
              value={pageInput}
              disabled={!pdfDocument}
              onChange={(event) => {
                if (pdfDocumentUrl) setPageState({ url: pdfDocumentUrl, pageNumber, pageInput: event.currentTarget.value });
              }}
              onBlur={commitPageInput}
              onKeyDown={(event) => {
                if (event.key === "Enter") commitPageInput();
              }}
              className="h-7 w-12 border-0 bg-background px-1 text-center text-xs text-foreground shadow-none focus-visible:ring-1"
            />
            <span className="shrink-0 text-xs" aria-label={pageCount ? `of ${pageCount} PDF pages` : "PDF page count loading"}>/ {pageCount || "…"}</span>
            <Button type="button" variant="ghost" size="icon-sm" aria-label="Next PDF page" title="Next PDF page" disabled={!pdfDocument || pageNumber >= pageCount} onClick={() => goToPage(pageNumber + 1)} className="text-background hover:bg-background/15 hover:text-background">
              <ChevronRight aria-hidden="true" />
            </Button>
            <span aria-hidden="true" className="mx-1 h-5 border-l border-background/30" />
            <Button type="button" variant="ghost" size="icon-sm" aria-label="Zoom out" title="Zoom out" disabled={!pdfDocument || zoom <= 0.75} onClick={() => setZoom((value) => Math.max(0.75, Math.round((value - 0.25) * 100) / 100))} className="text-background hover:bg-background/15 hover:text-background">
              <ZoomOut aria-hidden="true" />
            </Button>
            <span className="min-w-10 text-center text-xs">{Math.round(zoom * 100)}%</span>
            <Button type="button" variant="ghost" size="icon-sm" aria-label="Zoom in" title="Zoom in" disabled={!pdfDocument || zoom >= 2} onClick={() => setZoom((value) => Math.min(2, Math.round((value + 0.25) * 100) / 100))} className="text-background hover:bg-background/15 hover:text-background">
              <ZoomIn aria-hidden="true" />
            </Button>
            <span aria-live="polite" className="ml-auto min-w-0 flex-1 truncate text-right text-xs text-background/80 sm:ml-2">
              {searching ? <span className="inline-flex items-center gap-1"><Spinner aria-hidden="true" /> Searching…</span> : searchStatus}
            </span>
          </div>

          {pdfDocument && pdfjs && (
            <div className="relative flex min-h-0 min-w-0 flex-1 flex-col">
              <SourceDocumentPdfPage
                pdfDocument={pdfDocument}
                pdfjs={pdfjs}
                pageNumber={pageNumber}
                zoom={zoom}
                highlightedItemIndexes={searchResult?.pageNumber === pageNumber ? searchResult.itemIndexes : []}
                highlightedTextRanges={searchResult?.pageNumber === pageNumber ? searchResult.itemRanges : []}
                onRenderingChange={setPageRendering}
                onError={setPageError}
              />
              {pageRendering && (
                <div role="status" aria-label="Rendering PDF page" className="absolute inset-0 z-10 flex items-center justify-center gap-2 bg-background/70 text-sm text-muted-foreground backdrop-blur-[1px]">
                  <Spinner aria-hidden="true" />
                  Rendering page…
                </div>
              )}
            </div>
          )}

          {!pdfDocument && (
            <div role="status" aria-label="Loading original PDF" className="source-document-pdf-size flex items-center justify-center gap-2 bg-background/80 text-sm text-muted-foreground md:min-h-0 md:flex-1">
              <Spinner aria-hidden="true" />
              Loading PDF…
            </div>
          )}
        </div>
      )}

      {(documentError || pageError) && (
        <Alert variant="destructive">
          <AlertTitle>Original PDF unavailable</AlertTitle>
          <AlertDescription className="space-y-3">
            <p>{documentError ?? "The selected PDF page could not be rendered."}</p>
            <Button type="button" variant="outline" size="sm" onClick={() => void pdfQuery.refetch()} disabled={pdfQuery.isFetching}>
              <RotateCcw aria-hidden="true" />
              {pdfQuery.isFetching ? "Retrying…" : "Retry"}
            </Button>
          </AlertDescription>
        </Alert>
      )}
    </section>
  );
}

async function findTextInDocument(
  document: PDFDocumentProxy,
  candidates: readonly string[],
  context: string | null,
  textCache: Map<number, Awaited<ReturnType<PDFPageProxy["getTextContent"]>>>,
): Promise<SearchResult | null> {
  let bestMatch: SearchResult | null = null;
  for (let pageNumber = 1; pageNumber <= document.numPages; pageNumber += 1) {
    let textContent = textCache.get(pageNumber);
    if (!textContent) {
      textContent = await (await document.getPage(pageNumber)).getTextContent();
      textCache.set(pageNumber, textContent);
    }

    const match = findPdfTextMatches(textContent.items, candidates, context);
    // Keep a primary passage match actionable when parser and PDF extraction differ.
    if (match.matchedTargetCount === 0) continue;

    const result = { pageNumber, ...match };
    if (
      !bestMatch
      || (result.contextMatched && !bestMatch.contextMatched)
      || (result.contextMatched === bestMatch.contextMatched && result.matchedTargetCount > bestMatch.matchedTargetCount)
    ) {
      bestMatch = result;
    }

    if (result.matchedTargetCount === result.targetCount && (!context || result.contextMatched)) return result;
  }

  return bestMatch;
}
