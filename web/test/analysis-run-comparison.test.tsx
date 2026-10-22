import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AnalysisRunComparisonPage } from "@/features/analysis-runs/components/analysis-run-comparison-page";

const navigation = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }));
vi.mock("next/navigation", () => ({
  useRouter: () => navigation,
  useSearchParams: () => new URLSearchParams(window.location.search),
}));

function jsonResponse(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}

function run(id: string, filename: string, hash: string) {
  return {
    id,
    documentId: `document-${id}`,
    filename,
    sourceContentSha256: hash,
    status: "COMPLETED" as const,
    createdAt: "2026-10-01T00:00:00Z",
  };
}

const configuration = {
  claimExtractor: { provider: "local", model: "extractor-v1", version: "v1" },
  embedding: { provider: "local", model: "embedding-v1", version: "v1" },
  retrieval: { profileId: "hybrid-v1", vectorCandidateLimit: 10, lexicalCandidateLimit: 10, finalCandidateLimit: 5, reciprocalRankFusionConstant: 60, embeddingProfileHash: "b".repeat(64) },
  systemOne: { provider: "local", model: "assessor-v1", version: "v1" },
  sourceParser: { provider: "grobid", version: "v1" },
  citedPaperParser: { provider: "docling", version: "v1" },
  openAccess: { provider: "unpaywall", version: "v1" },
  languageDetector: { provider: "local", version: "v1" },
  referenceResolution: { executionStatus: "COMPLETED", provider: { provider: "crossref", version: "v1" }, scorePolicyVersion: "reference-v1", confidenceThreshold: 0.9 },
  aggregation: { executionStatus: "COMPLETED", verificationPolicyVersion: "verification-v1", aggregationPolicyVersion: "aggregation-v1", thresholds: { support: 0.8 } },
  externalProviderConsents: [{ providerId: "crossref", dataCategories: ["bibliographic_metadata"], retentionDisclosure: "Recorded disclosure." }],
};

const review = {
  id: "review-left",
  analysisRunId: "left-run",
  verificationId: "verification-left",
  action: "DISAGREE",
  overrideStatus: null,
  note: "A reviewer added a note on the left run.",
  createdAt: "2026-10-02T00:00:00Z",
};

