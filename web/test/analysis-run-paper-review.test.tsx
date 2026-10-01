import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AnalysisRunDetailPage } from "@/features/analysis-runs/components/analysis-run-detail-page";
import { AnalysisRunPaperReview } from "@/features/analysis-runs/components/analysis-run-paper-review";
import type { AnalysisRun, ParsedDocument, ReferenceResolutionReportResponse } from "@/features/analysis-runs/types";

const queryHookMocks = vi.hoisted(() => ({
  useAnalysisRun: vi.fn(),
  useParsedDocument: vi.fn(),
  useReferenceResolutionReport: vi.fn(),
}));

const pdfJsMocks = vi.hoisted(() => {
  class TextLayer {
    textDivs: HTMLElement[];

    constructor({ textContentSource, container }: { textContentSource: { items: Array<{ str?: string }> }; container: HTMLElement }) {
      this.textDivs = textContentSource.items.flatMap((item) => {
        if (item.str === undefined) return [];
        const span = document.createElement("span");
        span.textContent = item.str;
        container.append(span);
        return [span];
      });
    }

    async render() {}
    cancel() {}
  }

  return { getDocument: vi.fn(), GlobalWorkerOptions: { workerSrc: "" }, TextLayer };
});

vi.mock("pdfjs-dist", () => pdfJsMocks);

vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams(window.location.search),
}));

vi.mock("@/features/analysis-runs/queries/analysis-run-queries", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/features/analysis-runs/queries/analysis-run-queries")>();
  return { ...actual, ...queryHookMocks };
});

const run: AnalysisRun = {
  id: "run-paper-review",
  documentId: "source-document",
  filename: "uploaded-paper.pdf",
  sourceContentSha256: "a".repeat(64),
  status: "COMPLETED",
  progress: {},
  pipeline: null,
  configuration: {
    claimExtractor: { provider: "heuristic", version: "v1" },
    embedding: { provider: "local", version: "v1" },
    retrieval: { profileId: "test", vectorCandidateLimit: 10, lexicalCandidateLimit: 10, finalCandidateLimit: 5, reciprocalRankFusionConstant: 60, embeddingProfileHash: "b".repeat(64) },
    systemOne: { provider: "laya", version: "v1" },
    sourceParser: { provider: "grobid", version: "v1" },
    languageDetector: { provider: "local", version: "v1" },
  },
  createdAt: "2026-09-30T00:00:00Z",
  startedAt: "2026-09-30T00:00:00Z",
  failureReason: null,
};

const parsedDocument: ParsedDocument = {
  parser: { provider: "grobid", version: "0.9.1-crf" },
  sourceContentSha256: run.sourceContentSha256,
  normalizedSourceText: "The intervention improved the measured outcome [1].",
  sections: [],
  citationContexts: [{
    id: "context-1",
    sectionId: "section-1",
    boundaryKind: "SENTENCE_FALLBACK",
    text: "The intervention improved the measured outcome [1].",
    startOffset: 0,
    endOffset: 53,
    occurrences: [{ id: "occurrence-1", markerText: "[1]", startOffset: 49, endOffset: 52, bibliographyReferenceKeys: ["b0"] }],
    atomicClaims: [{
      id: "claim-1",
      text: "The intervention improved the measured outcome.",
      sourceStartOffset: 0,
      sourceEndOffset: 49,
      citationTargets: [{ id: "target-1", markerText: "[1]", bibliographyReferenceKey: "b0", bibliographyTitle: "A study of outcomes", associationKind: "INFERRED_PROVISIONAL" }],
    }],
  }],
  bibliographyEntries: [{
    entryOrder: 0,
    localReferenceKey: "b0",
    rawText: "A. Author. A study of outcomes. 2024.",
    title: "A study of outcomes",
    authors: ["A. Author"],
    year: 2024,
    doi: "10.1234/example",
    referenceType: "JOURNAL_ARTICLE",
    resolutionStatus: "RESOLVED",
  }],
};

const outcome: ReferenceResolutionReportResponse["referenceResolution"]["entries"][number]["verificationOutcomes"][number] = {
  id: "verification-1",
  atomicClaimId: "claim-1",
  claimText: "The intervention improved the measured outcome.",
  claimSourceStartOffset: 0,
  claimSourceEndOffset: 49,
  citationContextText: "The intervention improved the measured outcome [1].",
  citationMarkers: ["[1]"],
  associationKind: "INFERRED_PROVISIONAL",
  processingStatus: "COMPLETED",
  processingFailureReason: null,
  finalStatus: "SUPPORTED",
  verificationScope: "FULL_TEXT",
  terminalReason: null,
  evidenceConflict: false,
  aggregatorVersion: "conflict-aware-evidence-strength-v1",
  evidencePassages: [],
  humanReviews: [],
};

