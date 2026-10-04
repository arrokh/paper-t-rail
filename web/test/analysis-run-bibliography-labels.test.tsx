import { fireEvent, render, screen } from "@testing-library/react";
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
    occurrences: [{ id: "occurrence-1", markerText: "(Author, 2024)", startOffset: 25, endOffset: 37, bibliographyReferenceKeys: ["b0"] }],
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
  }],
};

const referenceReport = {
  referenceResolution: {
    summary: { resolved: 1, unresolved: 0, unsupportedReferenceType: 0, notAttempted: 0, failed: 0 },
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
      citedPaperAccess: null,
      verificationOutcomes: [],
    }],
  },
} as unknown as ReferenceResolutionReportResponse;

function renderStage(
  selectedStage: "source" | "references",
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

describe("Analysis Run bibliography display keys", () => {
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
