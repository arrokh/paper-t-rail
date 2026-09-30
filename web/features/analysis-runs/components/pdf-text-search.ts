import type { PDFPageProxy } from "pdfjs-dist";

export type PdfTextMatch = {
  itemIndexes: number[];
  matchedText: string;
};

type PdfTextContent = Awaited<ReturnType<PDFPageProxy["getTextContent"]>>;
type PdfTextItem = Extract<PdfTextContent["items"][number], { str: string }>;
type NormalizedCharacter = { itemIndex: number } | null;

export function findPdfTextMatch(items: readonly PdfTextContent["items"][number][], searchCandidates: readonly string[]): PdfTextMatch | null {
  const textItems = items.filter(isPdfTextItem);
  const { text, characterMap } = buildNormalizedText(textItems);
  if (text.length === 0) return null;

  for (const candidate of searchCandidates) {
    const normalizedCandidate = normalizePdfText(candidate);
    if (normalizedCandidate.length === 0) continue;

    const matchingPhrase = findMatchingPhrase(text, normalizedCandidate);
    if (!matchingPhrase) continue;

    const itemIndexes = [...new Set(characterMap
      .slice(matchingPhrase.start, matchingPhrase.end)
      .flatMap((mappedCharacter) => mappedCharacter === null ? [] : [mappedCharacter.itemIndex]))];
    if (itemIndexes.length > 0) return { itemIndexes, matchedText: matchingPhrase.text };
  }

  return null;
}

function isPdfTextItem(item: PdfTextContent["items"][number]): item is PdfTextItem {
  return "str" in item;
}

function findMatchingPhrase(source: string, candidate: string): { start: number; end: number; text: string } | null {
  const exactIndex = source.indexOf(candidate);
  if (exactIndex >= 0) return { start: exactIndex, end: exactIndex + candidate.length, text: candidate };

  const words = candidate.split(" ").filter((word) => word.length >= 3);
  for (const phraseLength of [8, 6, 4]) {
    if (words.length < phraseLength) continue;
    for (let start = 0; start <= words.length - phraseLength; start += 1) {
      const phrase = words.slice(start, start + phraseLength).join(" ");
      const index = source.indexOf(phrase);
      if (index >= 0) return { start: index, end: index + phrase.length, text: phrase };
    }
  }

  return null;
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
          characterMap.push({ itemIndex });
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
