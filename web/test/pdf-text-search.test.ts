import { describe, expect, it } from "vitest";
import { findPdfTextMatch, findPdfTextMatches } from "@/features/analysis-runs/components/pdf-text-search";

describe("PDF text search", () => {
  it("matches a citation passage split across PDF text runs", () => {
    const result = findPdfTextMatch([
      textItem("The intervention improved the measured", true),
      textItem("outcome [1].", true),
    ], ["The intervention improved the measured outcome [1]."]);

    expect(result).toMatchObject({ itemIndexes: [0, 1], matchedText: "the intervention improved the measured outcome 1" });
  });

  it("highlights the selected Atomic Claim and its citation marker inside the matching context", () => {
    const sourceText = "The intervention improved the measured outcome [1].";
    const result = findPdfTextMatches([textItem(sourceText, true)], ["The intervention improved the measured outcome.", "[1]"], sourceText);

    expect(result).toMatchObject({ matchedTargetCount: 2, targetCount: 2, contextMatched: true });
    expect(result.itemRanges.map(({ startOffset, endOffset }) => sourceText.slice(startOffset, endOffset))).toEqual([
      "The intervention improved the measured outcome",
      "[1].",
    ]);
  });

  it("aligns a complete Atomic Claim around citation callouts inserted into the PDF sentence", () => {
    const items = [
      textItem("Combining LLMs and Symbolic Planners.", true),
      textItem("A large body of recent work has highlighted the", false),
      textItem("shortcomings of LLMs on long-horizon planning problems", false),
      textItem("(Valmeekam et al., 2023; 2024; Pallagani et al., 2023; Momennejad et al., 2024; Hirsch et al., 2024; Zheng et al., 2024; Aghzal et al., 2023),", false),
      textItem("persisting across popular prompting techniques like Chain-of-Thought", false),
      textItem("(Wei et al., 2022), ReAct (Yao et al., 2022), and Reflexion", false),
      textItem("(Shinn et al., 2024).", true),
    ];
    const claim = "A large body of recent work has highlighted the shortcomings of LLMs on long-horizon planning problems, persisting across popular prompting techniques like Chain-of-Thought, ReAct, and Reflexion.";
    const context = "A large body of recent work has highlighted the shortcomings of LLMs on long-horizon planning problems (Valmeekam et al., 2023; 2024; Pallagani et al., 2023; Momennejad et al., 2024; Hirsch et al., 2024; Zheng et al., 2024; Aghzal et al., 2023), persisting across popular prompting techniques like Chain-of-Thought (Wei et al., 2022), ReAct (Yao et al., 2022), and Reflexion (Shinn et al., 2024).";
    const result = findPdfTextMatches(items, [claim, "Aghzal et al., 2023"], context);
    const highlightedText = result.itemRanges
      .map(({ itemIndex, startOffset, endOffset }) => items[itemIndex].str.slice(startOffset, endOffset))
      .join(" ");

    expect(result).toMatchObject({ matchedTargetCount: 2, targetCount: 2, contextMatched: true });
    expect(highlightedText).toContain("A large body of recent work has highlighted the");
    expect(highlightedText).toContain("shortcomings of LLMs on long-horizon planning problems");
    expect(highlightedText).toContain("persisting across popular prompting techniques like Chain-of-Thought");
    expect(highlightedText).toContain("ReAct");
    expect(highlightedText).toContain("Reflexion");
    expect(highlightedText).toContain("Aghzal et al., 2023");
    expect(highlightedText).not.toContain("Valmeekam");
    expect(highlightedText).not.toContain("Wei et al.");
  });

  it("does not resolve a repeated citation marker outside the selected citation context", () => {
    const items = [textItem("Other claim [1]. The intervention improved the measured outcome [1].", true)];
    const context = "The intervention improved the measured outcome [1].";
    const result = findPdfTextMatches(items, ["The intervention improved the measured outcome.", "[1]"], context);

    expect(result.contextMatched).toBe(true);
    expect(result.itemRanges.map(({ startOffset, endOffset }) => items[0].str.slice(startOffset, endOffset))).toEqual([
      "The intervention improved the measured outcome",
      "[1].",
    ]);
  });

  it("does not highlight a citation marker when its selected context cannot be located", () => {
    const items = [textItem("A different sentence cites [1]. The intervention improved the measured outcome [2].", true)];
    const result = findPdfTextMatches(
      items,
      ["The intervention improved the measured outcome.", "[1]"],
      "The intervention improved the measured outcome [1].",
    );

    expect(result).toMatchObject({ matchedTargetCount: 1, targetCount: 2, contextMatched: false });
    expect(result.itemRanges.map(({ startOffset, endOffset }) => items[0].str.slice(startOffset, endOffset))).toEqual([
      "The intervention improved the measured outcome",
    ]);
  });

  it("does not match a citation number as a substring of another marker", () => {
    const result = findPdfTextMatches([textItem("The intervention improved the measured outcome [10].", true)], ["[1]"]);

    expect(result.matchedTargetCount).toBe(0);
    expect(result.itemRanges).toEqual([]);
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