const comparison = {
  alignmentPolicy: {
    id: "conservative-source-span-reference-identity",
    version: "3",
    description: "Same bytes and parser text: source spans; same bytes with changed parser text: exact claims and compatible identity; changed bytes: exact claims and DOI/Canonical Paper identity.",
  },
  sourceContentDiffers: false,
  sourceDocumentDiffers: false,
  analysisConfigurationDiffers: true,
  leftRun: {
    analysisRunId: "left-run",
    sourceDocumentId: "document-left-run",
    filename: "left.pdf",
    sourceContentSha256: "a".repeat(64),
    status: "COMPLETED",
    createdAt: "2026-10-01T00:00:00Z",
    startedAt: "2026-10-01T00:01:00Z",
    failureReason: null,
    pipeline: null,
    referenceResolutionExecutionStatus: "COMPLETED",
    evidenceCoverageExecutionStatus: "COMPLETED",
    configuration,
  },
  rightRun: {
    analysisRunId: "right-run",
    sourceDocumentId: "document-left-run",
    filename: "right.pdf",
    sourceContentSha256: "a".repeat(64),
    status: "COMPLETED_WITH_WARNINGS",
    createdAt: "2026-10-03T00:00:00Z",
    startedAt: "2026-10-03T00:01:00Z",
    failureReason: "A verification item did not complete.",
    pipeline: { stages: [{
      id: "verification",
      status: "COMPLETED_WITH_WARNINGS",
      counts: { total: 2, waiting: 0, inProgress: 0, completed: 1, skipped: 0, failed: 1 },
      steps: [{
        id: "assess-pair",
        status: "COMPLETED_WITH_WARNINGS",
        counts: { total: 2, waiting: 0, inProgress: 0, completed: 1, skipped: 0, failed: 1 },
        items: [{ id: "pair-1", label: "Verification pair", status: "FAILED", reasonCode: "SYSTEM_ONE_TIMEOUT" }],
      }],
    }] },
    referenceResolutionExecutionStatus: "COMPLETED_WITH_WARNINGS",
    evidenceCoverageExecutionStatus: "COMPLETED_WITH_WARNINGS",
    configuration: { ...configuration, claimExtractor: { ...configuration.claimExtractor, version: "v2" } },
  },
  counts: { unchanged: 0, changed: 1, added: 0, removed: 0, unmatched: 0, outcomeChanges: 1 },
  items: [{
    changeKind: "CHANGED",
    alignmentStatus: "ALIGNED",
    unmatchedReason: null,
    changedFields: ["machineOutcome"],
    outcomeChanged: true,
    left: {
      atomicClaimId: "claim-left",
      claimText: "The study reports an outcome.",
      sourceStartOffset: 10,
      sourceEndOffset: 40,
      citationContextText: "The study reports an outcome [1].",
      citationTargets: [{ markerText: "[1]", associationKind: "INFERRED_PROVISIONAL" }],
      reference: {
        localReferenceKey: "ref-left",
        title: "Example Paper",
        rawText: "Example Paper, 2024",
        authors: ["A. Researcher"],
        year: 2024,
        doi: "10.1234/example",
        referenceType: "JOURNAL_ARTICLE",
        resolutionStatus: "RESOLVED",
        resolutionReasonCode: null,
        matchMethod: "DOI",
        canonicalPaper: { id: "paper-id", doi: "10.1234/example", title: "Example Paper", authors: ["A. Researcher"], year: 2024 },
      },
      citedPaperAccess: {
        accessStatus: "FULL_TEXT_AVAILABLE",
        accessOrigin: "AUTOMATIC",
        accessReason: null,
        accessReasons: [],
        accessProgressStatus: "COMPLETED",
        accessProgressReason: null,
        indexingStatus: "COMPLETED",
        sourceAssetId: "asset-left",
        contentSha256: "c".repeat(64),
        parserProvider: "docling",
        parserVersion: "v1",
        language: "en",
        languageDetectorVersion: "language-v1",
      },
      evidencePassages: [{
        text: "A retrieved Evidence Passage.",
        sectionOrder: 2,
        sectionHeading: "Results",
        paragraphStart: 5,
        paragraphEnd: 5,
        pageNumber: 4,
        pageNumbers: [4, 5],
        vectorRank: 3,
        lexicalRank: 1,
        fusedRank: 1,
        sourceAssetId: "asset-left",
        contentSha256: "c".repeat(64),
        parserProvider: "docling",
        parserVersion: "v1",
        language: "en",
        languageDetectorVersion: "language-v1",
        judgement: "DIRECT_SUPPORT",
        evidenceRole: "PRIMARY_FINDING",
      }],
      processingStatus: "COMPLETED",
      processingFailureReason: null,
      finalStatus: "SUPPORTED",
      verificationScope: "FULL_TEXT",
      terminalReason: null,
      evidenceConflict: false,
      aggregatorVersion: "aggregation-v1",
      humanReviews: [review],
    },
    right: {
      atomicClaimId: "claim-right",
      claimText: "The study reports an outcome.",
      sourceStartOffset: 10,
      sourceEndOffset: 40,
      citationContextText: "The study reports an outcome [1].",
      citationTargets: [{ markerText: "[1]", associationKind: "INFERRED_PROVISIONAL" }],
      reference: {
        localReferenceKey: "ref-right",
        title: "Example Paper",
        rawText: "Example Paper, 2024",
        authors: ["A. Researcher"],
        year: 2024,
        doi: "10.1234/example",
        referenceType: "JOURNAL_ARTICLE",
        resolutionStatus: "RESOLVED",
        resolutionReasonCode: null,
        matchMethod: "DOI",
        canonicalPaper: { id: "paper-id", doi: "10.1234/example", title: "Example Paper", authors: ["A. Researcher"], year: 2024 },
      },
      citedPaperAccess: {
        accessStatus: "FULL_TEXT_AVAILABLE",
        accessOrigin: "AUTOMATIC",
        accessReason: null,
        accessReasons: [],
        accessProgressStatus: "COMPLETED",
        accessProgressReason: null,
        indexingStatus: "COMPLETED",
        sourceAssetId: "asset-right",
        contentSha256: "d".repeat(64),
        parserProvider: "docling",
        parserVersion: "v1",
        language: "en",
        languageDetectorVersion: "language-v1",
      },
      evidencePassages: [],
      processingStatus: "INCOMPLETE",
      processingFailureReason: "SYSTEM_ONE_TIMEOUT",
      finalStatus: null,
      verificationScope: "FULL_TEXT",
      terminalReason: null,
      evidenceConflict: null,
      aggregatorVersion: null,
      humanReviews: [],
    },
  }],
  disclaimer: "This read-only comparison is a triage aid, not truth certification or an assessment of the whole paper. Machine confidence is not calibrated; no run is described as better. Human Reviews remain with their original run.",
};

afterEach(() => {
  vi.unstubAllGlobals();
  navigation.replace.mockReset();
  window.history.replaceState({}, "", "/");
});

