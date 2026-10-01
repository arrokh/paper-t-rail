import { describe, expect, it, vi } from "vitest";
import { pdfTextRangeClientRects } from "@/features/analysis-runs/components/pdf-text-highlight";

describe("PDF text highlight geometry", () => {
  it("maps each rendered text fragment into the current page layer coordinates", () => {
    const textRange = document.createRange();
    const getClientRects = vi.fn(() => [
      new DOMRect(128.5, 246.25, 44.75, 14),
      new DOMRect(128.5, 262.25, 52.5, 14),
      new DOMRect(0, 0, 0, 0),
    ]);
    Object.defineProperty(textRange, "getClientRects", { value: getClientRects });

    expect(pdfTextRangeClientRects(textRange, { left: 112, top: 230 })).toEqual([
      { left: 16.5, top: 16.25, width: 44.75, height: 14 },
      { left: 16.5, top: 32.25, width: 52.5, height: 14 },
    ]);
    expect(getClientRects).toHaveBeenCalledOnce();
  });
});
