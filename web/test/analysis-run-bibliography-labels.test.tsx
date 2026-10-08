import { fireEvent, render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AnalysisRunStageResults } from "@/features/analysis-runs/components/analysis-run-stage-results";
import { AnalysisRunsWorkspace } from "@/features/analysis-runs/components/analysis-runs-workspace";
import type { AnalysisRun, ParsedDocument, ReferenceResolutionReportResponse } from "@/features/analysis-runs/types";

const queryMocks = vi.hoisted(() => ({
  useRecentAnalysisRuns: vi.fn(),
  useParsedDocument: vi.fn(),
  useReferenceResolutionReport: vi.fn(),
  useReanalyzeDocument: vi.fn(),
  useDeleteSourceDocument: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams(window.location.search) }));
vi.mock("@/features/providers/provider-configuration-context", () => ({
  useProviderConfiguration: () => ({ configurationReady: false, createConfiguration: vi.fn() }),
}));
vi.mock("@/features/analysis-runs/queries/analysis-run-queries", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/features/analysis-runs/queries/analysis-run-queries")>();
  return { ...actual, ...queryMocks };
});

const run: AnalysisRun = {
  id: "run-bibliography-labels",
  documentId: "source-document",
  filename: "source.pdf",
  sourceContentSha256: "a".repeat(64),
  status: "COMPLETED",
  progress: {},
  pipeline: null,
  configuration: {
    claimExtractor: { provider: "heuristic", version: "v1" },
    embedding: { provider: "local", version: "v1" },
    retrieval: { profileId: "test", vectorCandidateLimit: 10, lexicalCandidateLimit: 10, finalCandidateLimit: 5, reciprocalRankFusionConstant: 60, embeddingProfileHash: "b".repeat(64) },
    systemOne: { provider: "mock", version: "v1" },
    sourceParser: { provider: "grobid", version: "v1" },
    languageDetector: { provider: "local", version: "v1" },
    referenceResolution: { executionStatus: "PENDING", provider: null, scorePolicyVersion: null, confidenceThreshold: null },
    aggregation: { executionStatus: "PENDING", verificationPolicyVersion: null, aggregationPolicyVersion: null, thresholds: null },
  },
  createdAt: "2026-01-01T00:00:00Z",
  startedAt: null,
  failureReason: null,
};

const parsedDocument: ParsedDocument = {
  parser: { provider: "grobid", version: "v1" },
  sourceContentSha256: run.sourceContentSha256,
  normalizedSourceText: "A claim is discussed (Author, 2024).",
  sections: [],
  citationContexts: [{
    id: "context-1",
    sectionId: "section-1",
    boundaryKind: "SENTENCE_FALLBACK",
    text: "A claim is discussed (Author, 2024).",
    startOffset: 0,
    endOffset: 38,
    occurrences: [{ id: "occurrence-1", markerText: "(Author, 2024)", startOffset: 25, endOffset: 37, bibliographyReferenceKeys: ["b0"], unmatchedBibliographyReferenceKeys: null }],
    atomicClaims: [{
      id: "claim-1",
      text: "A claim is discussed.",
      sourceStartOffset: 0,
      sourceEndOffset: 22,
      citationTargets: [{ id: "target-1", markerText: "(Author, 2024)", bibliographyReferenceKey: "b0", bibliographyTitle: null, associationKind: "INFERRED_PROVISIONAL" }],
    }],
  }],
  bibliographyEntries: [{
    entryOrder: 0,
    localReferenceKey: "b0",
    rawText: "Author. 2024. A study.",
    title: null,
    authors: ["Author"],
    year: 2024,
    doi: null,
    referenceType: "JOURNAL_ARTICLE",
    resolutionStatus: "RESOLVED",
    sourceTextContent: null,
    sourceElement: null,
    sourceLocalReferenceKey: null,
    localReferenceKeyOrigin: "UNKNOWN",
    identifiers: [],
    sourceLocations: [],
    provisionalArtifactSignals: [],
    extractionLimitations: ["BIBLIOGRAPHY_PROVENANCE_UNAVAILABLE"],
    provenanceCaptureStatus: "UNAVAILABLE",
  }],
  bibliographyNormalizationPolicy: null,
};