describe("Analysis Run comparison flow", () => {
  it("clears restored comparison query parameters when the selected pair is edited", async () => {
    window.history.replaceState({}, "", "/analysis-runs/compare?leftRunId=left-run&rightRunId=right-run");
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      if (url.startsWith("/api/v1/analysis-runs?")) {
        return jsonResponse({ items: [
          run("left-run", "left.pdf", "a".repeat(64)),
          run("alt-left-run", "left.pdf", "a".repeat(64)),
          run("right-run", "right.pdf", "a".repeat(64)),
        ], nextCursor: null, previousCursor: null });
      }
      if (url.startsWith("/api/v1/analysis-runs/comparison?")) return jsonResponse(comparison);
      throw new Error(`Unexpected request: ${url}`);
    }));

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={queryClient}><AnalysisRunComparisonPage /></QueryClientProvider>);
    expect(await screen.findByText("Comparison overview")).toBeTruthy();

    fireEvent.change(screen.getByLabelText("Left Analysis Run"), { target: { value: "alt-left-run" } });

    expect(navigation.replace).toHaveBeenCalledWith("/analysis-runs/compare", { scroll: false });
    expect(screen.queryByText("Comparison overview")).toBeNull();
  });

  it("selects two saved runs and displays provenance, machine changes, incomplete processing, and run-scoped Human Reviews", async () => {
    const requestedUrls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      requestedUrls.push(url);
      if (url.startsWith("/api/v1/analysis-runs?")) {
        return jsonResponse({ items: [
          run("left-run", "left.pdf", "a".repeat(64)),
          run("alt-left-run", "left.pdf", "a".repeat(64)),
          run("right-run", "right.pdf", "a".repeat(64)),
        ], nextCursor: null, previousCursor: null });
      }
      if (url.startsWith("/api/v1/analysis-runs/comparison?")) return jsonResponse(comparison);
      throw new Error(`Unexpected request: ${url}`);
    }));

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={queryClient}><AnalysisRunComparisonPage /></QueryClientProvider>);

    const leftSelect = await screen.findByLabelText("Left Analysis Run");
    const rightSelect = screen.getByLabelText("Right Analysis Run");
    expect(within(leftSelect).getByRole("option", { name: /left\.pdf.*left-run$/ })).toBeTruthy();
    expect(within(leftSelect).getByRole("option", { name: /left\.pdf.*alt-left/ })).toBeTruthy();
    fireEvent.change(leftSelect, { target: { value: "left-run" } });
    fireEvent.change(rightSelect, { target: { value: "right-run" } });
    fireEvent.click(screen.getByRole("button", { name: "Compare selected runs" }));

    expect(await screen.findByText("Comparison overview")).toBeTruthy();
    await waitFor(() => expect(requestedUrls.some((url) => url.startsWith("/api/v1/analysis-runs/comparison?leftRunId=left-run&rightRunId=right-run"))).toBe(true));
    expect(navigation.replace).toHaveBeenCalledWith("/analysis-runs/compare?leftRunId=left-run&rightRunId=right-run", { scroll: false });
    expect(screen.getAllByText("Source Document SHA-256")).toHaveLength(2);
    expect(screen.getAllByText("Pinned configuration and provider-consent snapshot")).toHaveLength(2);
    expect(screen.getByText(/pinned analysis configuration differs/)).toBeTruthy();
    expect(screen.getByText("Worker stage warnings and incomplete work")).toBeTruthy();
    expect(screen.getByText(/Recorded processing detail: A verification item did not complete\./)).toBeTruthy();
    const leftMachineOutcome = screen.getByRole("region", { name: "Left run machine outcome" });
    expect(within(leftMachineOutcome).getByText("Processing:", { exact: false }).textContent).toContain("COMPLETED");
    expect(screen.getByText(/Processing is incomplete; no final domain status is shown\./)).toBeTruthy();
    expect(screen.queryByText("INSUFFICIENT EVIDENCE")).toBeNull();
    expect(screen.getByText("A retrieved Evidence Passage.")).toBeTruthy();
    expect(screen.getByText(/physical PDF pages 4, 5/)).toBeTruthy();
    expect(screen.getAllByText(/Persisted evidence-conflict flag:/)).toHaveLength(2);
    expect(screen.getAllByText(/Aggregation policy version:/)).toHaveLength(2);
    expect(screen.getByText("Asset asset-left · SHA-256 " + "c".repeat(64))).toBeTruthy();
    expect(screen.getByText("A reviewer added a note on the left run.")).toBeTruthy();
    expect(screen.getAllByText(/Human Reviews — Left run only/)).toHaveLength(1);
    expect(screen.getByText("No Human Reviews recorded for this Verification in this run.")).toBeTruthy();
    expect(screen.getByText(/no run is described as better/i)).toBeTruthy();
    const rightReviews = screen.getByRole("region", { name: "Right run Human Reviews" });
    expect(within(rightReviews).queryByText("A reviewer added a note on the left run.")).toBeNull();
  });
});
