import type { PDFPageProxy } from "pdfjs-dist";

export type PdfTextMatch = {
  itemIndexes: number[];
  itemRanges: PdfTextRange[];
  matchedText: string;
};

export type PdfTextRange = {
  itemIndex: number;
  startOffset: number;
  endOffset: number;
};

export type PdfTextMatches = {
  itemIndexes: number[];
  itemRanges: PdfTextRange[];
  matchedTargetCount: number;
  targetCount: number;
  contextMatched: boolean;
};

type PdfTextContent = Awaited<ReturnType<PDFPageProxy["getTextContent"]>>;
type PdfTextItem = Extract<PdfTextContent["items"][number], { str: string }>;
type NormalizedCharacter = { itemIndex: number; startOffset: number; endOffset: number } | null;
type TextSpan = { start: number; end: number };
type MatchingPhrase = TextSpan & { text: string; tokenSpans?: TextSpan[] };

export function findPdfTextMatch(items: readonly PdfTextContent["items"][number][], searchCandidates: readonly string[]): PdfTextMatch | null {
  const textItems = items.filter(isPdfTextItem);
  const { text, characterMap } = buildNormalizedText(textItems);
  if (text.length === 0) return null;

  for (const candidate of searchCandidates) {
    const normalizedCandidate = normalizePdfText(candidate);
    if (normalizedCandidate.length === 0) continue;

    const matchingPhrase = findMatchingPhrase(text, normalizedCandidate);
    if (!matchingPhrase) continue;

    const itemRanges = rangesForMatch(characterMap, matchingPhrase, textItems);
    if (itemRanges.length > 0) {
      return {
        itemIndexes: itemRanges.map((range) => range.itemIndex),
        itemRanges,
        matchedText: matchingPhrase.text,
      };
    }
  }

  return null;
}

export function findPdfTextMatches(
  items: readonly PdfTextContent["items"][number][],
  targets: readonly string[],
  context?: string | null,
): PdfTextMatches {
  const textItems = items.filter(isPdfTextItem);
  const { text, characterMap } = buildNormalizedText(textItems);
  const normalizedTargets = [...new Set(targets.map(normalizePdfText).filter(Boolean))];
  if (text.length === 0 || normalizedTargets.length === 0) {
    return { itemIndexes: [], itemRanges: [], matchedTargetCount: 0, targetCount: normalizedTargets.length, contextMatched: false };
  }

  const normalizedContext = normalizePdfText(context ?? "");
  const contextPhrase = normalizedContext ? findMatchingPhrase(text, normalizedContext) : null;
  const contextMatched = contextPhrase?.text === normalizedContext;
  const scope = contextMatched && contextPhrase ? contextPhrase : null;
  const searchableTargets = normalizedContext && !contextMatched ? normalizedTargets.slice(0, 1) : normalizedTargets;
  const matches = searchableTargets.flatMap((target) => {
    const match = findMatchingPhrase(text, target, scope ?? undefined);
    return match ? [{ target, itemRanges: rangesForMatch(characterMap, match, textItems) }] : [];
  });
  const itemRanges = mergePdfTextRanges(matches.flatMap((match) => match.itemRanges));

  return {
    itemIndexes: [...new Set(itemRanges.map((range) => range.itemIndex))],
    itemRanges,
    matchedTargetCount: matches.filter((match) => match.itemRanges.length > 0).length,
    targetCount: normalizedTargets.length,
    contextMatched,
  };
}

function isPdfTextItem(item: PdfTextContent["items"][number]): item is PdfTextItem {
  return "str" in item;
}

function findMatchingPhrase(
  source: string,
  candidate: string,
  scope?: { start: number; end: number },
): MatchingPhrase | null {
  const startIndex = scope?.start ?? 0;
  const endIndex = scope?.end ?? source.length;
  const exactIndex = findBoundaryMatch(source, candidate, startIndex, endIndex);
  if (exactIndex >= 0) return { start: exactIndex, end: exactIndex + candidate.length, text: candidate };

  const alignedTokens = findOrderedTokenMatch(source, candidate, startIndex, endIndex);
  if (alignedTokens) {
    return {
      start: alignedTokens[0].start,
      end: alignedTokens.at(-1)!.end,
      text: candidate,
      tokenSpans: alignedTokens,
    };
  }

  const words = candidate.split(" ").filter((word) => word.length >= 3);
  for (const phraseLength of [8, 6, 4]) {
    if (words.length < phraseLength) continue;
    for (let start = 0; start <= words.length - phraseLength; start += 1) {
      const phrase = words.slice(start, start + phraseLength).join(" ");
      const index = findBoundaryMatch(source, phrase, startIndex, endIndex);
      if (index >= 0) {
        return { start: index, end: index + phrase.length, text: phrase };
      }
    }
  }

  return null;
}