const report: ReferenceResolutionReportResponse = {
  analysisRunId: run.id,
  runStatus: run.status,
  evidenceCoverage: {
    executionStatus: "COMPLETED",
    verificationPolicyVersion: "verification-v1",
    aggregationPolicyVersion: "aggregation-v1",
    thresholds: {},
    summary: {
      totalVerifications: 1,
      completedVerifications: 1,
      incompleteVerifications: 0,
      evidenceConflicts: 0,
      supported: 1,
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
    scorePolicyVersion: "score-v1",
    confidenceThreshold: 0.9,
    summary: { total: 1, resolved: 1, unresolved: 0, unsupportedReferenceType: 0, notAttempted: 0, failed: 0 },
    entries: [{
      entryOrder: 0,
      localReferenceKey: "b0",
      rawText: "A. Author. A study of outcomes. 2024.",
      title: "A study of outcomes",
      authors: ["A. Author"],
      year: 2024,
      doi: "10.1234/example",
      referenceType: "JOURNAL_ARTICLE",
      status: "RESOLVED",
      reasonCode: null,
      canonicalPaper: { id: "paper-1", doi: "10.1234/example", title: "A study of outcomes", authors: ["A. Author"], year: 2024 },
      confidenceScore: 0.99,
      matchMethod: "DOI",
      citedPaperAccess: null,
      verificationOutcomes: [outcome],
    }],
  },
};

function installSourcePdfResponse() {
  installPdfJsDocument();
  vi.stubGlobal("fetch", vi.fn(async (url: string) => {
    if (url === `/api/v1/analysis-runs/${run.id}/source-document`) {
      return {
        ok: true,
        status: 200,
        json: async () => ({
          filename: run.filename,
          viewUrl: "http://127.0.0.1:9000/source-documents/view-signed",
          downloadUrl: "http://127.0.0.1:9000/source-documents/download-signed",
          expiresAt: "2026-10-01T00:00:00Z",
        }),
      };
    }
    throw new Error(`Unexpected request: ${url}`);
  }));
}

function installPdfJsDocument(pages = ["Uploaded paper page one.", outcome.citationContextText, "A study of outcomes"]): ReturnType<typeof createPdfDocument> {
  const pdfDocument = createPdfDocument(pages);
  pdfJsMocks.getDocument.mockReturnValue({ promise: Promise.resolve(pdfDocument), destroy: vi.fn() });
  return pdfDocument;
}

function createPdfDocument(pages: string[]) {
  const pdfPages = pages.map((text) => ({
    getViewport: ({ scale }: { scale: number }) => ({ width: 612 * scale, height: 792 * scale }),
    getTextContent: async () => ({
      items: [{ str: text, dir: "ltr", transform: [12, 0, 0, 12, 72, 720], width: 400, height: 12, fontName: "test-font", hasEOL: true }],
      styles: { "test-font": { ascent: 0.8, descent: -0.2, vertical: false, fontFamily: "Arial" } },
      lang: "en",
    }),
    render: vi.fn(() => ({ promise: Promise.resolve(), cancel: vi.fn() })),
  }));

  return {
    numPages: pdfPages.length,
    getPage: vi.fn(async (pageNumber: number) => pdfPages[pageNumber - 1]),
  };
}

type RenderReviewOptions = {
  selectedOutcomeId?: string | null;
  selectedReferenceKey?: string | null;
  selectedDetailSection?: "results" | "citations";
  onSelectOutcome?: (outcomeId: string, referenceKey: string) => void;
  onSelectReference?: (referenceKey: string) => void;
  onClearReviewPair?: () => void;
  onClearSelectedReference?: () => void;
  onSelectDetailSection?: (section: "results" | "citations") => void;
};

function renderReview({ selectedOutcomeId = null, selectedReferenceKey = null, selectedDetailSection = "results", onSelectOutcome = vi.fn(), onSelectReference = vi.fn(), onClearReviewPair = vi.fn(), onClearSelectedReference = vi.fn(), onSelectDetailSection = vi.fn() }: RenderReviewOptions = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const initialOptions = { selectedOutcomeId, selectedReferenceKey, selectedDetailSection, onSelectOutcome, onSelectReference, onClearReviewPair, onClearSelectedReference, onSelectDetailSection };
  const createReview = (options: typeof initialOptions) => (
    <QueryClientProvider client={queryClient}>
      <AnalysisRunPaperReview
        run={run}
        parsedDocument={parsedDocument}
        report={report}
        parsedLoading={false}
        reportLoading={false}
        parsedError={null}
        reportError={null}
        {...options}
      />
    </QueryClientProvider>
  );
  const rendered = render(createReview(initialOptions));
  return {
    ...rendered,
    queryClient,
    onSelectOutcome,
    onSelectReference,
    onClearReviewPair,
    rerenderReview: (updates: Partial<typeof initialOptions>) => rendered.rerender(createReview({ ...initialOptions, ...updates })),
  };
}

function renderDetailPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AnalysisRunDetailPage analysisRunId={run.id} />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
  queryHookMocks.useAnalysisRun.mockReset();
  queryHookMocks.useParsedDocument.mockReset();
  queryHookMocks.useReferenceResolutionReport.mockReset();
  pdfJsMocks.getDocument.mockReset();
  pdfJsMocks.GlobalWorkerOptions.workerSrc = "";
});

