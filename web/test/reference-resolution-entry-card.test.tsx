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
          analysisRunId="run-123"
          entry={entry}
          references={[entry]}
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
    expect(screen.getByText("ref1 · journal article · 2024")).toBeTruthy();
    expect(screen.getByText(entry.rawText)).toBeTruthy();
    expect(screen.getByText("Canonical Example Study")).toBeTruthy();
    expect(screen.getByText("Canonical ID canonical-paper-1234")).toBeTruthy();
    const parsedEntryLink = screen.getByRole("link", { name: "View parsed entry" });
    expect(parsedEntryLink.getAttribute("href")).toEqual("#bibliography-ref1");

    fireEvent.click(parsedEntryLink);
    expect(onViewParsedEntry).toHaveBeenCalledWith(expect.anything(), "ref1");
  });

  it("displays generated bibliography keys one-based without changing parsed-entry callbacks", () => {
    const onViewParsedEntry = vi.fn();
    render(
      <ol>
        <ReferenceResolutionEntryCard
          analysisRunId="run-123"
          entry={{ ...entry, entryOrder: 0, localReferenceKey: "b0" }}
          references={[{ ...entry, entryOrder: 0, localReferenceKey: "b0" }, { ...entry, entryOrder: 1, localReferenceKey: "b7" }]}
          anchorId="resolution-b0"
          parsedEntryHref="#bibliography-b0"
          parsedEntryAvailable
          onViewParsedEntry={(event, referenceKey) => {
            event.preventDefault();
            onViewParsedEntry(event, referenceKey);
          }}
        />
      </ol>,
    );

    expect(screen.getByText("b1 · journal article · 2024")).toBeTruthy();
    expect(document.getElementById("resolution-b0")).toBeTruthy();
    expect(screen.getByRole("region", { name: "Original bibliography entry b1" })).toBeTruthy();
    const parsedEntryLink = screen.getByRole("link", { name: "View parsed entry" });
    expect(parsedEntryLink.getAttribute("href")).toBe("#bibliography-b0");
    fireEvent.click(parsedEntryLink);
    expect(onViewParsedEntry).toHaveBeenCalledWith(expect.anything(), "b0");
  });

  it("keeps one-based labels and internal navigation identifiers for legacy ordered keys", () => {
    const onViewParsedEntry = vi.fn();
    const references = [
      { ...entry, entryOrder: 1, localReferenceKey: "b1" },
      { ...entry, entryOrder: 2, localReferenceKey: "b7" },
    ];
    render(
      <ol>
        <ReferenceResolutionEntryCard
          analysisRunId="run-123"
          entry={references[1]}
          references={references}
          anchorId="resolution-b7"
          parsedEntryHref="#bibliography-b7"
          parsedEntryAvailable
          onViewParsedEntry={(event, referenceKey) => {
            event.preventDefault();
            onViewParsedEntry(event, referenceKey);
          }}
        />
      </ol>,
    );

    expect(screen.getByText("b2 · journal article · 2024")).toBeTruthy();
    expect(document.getElementById("resolution-b7")).toBeTruthy();
    const parsedEntryLink = screen.getByRole("link", { name: "View parsed entry" });
    expect(parsedEntryLink.getAttribute("href")).toBe("#bibliography-b7");
    fireEvent.click(parsedEntryLink);
    expect(onViewParsedEntry).toHaveBeenCalledWith(expect.anything(), "b7");
  });
});
