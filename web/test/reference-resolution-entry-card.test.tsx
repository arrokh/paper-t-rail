import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ReferenceResolutionEntryCard } from "@/features/reference-resolution/components/reference-resolution-entry-card";
import type { ReferenceResolutionReportResponse } from "@/features/analysis-runs/types";

type Entry = ReferenceResolutionReportResponse["referenceResolution"]["entries"][number];

const entry: Entry = {
  entryOrder: 1,
  localReferenceKey: "ref1",
  rawText: "Example study. Riley Example. 2024. DOI 10.1234/example.",
  title: "Example study",
  authors: ["Riley Example"],
  year: 2024,
  doi: "10.1234/example",
  referenceType: "JOURNAL_ARTICLE",
  status: "RESOLVED",
  reasonCode: null,
  canonicalPaper: {
    id: "canonical-paper-1234",
    doi: "10.1234/example",
    title: "Canonical Example Study",
    authors: ["Riley Example"],
    year: 2024,
  },
  confidenceScore: 1,
  matchMethod: "DOI",
  citedPaperAccess: null,
  verificationOutcomes: [],
};

describe("Reference Resolution Entry Card", () => {
  it("keeps the source citation linked to the resolved Canonical Paper and parsed entry", () => {
    const onViewParsedEntry = vi.fn();
    render(
      <ol>
        <ReferenceResolutionEntryCard
          entry={entry}
          anchorId="resolution-ref1"
          parsedEntryHref="#bibliography-ref1"
          parsedEntryAvailable
          onViewParsedEntry={(event, referenceKey) => {
            event.preventDefault();
            onViewParsedEntry(event, referenceKey);
          }}
        />
      </ol>,
    );

    expect(screen.getByText("Example study")).toBeTruthy();
    expect(screen.getByText(entry.rawText)).toBeTruthy();
    expect(screen.getByText("Canonical Example Study")).toBeTruthy();
    expect(screen.getByText("Canonical ID canonical-paper-1234")).toBeTruthy();
    const parsedEntryLink = screen.getByRole("link", { name: "View parsed entry" });
    expect(parsedEntryLink.getAttribute("href")).toEqual("#bibliography-ref1");

    fireEvent.click(parsedEntryLink);
    expect(onViewParsedEntry).toHaveBeenCalledWith(expect.anything(), "ref1");
  });
});