const referenceReport = {
  analysisRunId: run.id,
  runStatus: "COMPLETED",
  evidenceCoverage: {
    executionStatus: "COMPLETED",
    verificationPolicyVersion: null,
    aggregationPolicyVersion: null,
    thresholds: null,
    summary: {
      totalVerifications: 0,
      completedVerifications: 0,
      incompleteVerifications: 0,
      evidenceConflicts: 0,
      supported: 0,
      partiallySupported: 0,
      contradicted: 0,
      insufficientEvidence: 0,
      inaccessible: 0,
      unresolved: 0,
      unsupportedReferenceType: 0,
    },
    triageDisclaimer: "Research triage only.",
  },
  referenceResolution: {
    executionStatus: "COMPLETED",
    scorePolicyVersion: null,
    confidenceThreshold: null,
    summary: { total: 1, resolved: 1, unresolved: 0, unsupportedReferenceType: 0, notAttempted: 0, failed: 0 },
    entries: [{
      entryOrder: 0,
      localReferenceKey: "b0",
      rawText: "Author. 2024. A study.",
      title: "A study",
      authors: ["Author"],
      year: 2024,
      doi: null,
      referenceType: "JOURNAL_ARTICLE",
      status: "RESOLVED",
      reasonCode: null,
      canonicalPaper: null,
      confidenceScore: null,
      matchMethod: null,
      accessProgressStatus: null,
      accessProgressReason: null,
      citedPaperAccess: null,
      verificationOutcomes: [],
    }],
  },
} as unknown as ReferenceResolutionReportResponse;

function renderStage(
  selectedStage: "source" | "references" | "verification",
  selectedParsedDocument = parsedDocument,
  selectedReport = referenceReport,
) {
  return render(
    <AnalysisRunStageResults
      run={run}
      selectedStage={selectedStage}
      backHref="/analysis-runs"
      parsedDocument={selectedParsedDocument}
      report={selectedReport}
      parsedLoading={false}
      reportLoading={false}
      parsedError={null}
      reportError={null}
      onSelectStage={vi.fn()}
    />,
  );
}

function configureWorkspaceQueries(selectedParsedDocument = parsedDocument) {
  queryMocks.useRecentAnalysisRuns.mockReturnValue({
    data: { items: [run], nextCursor: null, previousCursor: null },
    isPending: false,
    error: null,
  });
  queryMocks.useParsedDocument.mockReturnValue({ data: selectedParsedDocument, isPending: false, isError: false, error: null });
  queryMocks.useReferenceResolutionReport.mockReturnValue({ data: null, isPending: false, isError: false, error: null });
  queryMocks.useReanalyzeDocument.mockReturnValue({ isPending: false, error: null, mutate: vi.fn() });
  queryMocks.useDeleteSourceDocument.mockReturnValue({ isPending: false, error: null, mutate: vi.fn(), reset: vi.fn() });
}

type ReferenceSpec = { localReferenceKey: string; entryOrder: number };

function withReferenceCollection(references: ReferenceSpec[]): { parsedDocument: ParsedDocument; report: ReferenceResolutionReportResponse } {
  const sourceEntry = parsedDocument.bibliographyEntries[0];
  const bibliographyEntries = references.map((reference, index) => ({
    ...sourceEntry,
    ...reference,
    rawText: `Author. 2024. Study ${index + 1}.`,
    title: `Study ${index + 1}`,
  }));
  const context = parsedDocument.citationContexts[0];
  const selectedParsedDocument: ParsedDocument = {
    ...parsedDocument,
    citationContexts: [{
      ...context,
      occurrences: references.map((reference, index) => ({
        ...context.occurrences[0],
        id: `occurrence-${index + 1}`,
        markerText: `[${index + 1}]`,
        bibliographyReferenceKeys: [reference.localReferenceKey],
      })),
      atomicClaims: [{
        ...context.atomicClaims[0],
        citationTargets: references.map((reference, index) => ({
          id: `target-${index + 1}`,
          markerText: `[${index + 1}]`,
          bibliographyReferenceKey: reference.localReferenceKey,
          bibliographyTitle: null,
          associationKind: "INFERRED_PROVISIONAL" as const,
        })),
      }],
    }],
    bibliographyEntries,
  };
  const reportEntry = referenceReport.referenceResolution.entries[0];
  const selectedReport: ReferenceResolutionReportResponse = {
    ...referenceReport,
    referenceResolution: {
      ...referenceReport.referenceResolution,
      entries: references.map((reference, index) => ({
        ...reportEntry,
        ...reference,
        rawText: `Author. 2024. Study ${index + 1}.`,
        title: `Study ${index + 1}`,
        verificationOutcomes: [],
      })),
    },
  };

  return { parsedDocument: selectedParsedDocument, report: selectedReport };
}

