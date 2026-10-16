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
  matchMethod: "CONFIRMED_DOI",
  accessProgressStatus: null,
  accessProgressReason: null,
  sourceTextContent: null,
  sourceElement: "biblStruct",
  sourceLocalReferenceKey: "ref1",
  localReferenceKeyOrigin: "GROBID_XML_ID",
  identifiers: [{ sourceElement: "idno", type: "DOI", rawValue: "10.1234/example", normalizedValue: "10.1234/example" }],
  sourceLocations: [{ page: 2, coordinates: "2,10,20,30,40" }],
  provisionalArtifactSignals: [],
  extractionLimitations: [],
  provenanceCaptureStatus: "CAPTURED",
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
    expect(screen.queryByText("1.000")).toBeNull();
    const parsedEntryLink = screen.getByRole("link", { name: "View parsed entry" });
    expect(parsedEntryLink.getAttribute("href")).toEqual("#bibliography-ref1");

    fireEvent.click(parsedEntryLink);
    expect(onViewParsedEntry).toHaveBeenCalledWith(expect.anything(), "ref1");
  });

  it("shows bounded candidates as unconfirmed evidence with uncalibrated ranking scores", () => {
    const candidateEntry = {
      ...entry,
      status: "UNRESOLVED",
      reasonCode: "TITLE_CONFLICT",
      canonicalPaper: null,
      candidateEvidence: [{
        doi: "10.1234/unrelated",
        title: "A different study",
        authors: ["Riley Example"],
        year: 2024,
        rankingScore: 0.812,
        reasonCodes: ["TITLE_CONFLICT", "AUTHOR_SET_MATCH", "YEAR_MATCH"],
      }],
    } satisfies Entry;

    render(
      <ol>
        <ReferenceResolutionEntryCard
          analysisRunId="run-123"
          entry={candidateEntry}
          references={[candidateEntry]}
          anchorId="resolution-ref1"
          parsedEntryHref="#bibliography-ref1"
          parsedEntryAvailable={false}
          onViewParsedEntry={vi.fn()}
        />
      </ol>,
    );

    const evidenceRegion = screen.getByRole("region", { name: "Unconfirmed candidate evidence for ref1" });
    expect(evidenceRegion.textContent).toContain("A different study");
    expect(evidenceRegion.textContent).toContain("Ranking score 0.812");
    expect(evidenceRegion.textContent).toContain("Provider results are candidates, not confirmed identities");
    expect(evidenceRegion.textContent).toContain("title conflict");
  });

  it("labels candidate comparisons as part of a resolved identity decision", () => {
    const resolvedEntry = {
      ...entry,
      candidateEvidence: [{
        doi: "10.1234/example",
        title: "Example study",
        authors: ["Riley Example"],
        year: 2024,
        rankingScore: null,
        reasonCodes: ["TITLE_EXACT", "AUTHOR_SET_MATCH", "YEAR_MATCH", "DOI_CONFIRMED"],
      }],
    } satisfies Entry;

    render(
      <ol>
        <ReferenceResolutionEntryCard
          analysisRunId="run-123"
          entry={resolvedEntry}
          references={[resolvedEntry]}
          anchorId="resolution-ref1"
          parsedEntryHref="#bibliography-ref1"
          parsedEntryAvailable={false}
          onViewParsedEntry={vi.fn()}
        />
      </ol>,
    );

    const evidenceRegion = screen.getByRole("region", { name: "Candidate comparison evidence for ref1" });
    expect(evidenceRegion.textContent).toContain("matching policy selected the shown identity");
    expect(evidenceRegion.textContent).not.toContain("candidates, not confirmed identities");
  });

  it("shows persisted provider-chain outcome, actual metadata origin, and bounded wire attempts", () => {
    const chainEntry = {
      ...entry,
      resolutionOutcome: "RESOLVED",
      metadataOriginProviderId: "datacite",
      providerLookups: [
        { providerId: "crossref", lookupOrdinal: 1, source: "WIRE", outcomeCode: "NOT_FOUND" },
        { providerId: "datacite", lookupOrdinal: 2, source: "WIRE", outcomeCode: "FOUND" },
      ],
      providerAttempts: [
        { providerId: "crossref", attemptOrdinal: 1, state: "COMPLETED", outcomeCode: "NOT_FOUND", reservedAt: "2025-01-01T00:00:00Z", startedAt: "2025-01-01T00:00:00.010Z", completedAt: "2025-01-01T00:00:00.130Z", unknownAt: null, httpStatus: 404, durationMillis: 120, responseBytes: 48, nextAttemptNotBefore: null },
        { providerId: "datacite", attemptOrdinal: 2, state: "COMPLETED", outcomeCode: "FOUND", reservedAt: "2025-01-01T00:00:01Z", startedAt: "2025-01-01T00:00:01.010Z", completedAt: "2025-01-01T00:00:01.250Z", unknownAt: null, httpStatus: 200, durationMillis: 240, responseBytes: 512, nextAttemptNotBefore: null },
      ],
    } satisfies Entry;
    render(
      <ol>
        <ReferenceResolutionEntryCard
          analysisRunId="run-123"
          entry={chainEntry}
          references={[chainEntry]}
          anchorId="resolution-ref1"
          parsedEntryHref="#bibliography-ref1"
          parsedEntryAvailable={false}
          onViewParsedEntry={vi.fn()}
        />
      </ol>,
    );

    const historyTrigger = screen.getByRole("button", { name: "Provider resolution history" });
    expect(historyTrigger.getAttribute("aria-expanded")).toBe("false");
    fireEvent.click(historyTrigger);
    expect(historyTrigger.getAttribute("aria-expanded")).toBe("true");
    const historyText = historyTrigger.parentElement?.textContent ?? "";
    expect(historyText).toContain("Provider-chain outcomeresolved");
    expect(historyText).toContain("Metadata selected by the matcherdatacite");
    expect(historyText).toContain("crossref · lookup 1 · wire · not found");
    expect(historyText).toContain("datacite · attempt 2 · completed · found · HTTP 200 · 240 ms · 512 response bytes · reserved 2025-01-01T00:00:01Z · started 2025-01-01T00:00:01.010Z · completed 2025-01-01T00:00:01.250Z");
    expect(historyText).toContain("cached lookups are not wire attempts");
    expect(historyText).not.toContain(entry.rawText);
  });

  it("explains an unconfirmed DOI without implying that the DOI or work does not exist", () => {
    const unconfirmedEntry = {
      ...entry,
      status: "UNRESOLVED",
      reasonCode: "DOI_NOT_FOUND",
      canonicalPaper: null,
      resolutionOutcome: "NO_CANDIDATE_OR_DOI_UNCONFIRMED",
      metadataOriginProviderId: null,
    } satisfies Entry;

    render(
      <ol>
        <ReferenceResolutionEntryCard
          analysisRunId="run-123"
          entry={unconfirmedEntry}
          references={[unconfirmedEntry]}
          anchorId="resolution-ref1"
          parsedEntryHref="#bibliography-ref1"
          parsedEntryAvailable={false}
          onViewParsedEntry={vi.fn()}
        />
      </ol>,
    );

    expect(screen.getByText("No public metadata confirmation was found. This does not prove the DOI or work does not exist.")).toBeTruthy();
    expect(screen.queryByText("doi not found")).toBeNull();
  });

  it("shows UNKNOWN when a rolling-compatible response has no persisted provider-chain outcome", () => {
    render(
      <ol>
        <ReferenceResolutionEntryCard
          analysisRunId="run-123"
          entry={entry}
          references={[entry]}
          anchorId="resolution-ref1"
          parsedEntryHref="#bibliography-ref1"
          parsedEntryAvailable={false}
          onViewParsedEntry={vi.fn()}
        />
      </ol>,
    );

    const historyTrigger = screen.getByRole("button", { name: "Provider resolution history" });
    fireEvent.click(historyTrigger);
    expect(historyTrigger.getAttribute("aria-expanded")).toBe("true");
    expect(historyTrigger.parentElement?.textContent ?? "").toContain("Provider-chain outcomeUNKNOWN");
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

  it("shows extraction signals as provisional and exposes retained source identifiers", () => {
    const suspiciousEntry = {
      ...entry,
      rawText: "",
      sourceTextContent: "Raw TEI text",
      provisionalArtifactSignals: ["EMPTY_GROBID_BIBLIOGRAPHY_TEXT"],
    };
    render(
      <ol>
        <ReferenceResolutionEntryCard
          analysisRunId="run-123"
          entry={suspiciousEntry}
          references={[suspiciousEntry]}
          anchorId="resolution-ref1"
          parsedEntryHref="#bibliography-ref1"
          parsedEntryAvailable={false}
          onViewParsedEntry={vi.fn()}
        />
      </ol>,
    );

    expect(screen.getByText("Provisional extraction signal")).toBeTruthy();
    expect(screen.getByText(/This screening signal is not human adjudication/)).toBeTruthy();
    fireEvent.click(screen.getByText("Preserved source identifiers and extraction provenance"));
    expect(screen.getByText(/Unnormalized GROBID text content: Raw TEI text/)).toBeTruthy();
    const provenanceDetails = screen.getByText("Preserved source identifiers and extraction provenance").closest("details");
    expect(provenanceDetails?.textContent).toContain("10.1234/example");
    expect(provenanceDetails?.textContent).toContain("Source coordinates: 2,10,20,30,40");
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
