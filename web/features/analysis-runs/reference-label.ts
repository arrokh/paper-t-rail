import type { ParsedDocument, ReferenceResolutionReportResponse } from "@/features/analysis-runs/types";

type ReferenceLabelSource = Pick<ParsedDocument["bibliographyEntries"][number], "entryOrder" | "localReferenceKey">;

const NUMERIC_CITATION_MARKER = /^(?:\d+(?:\s*[,;–—-]\s*\d+)*|\[\s*\d+(?:\s*[,;–—-]\s*\d+)*\s*\]|\(\s*\d+(?:\s*[,;–—-]\s*\d+)*\s*\))$/;

function isNumericCitationMarker(marker: string): boolean {
  return NUMERIC_CITATION_MARKER.test(marker.trim());
}

export function getNumericCitationReferenceKeys(
  parsedDocument: ParsedDocument | null,
  report: ReferenceResolutionReportResponse | null,
): ReadonlySet<string> {
  const keys = new Set<string>();

  for (const context of parsedDocument?.citationContexts ?? []) {
    for (const occurrence of context.occurrences) {
      if (!isNumericCitationMarker(occurrence.markerText)) continue;
      occurrence.bibliographyReferenceKeys.forEach((key) => keys.add(key));
    }
  }

  for (const entry of report?.referenceResolution.entries ?? []) {
    if (entry.verificationOutcomes.some((outcome) => outcome.citationMarkers.some(isNumericCitationMarker))) {
      keys.add(entry.localReferenceKey);
    }
  }

  return keys;
}

export function displayReferenceKey(
  reference: ReferenceLabelSource,
  numericReferenceKeys: ReadonlySet<string>,
): string {
  if (!numericReferenceKeys.has(reference.localReferenceKey) || reference.entryOrder < 0) return reference.localReferenceKey;

  const prefix = /^([bB])\d+$/.exec(reference.localReferenceKey)?.[1];
  return prefix ? `${prefix}${reference.entryOrder + 1}` : reference.localReferenceKey;
}