describe("Analysis Run Paper Review", () => {
  it("loads the uploaded Source Document from its short-lived MinIO URL", async () => {
    installSourcePdfResponse();
    renderReview();

    await waitFor(() => expect(pdfJsMocks.getDocument).toHaveBeenCalledWith({ url: "http://127.0.0.1:9000/source-documents/view-signed", withCredentials: false }));
    await waitFor(() => expect(screen.getByLabelText("PDF page number").getAttribute("max")).toBe("3"));
    expect(screen.getByRole("link", { name: "Download" }).getAttribute("href")).toBe("http://127.0.0.1:9000/source-documents/download-signed");
    await waitFor(() => expect(screen.getByText("Text of PDF page 1: Uploaded paper page one.")).toBeTruthy());
  });

  it("renews MinIO URLs only when Refresh PDF is explicitly selected", async () => {
    installPdfJsDocument();
    let issue = 0;
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      if (url !== `/api/v1/analysis-runs/${run.id}/source-document`) throw new Error(`Unexpected request: ${url}`);
      issue += 1;
      return {
        ok: true,
        status: 200,
        json: async () => ({
          filename: run.filename,
          viewUrl: `http://127.0.0.1:9000/source-documents/view-${issue}`,
          downloadUrl: `http://127.0.0.1:9000/source-documents/download-${issue}`,
          expiresAt: "2026-10-01T00:00:00Z",
        }),
      };
    }));
    renderReview();

    await waitFor(() => expect(pdfJsMocks.getDocument).toHaveBeenCalledWith({ url: "http://127.0.0.1:9000/source-documents/view-1", withCredentials: false }));
    fireEvent.click(screen.getByRole("button", { name: "Refresh PDF" }));
    await waitFor(() => expect(pdfJsMocks.getDocument).toHaveBeenCalledWith({ url: "http://127.0.0.1:9000/source-documents/view-2", withCredentials: false }));
    expect(issue).toBe(2);
  });

  it("highlights the selected Atomic Claim and citation marker in the original PDF", async () => {
    installSourcePdfResponse();
    installPdfJsDocument(["Front matter.", outcome.citationContextText, "References."]);
    renderReview({ selectedOutcomeId: outcome.id, selectedReferenceKey: "b0", selectedDetailSection: "results" });
    fireEvent.click(screen.getByRole("button", { name: "Details" }));

    fireEvent.click(await screen.findByRole("button", { name: "Show in PDF" }));

    await waitFor(() => expect((screen.getByLabelText("PDF page number") as HTMLInputElement).value).toBe("2"));
    await waitFor(() => expect([...document.querySelectorAll("mark[data-pdf-search-match='true']")].map((mark) => mark.textContent)).toEqual([
      outcome.claimText.replace(/\.$/u, ""),
      "[1].",
    ]));
    expect(screen.getByText("Selected text found · page 2")).toBeTruthy();
  });

  it("highlights an atomic claim from its Claim results card in the original PDF", async () => {
    installSourcePdfResponse();
    installPdfJsDocument(["Front matter.", outcome.citationContextText]);
    renderReview();
    fireEvent.click(screen.getByRole("button", { name: "Details" }));

    fireEvent.click(screen.getByRole("button", { name: `Show atomic claim in PDF: ${outcome.claimText}` }));

    await waitFor(() => expect((screen.getByLabelText("PDF page number") as HTMLInputElement).value).toBe("2"));
    await waitFor(() => expect([...document.querySelectorAll("mark[data-pdf-search-match='true']")].map((mark) => mark.textContent)).toEqual([
      outcome.claimText.replace(/\.$/u, ""),
      "[1].",
    ]));
    expect(screen.getByText("Selected text found · page 2")).toBeTruthy();
  });

  it("finds and highlights the selected bibliography entry in the original PDF", async () => {
    installSourcePdfResponse();
    installPdfJsDocument(["Front matter.", "Body text.", "A study of outcomes"]);
    renderReview({ selectedReferenceKey: "b0", selectedDetailSection: "citations" });
    fireEvent.click(screen.getByRole("button", { name: "Details" }));

    fireEvent.click(await screen.findByRole("button", { name: "Show in PDF" }));

    await waitFor(() => expect((screen.getByLabelText("PDF page number") as HTMLInputElement).value).toBe("3"));
    await waitFor(() => expect(document.querySelector('[aria-live="polite"]')?.textContent).toBe("1 of 3 selected passages found · page 3"));
  });

  it("groups parsed claims with linked bibliography references and reports a selected AI pair", async () => {
    installSourcePdfResponse();
    const onSelectOutcome = vi.fn();
    renderReview({ selectedOutcomeId: outcome.id, selectedReferenceKey: "b0", onSelectOutcome });
    fireEvent.click(screen.getByRole("button", { name: "Details" }));
    fireEvent.click(screen.getByRole("button", { name: "AI results" }));

    await screen.findByRole("region", { name: "Selected pair quick access" });
    expect(screen.getByRole("heading", { name: "Claim results" })).toBeTruthy();
    expect(screen.getAllByText(outcome.claimText)).toHaveLength(2);
    expect(screen.getByText("Machine result: supported")).toBeTruthy();
    expect(screen.getByText(outcome.citationContextText)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /Review claim against b0/ }));
    expect(onSelectOutcome).toHaveBeenCalledWith(outcome.id, "b0");
  });

  it("keeps the selected pair bibliography shortcut visible and lets users clear the selection", async () => {
    installSourcePdfResponse();
    const { onSelectReference, onClearReviewPair } = renderReview({ selectedOutcomeId: outcome.id, selectedReferenceKey: "b0" });
    fireEvent.click(screen.getByRole("button", { name: "Details" }));

    await screen.findByRole("region", { name: "Selected pair quick access" });
    fireEvent.click(screen.getByRole("button", { name: "View bibliography" }));
    expect(onSelectReference).toHaveBeenCalledWith("b0");
    fireEvent.click(screen.getByRole("button", { name: "Clear selected pair" }));
    expect(onClearReviewPair).toHaveBeenCalledOnce();
  });

  it("collapses and reopens the selected pair details from its result row", async () => {
    installSourcePdfResponse();
    renderReview({ selectedOutcomeId: outcome.id, selectedReferenceKey: "b0" });
    fireEvent.click(screen.getByRole("button", { name: "Details" }));

    const quickAccessName = "Selected pair quick access";
    await screen.findByRole("region", { name: quickAccessName });
    const trigger = screen.getByRole("button", { name: /Review claim against b0/ });
    fireEvent.click(trigger);
    await waitFor(() => expect(screen.queryByRole("region", { name: quickAccessName })).toBeNull());
    expect(trigger.getAttribute("aria-pressed")).toBe("true");

    fireEvent.click(trigger);
    expect(await screen.findByRole("region", { name: quickAccessName })).toBeTruthy();
  });

  it("connects a selected bibliography entry back to its parsed citation and AI result", async () => {
    installSourcePdfResponse();
    installPdfJsDocument(["Front matter.", parsedDocument.citationContexts[0].text]);
    const onSelectOutcome = vi.fn();
    renderReview({ selectedReferenceKey: "b0", selectedDetailSection: "citations", onSelectOutcome });
    fireEvent.click(screen.getByRole("button", { name: "Details" }));
    fireEvent.click(screen.getByRole("button", { name: "Citations & bibliography" }));

    const pinnedReference = await screen.findByRole("region", { name: "Selected bibliography quick access" });
    expect(within(pinnedReference).getByRole("heading", { name: "Citing contexts" })).toBeTruthy();
    expect(within(pinnedReference).getByText("The intervention improved the measured outcome [1].")).toBeTruthy();
    expect(screen.getByRole("button", { name: /A\. Author/ })).toBeTruthy();
    fireEvent.click(within(pinnedReference).getByRole("button", { name: "Find in PDF" }));
    await waitFor(() => expect((screen.getByLabelText("PDF page number") as HTMLInputElement).value).toBe("2"));
    fireEvent.click(within(pinnedReference).getByRole("button", { name: /Review AI result: supported/ }));
    expect(onSelectOutcome).toHaveBeenCalledWith(outcome.id, "b0");
  });

  it("collapses and reopens the selected bibliography details from its entry", async () => {
    installSourcePdfResponse();
    renderReview({ selectedReferenceKey: "b0", selectedDetailSection: "citations" });
    fireEvent.click(screen.getByRole("button", { name: "Details" }));
    fireEvent.click(screen.getByRole("button", { name: "Citations & bibliography" }));

    const quickAccessName = "Selected bibliography quick access";
    await screen.findByRole("region", { name: quickAccessName });
    const trigger = screen.getByRole("button", { name: /A\. Author/ });
    fireEvent.click(trigger);
    await waitFor(() => expect(screen.queryByRole("region", { name: quickAccessName })).toBeNull());
    expect(trigger.getAttribute("aria-pressed")).toBe("true");

    fireEvent.click(trigger);
    expect(await screen.findByRole("region", { name: quickAccessName })).toBeTruthy();
  });

  it("keeps both Analysis Run views selectable and restores Paper Review from the pipeline", async () => {
    installSourcePdfResponse();
    queryHookMocks.useAnalysisRun.mockReturnValue({ data: run, isPending: false, error: null });
    queryHookMocks.useParsedDocument.mockReturnValue({ data: parsedDocument, isPending: false, isError: false, error: null });
    queryHookMocks.useReferenceResolutionReport.mockReturnValue({ data: report, isPending: false, isError: false, error: null });
    window.history.replaceState(null, "", `/analysis-runs/${run.id}?step=source`);

    renderDetailPage();
    const reviewTab = screen.getByRole("tab", { name: "Paper Review" });
    const pipelineTab = screen.getByRole("tab", { name: "Analysis Pipeline" });

    fireEvent.click(reviewTab);
    await waitFor(() => expect(reviewTab.getAttribute("aria-selected")).toBe("true"));
    expect(screen.getByRole("heading", { name: "Paper Review" })).toBeTruthy();
    expect(new URLSearchParams(window.location.search).get("view")).toBe("review");

    fireEvent.click(pipelineTab);
    await waitFor(() => expect(pipelineTab.getAttribute("aria-selected")).toBe("true"));
    expect(new URLSearchParams(window.location.search).has("view")).toBe(false);

    fireEvent.click(reviewTab);
    await waitFor(() => expect(reviewTab.getAttribute("aria-selected")).toBe("true"));
    expect(screen.getByRole("heading", { name: "Paper Review" })).toBeTruthy();
  });

  it("reveals the destination item when moving between a pair and its bibliography or AI result", async () => {
    installSourcePdfResponse();
    const review = renderReview({ selectedOutcomeId: outcome.id, selectedReferenceKey: "b0" });
    fireEvent.click(screen.getByRole("button", { name: "Details" }));
    await screen.findByRole("region", { name: "Selected pair quick access" });
    fireEvent.click(screen.getByRole("button", { name: "View bibliography" }));
    review.rerenderReview({ selectedDetailSection: "citations" });
    await screen.findByRole("region", { name: "Selected bibliography quick access" });

    const bibliographyTrigger = document.getElementById("review-bibliography-trigger-b0");
    await waitFor(() => expect(bibliographyTrigger?.classList.contains("analysis-run-review-item-focus")).toBe(true));

    fireEvent.click(screen.getByRole("button", { name: /Review AI result: supported/ }));
    review.rerenderReview({ selectedDetailSection: "results" });
    await screen.findByRole("region", { name: "Selected pair quick access" });
    const pairTrigger = document.getElementById(`review-pair-trigger-${outcome.id}`);
    await waitFor(() => expect(pairTrigger?.classList.contains("analysis-run-review-item-focus")).toBe(true));
  });

  it("restores the URL-backed citation detail section and requests query selection when switched", async () => {
    installSourcePdfResponse();
    const onSelectDetailSection = vi.fn();
    renderReview({ selectedOutcomeId: outcome.id, selectedReferenceKey: "b0", selectedDetailSection: "citations", onSelectDetailSection });
    fireEvent.click(screen.getByRole("button", { name: "Details" }));

    expect(screen.getByRole("button", { name: "Citations & bibliography" }).getAttribute("aria-pressed")).toBe("true");
    await screen.findByRole("heading", { name: "Citing contexts" });
    fireEvent.click(screen.getByRole("button", { name: "AI results" }));
    expect(onSelectDetailSection).toHaveBeenCalledWith("results");
  });

  it("clears the selected pair from the Paper Review quick-access card", async () => {
    installSourcePdfResponse();
    queryHookMocks.useAnalysisRun.mockReturnValue({ data: run, isPending: false, error: null });
    queryHookMocks.useParsedDocument.mockReturnValue({ data: parsedDocument, isPending: false, isError: false, error: null });
    queryHookMocks.useReferenceResolutionReport.mockReturnValue({ data: report, isPending: false, isError: false, error: null });
    window.history.replaceState(null, "", `/analysis-runs/${run.id}?view=review&reviewPair=${outcome.id}&reviewReference=b0&reviewDetail=results`);

    renderDetailPage();
    fireEvent.click(screen.getByRole("button", { name: "Details" }));

    await screen.findByRole("region", { name: "Selected pair quick access" });
    fireEvent.click(screen.getByRole("button", { name: "Clear selected pair" }));

    await waitFor(() => {
      const searchParams = new URLSearchParams(window.location.search);
      expect(searchParams.has("reviewPair")).toBe(false);
      expect(searchParams.has("reviewReference")).toBe(false);
    });
    expect(new URLSearchParams(window.location.search).get("reviewDetail")).toBe("results");
  });

  it("persists citation and AI result panel selections through the detail URL and reload", async () => {
    installSourcePdfResponse();
    queryHookMocks.useAnalysisRun.mockReturnValue({ data: run, isPending: false, error: null });
    queryHookMocks.useParsedDocument.mockReturnValue({ data: parsedDocument, isPending: false, isError: false, error: null });
    queryHookMocks.useReferenceResolutionReport.mockReturnValue({ data: report, isPending: false, isError: false, error: null });
    window.history.replaceState(null, "", `/analysis-runs/${run.id}?view=review&reviewPair=${outcome.id}&reviewReference=b0&reviewDetail=results`);

    const firstRender = renderDetailPage();
    fireEvent.click(screen.getByRole("button", { name: "Details" }));
    fireEvent.click(screen.getByRole("button", { name: "Citations & bibliography" }));
    expect(new URLSearchParams(window.location.search).get("reviewDetail")).toBe("citations");
    firstRender.unmount();

    renderDetailPage();
    fireEvent.click(screen.getByRole("button", { name: "Details" }));
    expect(screen.getByRole("button", { name: "Citations & bibliography" }).getAttribute("aria-pressed")).toBe("true");
    fireEvent.click(await screen.findByRole("button", { name: /Review AI result: supported/ }));
    expect(new URLSearchParams(window.location.search).get("reviewDetail")).toBe("results");
    expect(new URLSearchParams(window.location.search).get("reviewPair")).toBe(outcome.id);
  });

  it("persists status filters in the URL", async () => {
    installSourcePdfResponse();
    renderReview();
    fireEvent.click(screen.getByRole("button", { name: "Details" }));
    const supportedCard = screen.getByRole("button", { name: /Supported\s+1/ });
    fireEvent.click(supportedCard);

    await waitFor(() => expect(new URLSearchParams(window.location.search).get("reviewFilter")).toBe("SUPPORTED"));
  });

  it("resets an active review status filter", async () => {
    installSourcePdfResponse();
    window.history.replaceState(null, "", "/?reviewFilter=SUPPORTED");
    renderReview();
    fireEvent.click(screen.getByRole("button", { name: "Details" }));
    fireEvent.click(screen.getByRole("button", { name: "Reset filters" }));
    await waitFor(() => expect(new URLSearchParams(window.location.search).has("reviewFilter")).toBe(false));
  });
});