/**
 * PDF text extraction often places citation callouts between the words of a
 * sentence. Align the complete target as an ordered token sequence, and keep
 * each matched word's source span so those inserted citations stay unmarked.
 */
function findOrderedTokenMatch(source: string, candidate: string, startIndex: number, endIndex: number): TextSpan[] | null {
  const sourceTokens = tokensInRange(source, startIndex, endIndex);
  const candidateTokens = tokensInRange(candidate, 0, candidate.length);
  if (candidateTokens.length < 4 || sourceTokens.length < candidateTokens.length) return null;

  const maximumSpan = candidateTokens.length * 4 + 24;
  const maximumGap = Math.min(72, Math.max(24, Math.ceil(candidateTokens.length * 1.5)));
  let bestMatch: TextSpan[] | null = null;
  let bestSpan = Number.POSITIVE_INFINITY;

  for (let start = 0; start < sourceTokens.length; start += 1) {
    if (sourceTokens[start].text !== candidateTokens[0].text) continue;

    const aligned = [sourceTokens[start]];
    let sourceIndex = start + 1;
    let candidateIndex = 1;

    while (candidateIndex < candidateTokens.length && sourceIndex < sourceTokens.length) {
      const candidateToken = candidateTokens[candidateIndex].text;
      const gapStart = sourceIndex;
      while (sourceIndex < sourceTokens.length && sourceTokens[sourceIndex].text !== candidateToken) {
        sourceIndex += 1;
      }

      if (sourceIndex >= sourceTokens.length || sourceIndex - gapStart > maximumGap) break;
      aligned.push(sourceTokens[sourceIndex]);
      sourceIndex += 1;
      candidateIndex += 1;
    }

    if (candidateIndex !== candidateTokens.length) continue;
    const span = sourceIndex - start;
    if (span > maximumSpan) continue;
    if (span < bestSpan) {
      bestMatch = aligned;
      bestSpan = span;
    }
  }

  return bestMatch?.map(({ start, end }) => ({ start, end })) ?? null;
}

function tokensInRange(text: string, start: number, end: number): Array<TextSpan & { text: string }> {
  const tokens: Array<TextSpan & { text: string }> = [];
  const expression = /[\p{L}\p{N}]+/gu;
  expression.lastIndex = start;
  let match = expression.exec(text);
  while (match && match.index < end) {
    const tokenEnd = match.index + match[0].length;
    if (tokenEnd <= end) tokens.push({ start: match.index, end: tokenEnd, text: match[0] });
    match = expression.exec(text);
  }
  return tokens;
}

function findBoundaryMatch(source: string, candidate: string, startIndex: number, endIndex: number): number {
  let index = source.indexOf(candidate, startIndex);
  while (index >= 0 && index + candidate.length <= endIndex) {
    const previous = source[index - 1];
    const next = source[index + candidate.length];
    if (!isWordCharacter(previous) && !isWordCharacter(next)) return index;
    index = source.indexOf(candidate, index + 1);
  }
  return -1;
}

function isWordCharacter(character: string | undefined): boolean {
  return Boolean(character && /[\p{L}\p{N}]/u.test(character));
}

