import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AnalysisRunDetailPage } from "@/features/analysis-runs/components/analysis-run-detail-page";
import { AnalysisRunPaperReview } from "@/features/analysis-runs/components/analysis-run-paper-review";
import { WorkspaceShell } from "@/features/workspace/components/workspace-shell";
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
    getViewport: ({ scale }: { scale: number }) => ({ scale, userUnit: 1, width: 612 * scale, height: 792 * scale }),
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
  parsedDocument?: ParsedDocument | null;
  report?: ReferenceResolutionReportResponse | null;
  selectedOutcomeId?: string | null;
  selectedReferenceKey?: string | null;
  selectedDetailSection?: "results" | "citations";
  onSelectOutcome?: (outcomeId: string, referenceKey: string) => void;
  onSelectReference?: (referenceKey: string) => void;
  onClearReviewPair?: () => void;
  onClearSelectedReference?: () => void;
  onSelectDetailSection?: (section: "results" | "citations") => void;
};

function renderReview({ parsedDocument: selectedParsedDocument = parsedDocument, report: selectedReport = report, selectedOutcomeId = null, selectedReferenceKey = null, selectedDetailSection = "results", onSelectOutcome = vi.fn(), onSelectReference = vi.fn(), onClearReviewPair = vi.fn(), onClearSelectedReference = vi.fn(), onSelectDetailSection = vi.fn() }: RenderReviewOptions = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const initialOptions = { parsedDocument: selectedParsedDocument, report: selectedReport, selectedOutcomeId, selectedReferenceKey, selectedDetailSection, onSelectOutcome, onSelectReference, onClearReviewPair, onClearSelectedReference, onSelectDetailSection };
  const createReview = (options: typeof initialOptions) => (
    <QueryClientProvider client={queryClient}>
      <AnalysisRunPaperReview
        run={run}
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
  vi.stubGlobal("IntersectionObserver", class {
    observe() {}
    unobserve() {}
    disconnect() {}
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <WorkspaceShell>
        <AnalysisRunDetailPage analysisRunId={run.id} />
      </WorkspaceShell>
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

  it("fits the PDF page inside the padded viewport at default zoom", async () => {
    installSourcePdfResponse();
    const originalClientWidth = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "clientWidth");
    const paddingStyles = document.createElement("style");
    paddingStyles.textContent = ".source-document-pdf-page { padding-left: 12px; padding-right: 12px; }";
    document.head.append(paddingStyles);
    Object.defineProperty(HTMLElement.prototype, "clientWidth", {
      configurable: true,
      get() {
        if (this instanceof HTMLElement && this.classList.contains("source-document-pdf-page")) return 1000;
        return originalClientWidth?.get?.call(this) ?? 0;
      },
    });

    try {
      renderReview();
      await screen.findByText("Text of PDF page 1: Uploaded paper page one.");
      const pageViewport = screen.getByRole("region", { name: "PDF page 1 scroll area" });

      await waitFor(() => expect(pageViewport.querySelector("canvas")?.getAttribute("style")).toContain("width: 976px"));
    } finally {
      paddingStyles.remove();
      if (originalClientWidth) Object.defineProperty(HTMLElement.prototype, "clientWidth", originalClientWidth);
      else Reflect.deleteProperty(HTMLElement.prototype, "clientWidth");
    }
  });

  it("allows keyboard focus on the scrollable PDF page viewport", async () => {
    installSourcePdfResponse();
    renderReview();

    await screen.findByText("Text of PDF page 1: Uploaded paper page one.");
    const pageViewport = screen.getByRole("region", { name: "PDF page 1 scroll area" });
    pageViewport.focus();
    expect(document.activeElement).toBe(pageViewport);
  });

  it("adds an icon-only Back to top action alongside Focus Paper Review", () => {
    installSourcePdfResponse();
    const scrollTo = vi.spyOn(window, "scrollTo").mockImplementation(() => {});

    try {
      renderReview();
      const viewer = screen.getByRole("region", { name: "Original uploaded paper" });
      expect(within(viewer).getByRole("button", { name: "Focus Paper Review" })).toBeTruthy();
      fireEvent.click(within(viewer).getByRole("button", { name: "Back to top" }));
      expect(scrollTo).toHaveBeenCalledWith({ top: 0, behavior: "smooth" });
    } finally {
      scrollTo.mockRestore();
    }
  });

  it("shows one-based labels for numerically cited entries even in a mixed citation style", () => {
    const mixedCitationParsedDocument: ParsedDocument = {
      ...parsedDocument,
      citationContexts: [
        ...parsedDocument.citationContexts,
        {
          ...parsedDocument.citationContexts[0],
          id: "author-year-context",
          occurrences: [{
            ...parsedDocument.citationContexts[0].occurrences[0],
            id: "author-year-occurrence",
            markerText: "(A. Author, 2024)",
            bibliographyReferenceKeys: [],
          }],
          atomicClaims: [],
        },
      ],
    };
    const review = renderReview({ parsedDocument: mixedCitationParsedDocument, selectedReferenceKey: "b0", selectedDetailSection: "citations" });

    expect(screen.getByText("b1 · 2024")).toBeTruthy();
    expect(screen.getByText("Selected bibliography · b1")).toBeTruthy();
    review.rerenderReview({ selectedReferenceKey: null });
    fireEvent.click(screen.getByText("b1 · 2024").closest("button")!);
    expect(review.onSelectReference).toHaveBeenCalledWith("b0");

    review.rerenderReview({ selectedReferenceKey: "b0", selectedDetailSection: "results" });
    expect(screen.getByRole("button", { name: /Review claim against b1/ })).toBeTruthy();

    const authorYearParsedDocument: ParsedDocument = {
      ...parsedDocument,
      citationContexts: parsedDocument.citationContexts.map((context) => ({
        ...context,
        occurrences: context.occurrences.map((occurrence) => ({ ...occurrence, markerText: "(A. Author, 2024)" })),
      })),
    };
    const authorYearReport: ReferenceResolutionReportResponse = {
      ...report,
      referenceResolution: {
        ...report.referenceResolution,
        entries: report.referenceResolution.entries.map((entry) => ({
          ...entry,
          verificationOutcomes: entry.verificationOutcomes.map((entryOutcome) => ({
            ...entryOutcome,
            citationMarkers: ["(A. Author, 2024)"],
          })),
        })),
      },
    };
    review.rerenderReview({ parsedDocument: authorYearParsedDocument, report: authorYearReport, selectedDetailSection: "citations" });

    expect(screen.getByText("b0 · 2024")).toBeTruthy();
    expect(screen.getByText("Selected bibliography · b0")).toBeTruthy();
    review.rerenderReview({ parsedDocument: null, report });
    expect(screen.getByText("b1 · 2024")).toBeTruthy();
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

    fireEvent.click(screen.getByRole("button", { name: `Show atomic claim in PDF: ${outcome.claimText}` }));

    await waitFor(() => expect((screen.getByLabelText("PDF page number") as HTMLInputElement).value).toBe("2"));
    await waitFor(() => expect([...document.querySelectorAll("mark[data-pdf-search-match='true']")].map((mark) => mark.textContent)).toEqual([
      outcome.claimText.replace(/\.$/u, ""),
      "[1].",
    ]));
    expect(screen.getByText("Selected text found · page 2")).toBeTruthy();
  });

  it("finds an AI-result citation in the PDF when extracted text differs from the parsed citation context", async () => {
    installSourcePdfResponse();
    installPdfJsDocument(["Front matter.", "The intervention improved the measured outcomes [1]."]);
    renderReview();

    fireEvent.click(screen.getByRole("button", { name: `Show atomic claim in PDF: ${outcome.claimText}` }));

    await waitFor(() => expect((screen.getByLabelText("PDF page number") as HTMLInputElement).value).toBe("2"));
    await waitFor(() => expect(document.querySelector('[aria-live="polite"]')?.textContent).toBe("1 of 2 selected passages found · page 2"));
    await waitFor(() => expect([...document.querySelectorAll("mark[data-pdf-search-match='true']")].map((mark) => mark.textContent).join(" ")).toContain("The intervention improved the"));
  });

  it("finds and highlights the selected bibliography entry in the original PDF", async () => {
    installSourcePdfResponse();
    installPdfJsDocument(["Front matter.", "Body text.", "A study of outcomes"]);
    renderReview({ selectedReferenceKey: "b0", selectedDetailSection: "citations" });

    fireEvent.click(await screen.findByRole("button", { name: "Show in PDF" }));

    await waitFor(() => expect((screen.getByLabelText("PDF page number") as HTMLInputElement).value).toBe("3"));
    await waitFor(() => expect(document.querySelector('[aria-live="polite"]')?.textContent).toBe("Selected text found · page 3"));
  });

  it("finds a citation context when PDF text differs from the parsed context", async () => {
    installSourcePdfResponse();
    installPdfJsDocument(["Front matter.", "The intervention improved the measured outcomes [1]."]);
    renderReview({ selectedReferenceKey: "b0", selectedDetailSection: "citations" });
    fireEvent.click(screen.getByRole("button", { name: "Citations & bibliography" }));

    const bibliography = await screen.findByRole("region", { name: "Selected bibliography quick access" });
    fireEvent.click(within(bibliography).getByRole("button", { name: "Find in PDF" }));

    await waitFor(() => expect((screen.getByLabelText("PDF page number") as HTMLInputElement).value).toBe("2"));
    await waitFor(() => expect(document.querySelector('[aria-live="polite"]')?.textContent).toBe("Selected text found · page 2"));
    await waitFor(() => expect([...document.querySelectorAll("mark[data-pdf-search-match='true']")].map((mark) => mark.textContent).join(" ")).toContain("The intervention improved the"));
  });

  it("groups parsed claims with linked bibliography references and reports a selected AI pair", async () => {
    installSourcePdfResponse();
    const onSelectOutcome = vi.fn();
    renderReview({ selectedOutcomeId: outcome.id, selectedReferenceKey: "b0", onSelectOutcome });
    fireEvent.click(screen.getByRole("button", { name: "AI results" }));

    await screen.findByRole("region", { name: "Selected pair quick access" });
    expect(screen.getByRole("heading", { name: "Claim results" })).toBeTruthy();
    expect(screen.getAllByText(outcome.claimText)).toHaveLength(2);
    expect(screen.getByText("Machine result: supported")).toBeTruthy();
    expect(screen.getByText(outcome.citationContextText)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /Review claim against b1/ }));
    expect(onSelectOutcome).toHaveBeenCalledWith(outcome.id, "b0");
  });

  it("keeps the selected pair bibliography shortcut visible and lets users clear the selection", async () => {
    installSourcePdfResponse();
    const { onSelectReference, onClearReviewPair } = renderReview({ selectedOutcomeId: outcome.id, selectedReferenceKey: "b0" });

    await screen.findByRole("region", { name: "Selected pair quick access" });
    fireEvent.click(screen.getByRole("button", { name: "View bibliography" }));
    expect(onSelectReference).toHaveBeenCalledWith("b0");
    fireEvent.click(screen.getByRole("button", { name: "Clear selected pair" }));
    expect(onClearReviewPair).toHaveBeenCalledOnce();
  });

  it("collapses and reopens the selected pair details from its result row", async () => {
    installSourcePdfResponse();
    renderReview({ selectedOutcomeId: outcome.id, selectedReferenceKey: "b0" });

    const quickAccessName = "Selected pair quick access";
    await screen.findByRole("region", { name: quickAccessName });
    const trigger = screen.getByRole("button", { name: /Review claim against b1/ });
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

  it("removes Back to top while Paper Review is active and restores it for the pipeline", async () => {
    installSourcePdfResponse();
    queryHookMocks.useAnalysisRun.mockReturnValue({ data: run, isPending: false, error: null });
    queryHookMocks.useParsedDocument.mockReturnValue({ data: parsedDocument, isPending: false, isError: false, error: null });
    queryHookMocks.useReferenceResolutionReport.mockReturnValue({ data: report, isPending: false, isError: false, error: null });
    window.history.replaceState(null, "", `/analysis-runs/${run.id}?step=source`);

    renderDetailPage();
    const workspaceMain = screen.getByRole("main");
    expect(workspaceMain.classList.contains("pb-6")).toBe(true);
    expect(screen.queryByRole("heading", { name: "Pipeline outputs" })).toBeNull();
    expect(screen.queryByText(/saved pipeline-stage results/i)).toBeNull();
    const reviewTab = screen.getByRole("tab", { name: "Paper Review" });
    const pipelineTab = screen.getByRole("tab", { name: "Analysis Pipeline" });
    expect(screen.getByText("Back to top")).toBeTruthy();
    expect(screen.getByRole("contentinfo")).toBeTruthy();

    fireEvent.click(reviewTab);
    await waitFor(() => expect(reviewTab.getAttribute("aria-selected")).toBe("true"));
    expect(screen.getByRole("heading", { name: "Paper Review" })).toBeTruthy();
    expect(screen.queryByText("Back to top")).toBeNull();
    const paperViewer = screen.getByRole("region", { name: "Original uploaded paper" });
    expect(within(paperViewer).getByRole("button", { name: "Back to top" })).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole("contentinfo")).toBeNull());
    expect(workspaceMain.classList.contains("pb-2")).toBe(true);
    expect(new URLSearchParams(window.location.search).get("view")).toBe("review");

    fireEvent.click(pipelineTab);
    await waitFor(() => expect(pipelineTab.getAttribute("aria-selected")).toBe("true"));
    expect(screen.getByText("Back to top")).toBeTruthy();
    expect(screen.getByRole("contentinfo")).toBeTruthy();
    expect(workspaceMain.classList.contains("pb-6")).toBe(true);
    expect(new URLSearchParams(window.location.search).has("view")).toBe(false);

    fireEvent.click(reviewTab);
    await waitFor(() => expect(reviewTab.getAttribute("aria-selected")).toBe("true"));
    expect(screen.getByRole("heading", { name: "Paper Review" })).toBeTruthy();
    expect(screen.queryByText("Back to top")).toBeNull();
    await waitFor(() => expect(screen.queryByRole("contentinfo")).toBeNull());
  });

  it("scrolls bibliography and filtered AI-result destinations with a top gap", async () => {
    installSourcePdfResponse();
    window.history.replaceState(null, "", "/?reviewFilter=INCOMPLETE");
    const review = renderReview({ selectedOutcomeId: outcome.id, selectedReferenceKey: "b0" });
    await screen.findByRole("region", { name: "Selected pair outside active filters" });

    const viewport = document.querySelector<HTMLElement>("[data-review-items-viewport]");
    expect(viewport).not.toBeNull();
    viewport!.style.overflowY = "auto";
    Object.defineProperties(viewport!, {
      clientHeight: { configurable: true, value: 400 },
      scrollHeight: { configurable: true, value: 1_200 },
      scrollTop: { configurable: true, writable: true, value: 50 },
    });
    const originalGetBoundingClientRect = HTMLElement.prototype.getBoundingClientRect;
    const geometry = vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
      if (this === viewport) return { top: 100 } as DOMRect;
      if (this.id === "review-bibliography-trigger-b0" || this.id === `review-pair-trigger-${outcome.id}`) {
        return { top: 400, height: 40 } as DOMRect;
      }
      return originalGetBoundingClientRect.call(this);
    });
    const animationFrames = vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => {
      callback(performance.now() + 2_000);
      return 1;
    });

    try {
      fireEvent.click(screen.getByRole("button", { name: "View bibliography" }));
      review.rerenderReview({ selectedDetailSection: "citations" });
      await screen.findByRole("region", { name: "Selected bibliography quick access" });

      const bibliographyTrigger = document.getElementById("review-bibliography-trigger-b0");
      await waitFor(() => expect(bibliographyTrigger?.classList.contains("analysis-run-review-item-focus")).toBe(true));
      expect(viewport!.scrollTop).toBe(342);

      review.rerenderReview({ selectedDetailSection: "citations", selectedReferenceKey: null });
      await waitFor(() => expect(screen.queryByRole("region", { name: "Selected bibliography quick access" })).toBeNull());
      review.rerenderReview({ selectedDetailSection: "citations", selectedReferenceKey: "b0" });
      await screen.findByRole("region", { name: "Selected bibliography quick access" });
      expect(bibliographyTrigger?.classList.contains("analysis-run-review-item-focus")).toBe(false);
      expect(viewport!.scrollTop).toBe(342);

      fireEvent.click(screen.getByRole("button", { name: /Review AI result: supported/ }));
      review.rerenderReview({ selectedDetailSection: "results" });
      await screen.findByRole("region", { name: "Selected pair outside active filters" });
      const pairTrigger = document.getElementById(`review-pair-trigger-${outcome.id}`);
      await waitFor(() => expect(pairTrigger?.classList.contains("analysis-run-review-item-focus")).toBe(true));
      expect(viewport!.scrollTop).toBe(634);
    } finally {
      geometry.mockRestore();
      animationFrames.mockRestore();
    }
  });

  it("focuses a directly linked bibliography entry when its citation data finishes loading", async () => {
    installSourcePdfResponse();
    const originalGetBoundingClientRect = HTMLElement.prototype.getBoundingClientRect;
    const geometry = vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
      if (this.matches("[data-review-items-viewport]")) return { top: 100 } as DOMRect;
      if (this.id === "review-bibliography-trigger-b0") return { top: 400, height: 40 } as DOMRect;
      return originalGetBoundingClientRect.call(this);
    });
    const animationFrames = vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => {
      callback(performance.now() + 2_000);
      return 1;
    });

    try {
      const review = renderReview({ parsedDocument: null, report: null, selectedReferenceKey: "b0", selectedDetailSection: "citations" });
      const viewport = document.querySelector<HTMLElement>("[data-review-items-viewport]");
      expect(viewport).not.toBeNull();
      viewport!.style.overflowY = "auto";
      Object.defineProperties(viewport!, {
        clientHeight: { configurable: true, value: 400 },
        scrollHeight: { configurable: true, value: 1_200 },
        scrollTop: { configurable: true, writable: true, value: 50 },
      });

      review.rerenderReview({ parsedDocument, report });
      await screen.findByRole("region", { name: "Selected bibliography quick access" });
      const bibliographyTrigger = document.getElementById("review-bibliography-trigger-b0");
      await waitFor(() => expect(bibliographyTrigger?.classList.contains("analysis-run-review-item-focus")).toBe(true));
      expect(viewport!.scrollTop).toBe(342);
    } finally {
      geometry.mockRestore();
      animationFrames.mockRestore();
    }
  });

  it("restores the URL-backed citation detail section and requests query selection when switched", async () => {
    installSourcePdfResponse();
    const onSelectDetailSection = vi.fn();
    renderReview({ selectedOutcomeId: outcome.id, selectedReferenceKey: "b0", selectedDetailSection: "citations", onSelectDetailSection });

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

    await screen.findByRole("region", { name: "Selected pair quick access" });
    expect(screen.queryByText("Back to top")).toBeNull();
    const paperViewer = screen.getByRole("region", { name: "Original uploaded paper" });
    expect(within(paperViewer).getByRole("button", { name: "Back to top" })).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole("contentinfo")).toBeNull());
    expect(screen.getByRole("main").classList.contains("pb-2")).toBe(true);
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
    fireEvent.click(screen.getByRole("button", { name: "Citations & bibliography" }));
    expect(new URLSearchParams(window.location.search).get("reviewDetail")).toBe("citations");
    firstRender.unmount();

    renderDetailPage();
    expect(screen.getByRole("button", { name: "Citations & bibliography" }).getAttribute("aria-pressed")).toBe("true");
    fireEvent.click(await screen.findByRole("button", { name: /Review AI result: supported/ }));
    expect(new URLSearchParams(window.location.search).get("reviewDetail")).toBe("results");
    expect(new URLSearchParams(window.location.search).get("reviewPair")).toBe(outcome.id);
  });

  it("persists status filters in the URL", async () => {
    installSourcePdfResponse();
    renderReview();
    const supportedCard = screen.getByRole("button", { name: /Supported\s+1/ });
    fireEvent.click(supportedCard);

    await waitFor(() => expect(new URLSearchParams(window.location.search).get("reviewFilter")).toBe("SUPPORTED"));
  });

  it("resets an active review status filter", async () => {
    installSourcePdfResponse();
    window.history.replaceState(null, "", "/?reviewFilter=SUPPORTED");
    renderReview();
    fireEvent.click(screen.getByRole("button", { name: "Reset filters" }));
    await waitFor(() => expect(new URLSearchParams(window.location.search).has("reviewFilter")).toBe(false));
  });
});