afterEach(() => {
  vi.clearAllMocks();
  window.history.replaceState(null, "", "/");
});

describe("Analysis Run stage results loading", () => {
  it("shows accessible skeleton placeholders while persisted results are loading", () => {
    render(
      <AnalysisRunStageResults
        run={run}
        selectedStage="source"
        backHref="/analysis-runs"
        parsedDocument={null}
        report={null}
        parsedLoading={true}
        reportLoading={false}
        parsedError={null}
        reportError={null}
        onSelectStage={vi.fn()}
      />,
    );

    const status = screen.getByRole("status", { name: "Loading Read the PDF results" });
    expect(status.textContent).toContain("Loading Read the PDF results…");
    expect(status.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(0);
  });
});

describe("Analysis Run bibliography display keys", () => {
  it("explains unresolved matches by their recorded cause and labels ranking scores as uncalibrated", () => {
    const sourceEntry = referenceReport.referenceResolution.entries[0];
    const unresolvedReport = {
      ...referenceReport,
      referenceResolution: {
        ...referenceReport.referenceResolution,
        summary: { ...referenceReport.referenceResolution.summary, resolved: 0, unresolved: 1 },
        entries: [{ ...sourceEntry, status: "UNRESOLVED", reasonCode: "TITLE_CONFLICT", confidenceScore: 0.91 }],
      },
    } as unknown as ReferenceResolutionReportResponse;

    renderStage("references", parsedDocument, unresolvedReport);

    fireEvent.click(screen.getByRole("button", { name: "Explain Unresolved" }));
    expect(screen.getByText("The run did not confirm a Canonical Paper; inspect the decision reason for the specific cause.")).toBeTruthy();
    expect(screen.getByText("Similarity score (uncalibrated)")).toBeTruthy();
    expect(screen.getByText("0.910")).toBeTruthy();
    expect(screen.getByText("title conflict")).toBeTruthy();
  });

  it("does not display DOI confirmation as a similarity score", () => {
    const sourceEntry = referenceReport.referenceResolution.entries[0];
    const confirmedDoiReport = {
      ...referenceReport,
      referenceResolution: {
        ...referenceReport.referenceResolution,
        entries: [{
          ...sourceEntry,
          status: "RESOLVED",
          reasonCode: "DOI_CONFIRMED",
          matchMethod: "CONFIRMED_DOI",
          confidenceScore: 1,
          canonicalPaper: {
            id: "paper-id",
            doi: "10.1234/example",
            title: "A study",
            authors: ["Author"],
            year: 2024,
          },
        }],
      },
    } as unknown as ReferenceResolutionReportResponse;

    renderStage("references", parsedDocument, confirmedDoiReport);

    expect(screen.queryByText("Similarity score (uncalibrated)")).toBeNull();
    expect(screen.queryByText("1.000")).toBeNull();
    expect(screen.getByText("doi confirmed")).toBeTruthy();
  });

  it("shows persisted stage outcomes instead of startup PENDING values from the run snapshot", () => {
    const references = renderStage("references");
    const referenceConfiguration = screen.getByRole("region", { name: /Resolve references configuration and persisted progress/ });
    expect(within(referenceConfiguration).getByText("Execution status").nextElementSibling?.textContent).toBe("COMPLETED");
    expect(within(referenceConfiguration).queryByText("PENDING")).toBeNull();
    references.unmount();

    renderStage("verification");
    const verificationConfiguration = screen.getByRole("region", { name: /Assess evidence configuration and persisted progress/ });
    expect(within(verificationConfiguration).getByText("Aggregation status").nextElementSibling?.textContent).toBe("COMPLETED");
    expect(within(verificationConfiguration).queryByText("PENDING")).toBeNull();
  });

  it.each([
    {
      convention: "current zero-based order with nonconsecutive TEI IDs",
      references: [{ localReferenceKey: "b0", entryOrder: 0 }, { localReferenceKey: "b7", entryOrder: 1 }],
      labels: ["b1", "b2"],
    },
    {
      convention: "legacy one-based order with nonconsecutive TEI IDs",
      references: [{ localReferenceKey: "b1", entryOrder: 1 }, { localReferenceKey: "b7", entryOrder: 2 }],
      labels: ["b1", "b2"],
    },
    {
      convention: "fresh GROBID keys with zero-based order",
      references: [{ localReferenceKey: "b1", entryOrder: 0 }, { localReferenceKey: "b7", entryOrder: 1 }],
      labels: ["b1", "b2"],
    },
    {
      convention: "zero-based IDs with consecutive suffixes",
      references: [{ localReferenceKey: "b0", entryOrder: 0 }, { localReferenceKey: "b1", entryOrder: 1 }],
      labels: ["b1", "b2"],
    },
  ])("uses the complete collection for $convention labels without changing workspace anchors", ({ references, labels }) => {
    const data = withReferenceCollection(references);
    const sourceResults = renderStage("source", data.parsedDocument, data.report);
    labels.forEach((label, index) => {
      expect(screen.getByText(`${label} · journal article`)).toBeTruthy();
      expect(screen.getByText(`[${index + 1}] · ${label} · inferred`)).toBeTruthy();
    });
    sourceResults.unmount();

    const referenceResults = renderStage("references", data.parsedDocument, data.report);
    labels.forEach((label) => expect(screen.getByText(`${label} · 2024`)).toBeTruthy());
    referenceResults.unmount();

    configureWorkspaceQueries(data.parsedDocument);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <AnalysisRunsWorkspace initialSelectedRunId={run.id} />
      </QueryClientProvider>,
    );
    fireEvent.click(screen.getByRole("tab", { name: /Parsed Document/ }));

    references.forEach(({ localReferenceKey }, index) => {
      expect(screen.getByText(`${labels[index]} · journal article · 2024`)).toBeTruthy();
      const citationLink = document.querySelector(`a[href="#bibliography-${localReferenceKey}"]`);
      expect(citationLink?.textContent).toContain(labels[index]);
      expect(document.getElementById(`bibliography-${localReferenceKey}`)).toBeTruthy();
    });
  });

  it("shows provisional extraction signals and unmatched TEI targets without dropping local Citation Targets", () => {
    const suspiciousDocument: ParsedDocument = {
      ...parsedDocument,
      bibliographyNormalizationPolicy: { policyId: "grobid-bibliography-normalization", version: "2" },
      citationContexts: [{
        ...parsedDocument.citationContexts[0],
        occurrences: [{
          ...parsedDocument.citationContexts[0].occurrences[0],
          unmatchedBibliographyReferenceKeys: ["missing-source-key"],
        }],
      }],
      bibliographyEntries: [{
        ...parsedDocument.bibliographyEntries[0],
        rawText: "",
        sourceTextContent: "Raw TEI content",
        sourceElement: "bibl",
        sourceLocalReferenceKey: "b0",
        localReferenceKeyOrigin: "GROBID_XML_ID",
        provisionalArtifactSignals: ["EMPTY_GROBID_BIBLIOGRAPHY_TEXT"],
        extractionLimitations: ["SOURCE_TEXT_SPAN_UNAVAILABLE"],
        provenanceCaptureStatus: "CAPTURED",
      }],
    };

    renderStage("source", suspiciousDocument);

    expect(screen.getByText("Unmatched GROBID targets: missing-source-key")).toBeTruthy();
    expect(screen.getByText("Provisional extraction signal")).toBeTruthy();
    expect(screen.getByText(/This screening signal is not human adjudication/)).toBeTruthy();
    expect(screen.getByText("No bibliography text was extracted.")).toBeTruthy();
    expect(screen.getByText("b1 · journal article")).toBeTruthy();
  });

  it("preserves generated keys when the complete order collection is not reliable", () => {
    const data = withReferenceCollection([
      { localReferenceKey: "b3", entryOrder: 0 },
      { localReferenceKey: "b7", entryOrder: 2 },
    ]);
    renderStage("source", data.parsedDocument, data.report);

    expect(screen.getByText("b3 · journal article")).toBeTruthy();
    expect(screen.getByText("b7 · journal article")).toBeTruthy();
    expect(screen.getByText("[1] · b3 · inferred")).toBeTruthy();
    expect(screen.getByText("[2] · b7 · inferred")).toBeTruthy();
  });

  it("shows one-based bibliography labels in the run workspace while retaining zero-based anchors", () => {
    configureWorkspaceQueries();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <AnalysisRunsWorkspace initialSelectedRunId={run.id} />
      </QueryClientProvider>,
    );

    fireEvent.click(screen.getByRole("tab", { name: /Parsed Document/ }));

    expect(screen.getByText("b1 · journal article · 2024")).toBeTruthy();
    expect(screen.getByRole("link", { name: "b1" }).getAttribute("href")).toBe("#bibliography-b0");
    expect(screen.getByRole("link", { name: /\(Author, 2024\).*b1/ }).getAttribute("href")).toBe("#bibliography-b0");
    expect(document.getElementById("bibliography-b0")).toBeTruthy();
  });
});
