import { describe, expect, it } from "vitest";
import { findPdfTextMatch } from "@/features/analysis-runs/components/pdf-text-search";

describe("PDF text search", () => {
  it("matches a citation passage split across PDF text runs", () => {
    const result = findPdfTextMatch([
      textItem("The intervention improved the measured", true),
      textItem("outcome [1].", true),
    ], ["The intervention improved the measured outcome [1]."]);

    expect(result).toEqual({ itemIndexes: [0, 1], matchedText: "the intervention improved the measured outcome 1" });
  });

  it("joins words broken by a line-ending hyphen when matching a bibliography title", () => {
    const result = findPdfTextMatch([
      textItem("A study of out-", true),
      textItem("comes.", true),
    ], ["A study of outcomes"]);

    expect(result?.itemIndexes).toEqual([0, 1]);
  });

  it("tries the next candidate when the preferred text is absent", () => {
    const result = findPdfTextMatch([textItem("A study of outcomes", true)], ["The exact article title is missing", "A study of outcomes"]);

    expect(result?.itemIndexes).toEqual([0]);
  });
});

function textItem(str: string, hasEOL: boolean) {
  return { str, dir: "ltr", transform: [12, 0, 0, 12, 72, 720], width: 300, height: 12, fontName: "font", hasEOL };
}
