"use client";

import { useEffect, useRef, useState } from "react";
import type { PDFDocumentProxy, PDFPageProxy, RenderTask } from "pdfjs-dist";
import type { PdfTextRange } from "@/features/analysis-runs/components/pdf-text-search";
import { pdfTextRangeClientRects } from "@/features/analysis-runs/components/pdf-text-highlight";

type PdfJsLibrary = typeof import("pdfjs-dist");

export function SourceDocumentPdfPage({
  pdfDocument,
  pdfjs,
  pageNumber,
  zoom,
  highlightedItemIndexes,
  highlightedTextRanges,
  onRenderingChange,
  onError,
}: {
  pdfDocument: PDFDocumentProxy;
  pdfjs: PdfJsLibrary;
  pageNumber: number;
  zoom: number;
  highlightedItemIndexes: number[];
  highlightedTextRanges: PdfTextRange[];
  onRenderingChange: (rendering: boolean) => void;
  onError: (message: string | null) => void;
}) {
  const viewerRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const textLayerRef = useRef<HTMLDivElement>(null);
  const highlightLayerRef = useRef<HTMLDivElement>(null);
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
        textLayerElement.style.setProperty("--total-scale-factor", String(viewport.scale * viewport.userUnit));

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
    for (const textDiv of textDivsRef.current) {
      clearPdfSearchHighlights(textDiv);
    }
    highlightLayerRef.current?.replaceChildren();
    const highlightLayer = highlightLayerRef.current;
    const highlightLayerRect = highlightLayer?.getBoundingClientRect();

    const rangesInReverseTextOrder = [...highlightedTextRanges].sort((first, second) =>
      second.itemIndex - first.itemIndex || second.startOffset - first.startOffset,
    );
    for (const range of rangesInReverseTextOrder) {
      const textDiv = textDivsRef.current[range.itemIndex];
      if (textDiv && highlightLayer && highlightLayerRect) {
        highlightPdfTextRange(textDiv, range, highlightLayer, highlightLayerRect);
      }
    }

    const target = textDivsRef.current[highlightedTextRanges[0]?.itemIndex ?? highlightedItemIndexes[0] ?? -1];
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
  }, [highlightedItemIndexes, highlightedTextRanges, textLayerRevision]);

  return (
    <div ref={viewerRef} data-page-ready={pageReady} role="region" aria-label={`PDF page ${pageNumber} scroll area`} tabIndex={0} className="source-document-pdf-size source-document-pdf-page min-w-0 overflow-auto rounded-b-lg border border-t-0 border-border bg-muted/40 p-2 sm:p-3 md:min-h-0 md:flex-1">
      <div className="relative mx-auto w-fit bg-white shadow-sm">
        <canvas ref={canvasRef} aria-hidden="true" className="block" />
        <div ref={textLayerRef} className="source-document-pdf-text-layer textLayer" aria-hidden="true" />
        <div ref={highlightLayerRef} className="pointer-events-none absolute inset-0 z-[2] overflow-hidden mix-blend-multiply" aria-hidden="true" />
      </div>
      <p className="sr-only">Text of PDF page {pageNumber}: {accessiblePageText}</p>
    </div>
  );
}

function clearPdfSearchHighlights(textDiv: HTMLElement) {
  for (const mark of textDiv.querySelectorAll("mark[data-pdf-search-match='true']")) {
    mark.replaceWith(...Array.from(mark.childNodes));
  }
  delete textDiv.dataset.pdfSearchMatch;
  textDiv.normalize();
}

function highlightPdfTextRange(
  textDiv: HTMLElement,
  range: PdfTextRange,
  highlightLayer: HTMLElement,
  layerRect: DOMRect,
): boolean {
  const { startOffset, endOffset } = range;
  if (startOffset < 0 || endOffset <= startOffset || endOffset > (textDiv.textContent?.length ?? 0)) return false;
  const textNodes: Array<{ node: Text; start: number; end: number }> = [];
  const walker = document.createTreeWalker(textDiv, NodeFilter.SHOW_TEXT);
  let node = walker.nextNode();
  let textOffset = 0;

  while (node) {
    const textNode = node as Text;
    const textLength = textNode.textContent?.length ?? 0;
    textNodes.push({ node: textNode, start: textOffset, end: textOffset + textLength });
    textOffset += textLength;
    node = walker.nextNode();
  }

  const intersectingNodes = textNodes.filter(({ start, end }) => start < endOffset && end > startOffset);
  if (intersectingNodes.length === 0) return false;

  const textRange = document.createRange();
  const firstNode = intersectingNodes[0];
  const lastNode = intersectingNodes.at(-1)!;
  textRange.setStart(firstNode.node, Math.max(0, startOffset - firstNode.start));
  textRange.setEnd(lastNode.node, Math.min(lastNode.end - lastNode.start, endOffset - lastNode.start));

  const matchedText = textRange.toString();
  for (const rect of pdfTextRangeClientRects(textRange, layerRect)) {
    const highlight = document.createElement("span");
    highlight.dataset.pdfSearchHighlight = "true";
    highlight.dataset.pdfSearchMatchText = matchedText;
    highlight.style.position = "absolute";
    highlight.style.left = `${rect.left}px`;
    highlight.style.top = `${rect.top}px`;
    highlight.style.width = `${rect.width}px`;
    highlight.style.height = `${rect.height}px`;
    highlight.style.borderRadius = "2px";
    highlight.style.backgroundColor = "color-mix(in srgb, var(--warning) 78%, transparent)";
    highlight.style.boxShadow = "inset 0 0 0 1px color-mix(in srgb, var(--warning-foreground) 42%, transparent)";
    highlightLayer.append(highlight);
  }

  for (const { node: textNode, start, end } of intersectingNodes.reverse()) {
    const localStart = Math.max(0, startOffset - start);
    const localEnd = Math.min(end - start, endOffset - start);
    const selectedText = textNode.splitText(localStart);
    selectedText.splitText(localEnd - localStart);

    const mark = document.createElement("mark");
    mark.dataset.pdfSearchMatch = "true";
    mark.style.backgroundColor = "transparent";
    mark.style.boxShadow = "none";
    selectedText.parentNode?.insertBefore(mark, selectedText);
    mark.append(selectedText);
  }

  return true;
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