function rangesForMatch(
  characterMap: readonly NormalizedCharacter[],
  match: MatchingPhrase,
  items: readonly PdfTextItem[],
): PdfTextRange[] {
  const spans = match.tokenSpans ?? [{ start: match.start, end: match.end }];
  const ranges = spans.flatMap((span) => {
    const rangesByItem = new Map<number, { startOffset: number; endOffset: number }>();
    for (const mappedCharacter of characterMap.slice(span.start, span.end)) {
      if (!mappedCharacter) continue;
      const current = rangesByItem.get(mappedCharacter.itemIndex);
      if (current) {
        current.startOffset = Math.min(current.startOffset, mappedCharacter.startOffset);
        current.endOffset = Math.max(current.endOffset, mappedCharacter.endOffset);
      } else {
        rangesByItem.set(mappedCharacter.itemIndex, {
          startOffset: mappedCharacter.startOffset,
          endOffset: mappedCharacter.endOffset,
        });
      }
    }

    return [...rangesByItem.entries()].map(([itemIndex, range]) => ({ itemIndex, ...range }));
  });

  return mergePdfTextRanges(ranges.map((range) => {
      const { itemIndex } = range;
      const sourceText = items[itemIndex]?.str ?? "";
      while (range.startOffset > 0 && !/[\p{L}\p{N}\s]/u.test(sourceText[range.startOffset - 1] ?? "")) range.startOffset -= 1;
      while (range.endOffset < sourceText.length && !/[\p{L}\p{N}\s]/u.test(sourceText[range.endOffset] ?? "")) range.endOffset += 1;
      return range;
    }), items);
}

function mergePdfTextRanges(ranges: readonly PdfTextRange[], items?: readonly PdfTextItem[]): PdfTextRange[] {
  const sortedRanges = [...ranges].sort((first, second) =>
    first.itemIndex - second.itemIndex || first.startOffset - second.startOffset,
  );
  const merged: PdfTextRange[] = [];
  for (const range of sortedRanges) {
    const previous = merged.at(-1);
    const sameTextItem = previous?.itemIndex === range.itemIndex;
    const gap = sameTextItem ? items?.[range.itemIndex]?.str.slice(previous.endOffset, range.startOffset) : undefined;
    const canMergeAcrossGap = gap !== undefined && !/[\p{L}\p{N}]/u.test(gap);
    if (sameTextItem && (range.startOffset <= previous.endOffset || canMergeAcrossGap)) {
      previous.endOffset = Math.max(previous.endOffset, range.endOffset);
    } else {
      merged.push({ ...range });
    }
  }
  return merged;
}

function buildNormalizedText(items: readonly PdfTextItem[]): { text: string; characterMap: NormalizedCharacter[] } {
  const characters: string[] = [];
  const characterMap: NormalizedCharacter[] = [];
  let previousItemEndedWithHyphen = false;

  for (const [itemIndex, item] of items.entries()) {
    if (characters.length > 0 && !previousItemEndedWithHyphen) appendSpace(characters, characterMap);

    const itemText = item.str;
    const hasLineEndHyphen = item.hasEOL && /[-\u00ad]$/u.test(item.str);
    const searchableText = hasLineEndHyphen ? itemText.slice(0, -1) : itemText;

    for (let index = 0; index < searchableText.length;) {
      const codePoint = searchableText.codePointAt(index);
      if (codePoint === undefined) break;
      const character = String.fromCodePoint(codePoint);
      const normalizedCharacter = character
        .normalize("NFKD")
        .replace(/\p{M}/gu, "")
        .toLocaleLowerCase();

      for (const normalizedPart of normalizedCharacter) {
        if (/[\p{L}\p{N}]/u.test(normalizedPart)) {
          characters.push(normalizedPart);
          characterMap.push({ itemIndex, startOffset: index, endOffset: index + character.length });
        } else {
          appendSpace(characters, characterMap);
        }
      }
      index += character.length;
    }

    previousItemEndedWithHyphen = hasLineEndHyphen;
  }

  while (characters.at(-1) === " ") {
    characters.pop();
    characterMap.pop();
  }

  return { text: characters.join(""), characterMap };
}

function appendSpace(characters: string[], characterMap: NormalizedCharacter[]) {
  if (characters.length === 0 || characters.at(-1) === " ") return;
  characters.push(" ");
  characterMap.push(null);
}

function normalizePdfText(value: string) {
  const characters: string[] = [];

  for (const character of value.normalize("NFKD").replace(/\p{M}/gu, "").toLocaleLowerCase()) {
    characters.push(/[\p{L}\p{N}]/u.test(character) ? character : " ");
  }

  return characters.join("").replace(/\s+/gu, " ").trim();
}
