export type PdfTextHighlightRect = {
  left: number;
  top: number;
  width: number;
  height: number;
};

/** Translate the browser's rendered text-range rectangles into the PDF page layer's coordinates. */
export function pdfTextRangeClientRects(
  textRange: Range,
  layerRect: Pick<DOMRectReadOnly, "left" | "top">,
): PdfTextHighlightRect[] {
  if (typeof textRange.getClientRects !== "function") return [];

  return Array.from(textRange.getClientRects())
    .filter((rect) => rect.width > 0 && rect.height > 0)
    .map((rect) => ({
      left: rect.left - layerRect.left,
      top: rect.top - layerRect.top,
      width: rect.width,
      height: rect.height,
    }));
}
