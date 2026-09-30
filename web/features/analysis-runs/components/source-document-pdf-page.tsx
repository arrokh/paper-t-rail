"use client";

import { useEffect, useRef, useState } from "react";
import type { PDFDocumentProxy, PDFPageProxy, RenderTask } from "pdfjs-dist";

type PdfJsLibrary = typeof import("pdfjs-dist");

export function SourceDocumentPdfPage({
  pdfDocument,
  pdfjs,
  pageNumber,
  zoom,
  highlightedItemIndexes,
  onRenderingChange,
  onError,
}: {
  pdfDocument: PDFDocumentProxy;
  pdfjs: PdfJsLibrary;
  pageNumber: number;
  zoom: number;
  highlightedItemIndexes: number[];
  onRenderingChange: (rendering: boolean) => void;
  onError: (message: string | null) => void;
}) {
  const viewerRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const textLayerRef = useRef<HTMLDivElement>(null);
  const textDivsRef = useRef<HTMLElement[]>([]);
  const [viewerWidth, setViewerWidth] = useState(640);
  const [textLayerRevision, setTextLayerRevision] = useState(0);
  const [accessiblePageText, setAccessiblePageText] = useState("");
  const [pageReady, setPageReady] = useState(false);

  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer) return;

    const updateWidth = () => {
      const fallbackWidth = estimatePdfViewerWidth(window.innerWidth);
      setViewerWidth(viewer.clientWidth || fallbackWidth);
    };
    updateWidth();
    if (typeof ResizeObserver === "undefined") return;

    const observer = new ResizeObserver(updateWidth);
    observer.observe(viewer);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    const textLayerElement = textLayerRef.current;
    if (!canvas || !textLayerElement) return;

    let active = true;
    let renderTask: RenderTask | null = null;
    let textLayer: InstanceType<PdfJsLibrary["TextLayer"]> | null = null;
    setPageReady(false);
    onRenderingChange(true);
    onError(null);
    textDivsRef.current = [];
    textLayerElement.replaceChildren();

    void (async () => {
      try {
        const page = await pdfDocument.getPage(pageNumber);
        if (!active) return;

        const baseViewport = page.getViewport({ scale: 1 });
        const scaleToFit = Math.max(0.1, (viewerWidth - 16) / baseViewport.width);
        const viewport = page.getViewport({ scale: scaleToFit * zoom });
        const outputScale = Math.min(window.devicePixelRatio || 1, 2);
        canvas.width = Math.floor(viewport.width * outputScale);
        canvas.height = Math.floor(viewport.height * outputScale);
        canvas.style.width = `${Math.floor(viewport.width)}px`;
        canvas.style.height = `${Math.floor(viewport.height)}px`;
        textLayerElement.style.width = `${Math.floor(viewport.width)}px`;
        textLayerElement.style.height = `${Math.floor(viewport.height)}px`;

        const textContent = await page.getTextContent();
        if (!active) return;
        setAccessiblePageText(pdfPageText(textContent));

        renderTask = page.render({
          canvas,
          viewport,
          transform: [outputScale, 0, 0, outputScale, 0, 0],
        });
        textLayer = new pdfjs.TextLayer({ textContentSource: textContent, container: textLayerElement, viewport });
        await Promise.all([renderTask.promise, textLayer.render()]);
        if (!active) return;

        textDivsRef.current = textLayer.textDivs;
        setTextLayerRevision((revision) => revision + 1);
        setPageReady(true);
        onRenderingChange(false);
      } catch (error) {
        if (!active) return;
        onError(error instanceof Error ? error.message : "The selected PDF page could not be rendered.");
        onRenderingChange(false);
      }
    })();

    return () => {
      active = false;
      renderTask?.cancel();
      textLayer?.cancel();
    };
  }, [onError, onRenderingChange, pageNumber, pdfDocument, pdfjs, viewerWidth, zoom]);

  useEffect(() => {
    for (const [index, textDiv] of textDivsRef.current.entries()) {
      if (highlightedItemIndexes.includes(index)) {
        textDiv.dataset.pdfSearchMatch = "true";
      } else {
        delete textDiv.dataset.pdfSearchMatch;
      }
    }

    const target = textDivsRef.current[highlightedItemIndexes[0] ?? -1];
    const viewer = viewerRef.current;
    if (!target || !viewer) return;

    const scrollToMatch = () => {
      const targetRect = target.getBoundingClientRect();
      const viewerRect = viewer.getBoundingClientRect();
      const targetCenter = viewer.scrollTop + targetRect.top - viewerRect.top + targetRect.height / 2;
      const top = Math.max(0, targetCenter - viewer.clientHeight / 2);
      viewer.scrollTop = top;
    };
    if (typeof requestAnimationFrame !== "function") {
      scrollToMatch();
      return;
    }

    const animationFrame = requestAnimationFrame(scrollToMatch);

    return () => cancelAnimationFrame(animationFrame);
  }, [highlightedItemIndexes, textLayerRevision]);

  return (
    <div ref={viewerRef} data-page-ready={pageReady} className="source-document-pdf-size source-document-pdf-page min-w-0 overflow-auto rounded-b-lg border border-t-0 border-border bg-muted/40 p-2 sm:p-3 md:min-h-0 md:flex-1">
      <div className="relative mx-auto w-fit bg-white shadow-sm">
        <canvas ref={canvasRef} aria-hidden="true" className="block" />
        <div ref={textLayerRef} className="source-document-pdf-text-layer textLayer" aria-hidden="true" />
      </div>
      <p className="sr-only">Text of PDF page {pageNumber}: {accessiblePageText}</p>
    </div>
  );
}

function estimatePdfViewerWidth(viewportWidth: number) {
  if (viewportWidth < 640) return Math.max(1, viewportWidth - 66);
  if (viewportWidth < 768) return Math.max(1, viewportWidth - 90);
  if (viewportWidth < 864) return Math.max(1, viewportWidth - 408);
  if (viewportWidth < 1024) return Math.max(1, Math.round((viewportWidth - 104) * 0.6));
  return Math.max(1, Math.round((viewportWidth - 112) * 0.6));
}

export function pdfPageText(textContent: Awaited<ReturnType<PDFPageProxy["getTextContent"]>>) {
  return textContent.items.filter((item): item is Extract<Awaited<ReturnType<PDFPageProxy["getTextContent"]>>["items"][number], { str: string }> => "str" in item)
    .map((item) => item.str)
    .join(" ");
}
