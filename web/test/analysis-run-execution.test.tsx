import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AnalysisRunDetailPage } from "@/features/analysis-runs/components/analysis-run-detail-page";
import { WorkspaceShell } from "@/features/workspace/components/workspace-shell";
import type { ExecutionArtifact, ExecutionSpan, ExecutionSpanDetail, ExecutionSummary } from "@/features/analysis-runs/execution/execution-types";
import type { AnalysisRun } from "@/features/analysis-runs/types";

const executionMocks = vi.hoisted(() => ({
  useAnalysisRun: vi.fn(),
  useParsedDocument: vi.fn(),
  useReferenceResolutionReport: vi.fn(),
  useExecutionSummary: vi.fn(),
  useExecutionSpans: vi.fn(),
  useExecutionSpan: vi.fn(),
  useExecutionArtifact: vi.fn(),
  useStopExecutionCapture: vi.fn(),
  useRemoveExecutionArtifact: vi.fn(),
  stopCapture: vi.fn(),
  removeArtifact: vi.fn(),
  spanRequests: vi.fn(),
  artifactRequests: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams(window.location.search),
}));

vi.mock("@/features/analysis-runs/queries/analysis-run-queries", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/features/analysis-runs/queries/analysis-run-queries")>();
  return { ...actual, ...executionMocks };
});

const run: AnalysisRun = {
  id: "execution-run",
  documentId: "source-document",
  filename: "execution-paper.pdf",
  sourceContentSha256: "a".repeat(64),
  status: "PROCESSING",
  progress: {},
  pipeline: null,
  configuration: {
    claimExtractor: { provider: "local", version: "v1" },
    embedding: { provider: "local", version: "v1" },
    retrieval: { profileId: "test", vectorCandidateLimit: 10, lexicalCandidateLimit: 10, finalCandidateLimit: 5, reciprocalRankFusionConstant: 60, embeddingProfileHash: "b".repeat(64) },
    systemOne: { provider: "local", version: "v1" },
    sourceParser: { provider: "local", version: "v1" },
    languageDetector: { provider: "local", version: "v1" },
  },
  createdAt: "2026-01-01T00:00:00.000Z",
  startedAt: "2026-01-01T00:00:00.000Z",
  failureReason: null,
};

const summary: ExecutionSummary = {
  analysisRunId: run.id,
  captureEnabled: true,
  recordingState: "RECORDING",
  completeness: "RECORDING",
  startedAt: "2026-01-01T00:00:00.000Z",
  finishedAt: null,
  totalDurationMillis: 12_000,
};

const spans: ExecutionSpan[] = [
  {
    id: "stage-source", parentSpanId: null, operationId: "source", stageId: "source", kind: "STAGE", name: "Read the PDF",
    startedAt: "2026-01-01T00:00:00.000Z", endedAt: "2026-01-01T00:00:12.000Z", durationMillis: 12_000,
    status: "SUCCEEDED", attempt: 1, providerId: null, modelId: null, httpStatus: null, safeErrorCode: null,
    attributes: {}, artifactRoles: [],
  },
  {
    id: "parse", parentSpanId: "stage-source", operationId: "parse", stageId: "source", kind: "TRANSFORM", name: "Parse source",
    startedAt: "2026-01-01T00:00:00.000Z", endedAt: "2026-01-01T00:00:10.000Z", durationMillis: 10_000,
    status: "SUCCEEDED", attempt: 1, providerId: null, modelId: null, httpStatus: null, safeErrorCode: null,
    attributes: {}, artifactRoles: [],
  },
  {
    id: "model", parentSpanId: "stage-source", operationId: "model", stageId: "source", kind: "PROVIDER_CALL", name: "Model request",
    startedAt: "2026-01-01T00:00:02.000Z", endedAt: "2026-01-01T00:00:12.000Z", durationMillis: 10_000,
    status: "FAILED", attempt: 1, providerId: "local", modelId: "demo-model", httpStatus: 503, safeErrorCode: "PROVIDER_UNAVAILABLE",
    attributes: { trustBoundary: "local", httpRoute: "POST https://private-host.example/v1/chat?secret=hidden" }, artifactRoles: [{ id: "response-artifact", role: "RESPONSE", fidelity: "SANITIZED", reason: null, mediaType: "text/plain", sizeBytes: 44 }],
  },
  {
    id: "model-retry", parentSpanId: "stage-source", operationId: "model", stageId: "source", kind: "PROVIDER_CALL", name: "Model request",
    startedAt: "2026-01-01T00:00:04.000Z", endedAt: "2026-01-01T00:00:11.000Z", durationMillis: 7_000,
    status: "SUCCEEDED", attempt: 2, providerId: "local", modelId: "demo-model", httpStatus: 200, safeErrorCode: null,
    attributes: { trustBoundary: "local" }, artifactRoles: [],
  },
];

const responseArtifact: ExecutionArtifact = {
  id: "response-artifact", spanId: "model", role: "RESPONSE", fidelity: "SANITIZED", reason: null,
  mediaType: "text/plain", content: "<img src=x onerror=alert(1)> sanitized response", schemaVersion: "v1", captureVersion: "v1", sanitizerVersion: "v2", sizeBytes: 44,
};

const detail: ExecutionSpanDetail = {
  ...spans[2],
  artifactRoles: [{ id: responseArtifact.id, role: "RESPONSE", fidelity: "SANITIZED", reason: null, mediaType: "text/plain", sizeBytes: 44 }],
};

function configureExecutionMocks() {
  vi.clearAllMocks();
  executionMocks.useAnalysisRun.mockReturnValue({ data: run, isPending: false, error: null });
  executionMocks.useParsedDocument.mockReturnValue({ data: null, isPending: false, isError: false, error: null });
  executionMocks.useReferenceResolutionReport.mockReturnValue({ data: null, isPending: false, isError: false, error: null });
  executionMocks.useExecutionSummary.mockReturnValue({ data: summary, isPending: false, isError: false, error: null });
  executionMocks.useExecutionSpans.mockReturnValue({
    data: { pages: [{ items: spans, nextCursor: null }] }, isPending: false, isError: false, isFetchingNextPage: false,
    fetchNextPage: vi.fn(),
  });
  executionMocks.useExecutionSpan.mockImplementation((analysisRunId: string, spanId: string | null) => {
    executionMocks.spanRequests(analysisRunId, spanId);
    return {
      data: spanId === "model" ? detail : undefined,
      isPending: Boolean(spanId) && spanId !== "model",
      isError: false,
      error: null,
    };
  });
  executionMocks.useExecutionArtifact.mockImplementation((analysisRunId: string, artifactId: string | null, spanId: string | null, role: string | null) => {
    executionMocks.artifactRequests(analysisRunId, artifactId, spanId, role);
    return {
      data: artifactId === responseArtifact.id ? responseArtifact : undefined,
      isPending: false,
      isError: false,
      error: null,
    };
  });
  executionMocks.useStopExecutionCapture.mockReturnValue({ mutate: executionMocks.stopCapture, isPending: false, isError: false });
  executionMocks.useRemoveExecutionArtifact.mockReturnValue({ mutate: executionMocks.removeArtifact, isPending: false, isError: false });
}

function renderRun() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <WorkspaceShell><AnalysisRunDetailPage analysisRunId={run.id} /></WorkspaceShell>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.stubGlobal("IntersectionObserver", class {
    observe() {}
    unobserve() {}
    disconnect() {}
  });
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
});

describe("Analysis Run Execution view", () => {
  it("selects an operation, keeps its URL selection across views, and renders sanitized payload as inert text", async () => {
    configureExecutionMocks();
    window.history.replaceState(null, "", `/analysis-runs/${run.id}`);
    renderRun();

    fireEvent.click(screen.getByRole("tab", { name: "Execution" }));
    const trace = await screen.findByRole("list", { name: "Execution operations" });
    expect(within(trace).getByText("Read the PDF")).toBeTruthy();
    expect(within(trace).queryByText("Parse source")).toBeTruthy();
    expect(within(trace).getByText("2 attempts")).toBeTruthy();
    expect(within(trace).getByRole("button", { name: /Model request, Failed, 10 sec/i })).toBeTruthy();
    expect(within(trace).getByRole("button", { name: /Model request, Succeeded, 7 sec/i })).toBeTruthy();
    expect(screen.getByText("Elapsed 12 sec")).toBeTruthy();

    fireEvent.click(within(trace).getByRole("button", { name: /Model request, Failed, 10 sec/i }));
    await waitFor(() => expect(new URLSearchParams(window.location.search).get("span")).toBe("model"));
    expect(screen.getByRole("heading", { name: "Model request" })).toBeTruthy();
    expect(screen.getByText("/v1/chat")).toBeTruthy();
    expect(screen.queryByText("private-host.example")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Stop future capture" }));
    expect(executionMocks.stopCapture).toHaveBeenCalledOnce();

    fireEvent.click(screen.getByRole("tab", { name: "Analysis Pipeline" }));
    expect(new URLSearchParams(window.location.search).get("span")).toBe("model");
    fireEvent.click(screen.getByRole("tab", { name: "Execution" }));
    expect(new URLSearchParams(window.location.search).get("span")).toBe("model");

    fireEvent.click(screen.getByRole("tab", { name: "Response" }));
    const sanitizedContent = await screen.findByText("<img src=x onerror=alert(1)> sanitized response");
    expect(sanitizedContent.closest("pre")).toBeTruthy();
    expect(sanitizedContent.querySelector("img")).toBeNull();
    vi.spyOn(window, "confirm").mockReturnValue(true);
    fireEvent.click(screen.getByRole("button", { name: "Remove artifact body" }));
    expect(window.confirm).toHaveBeenCalledWith("Remove this deduplicated artifact body from all linked spans in this Analysis Run? The analysis result will not change.");
    expect(executionMocks.removeArtifact).toHaveBeenCalledWith(responseArtifact.id);
    expect(executionMocks.artifactRequests).toHaveBeenCalledWith(run.id, responseArtifact.id, "model", "RESPONSE");
  });

  it("renders every supported artifact fidelity as a truthful state", async () => {
    configureExecutionMocks();
    const fixtureDetails: Record<string, ExecutionSpanDetail> = {};
    const fixtureArtifacts: Record<string, ExecutionArtifact> = {};
    const fidelityCases = [
      { span: spans[1], artifactId: "complete-artifact", fidelity: "COMPLETE" as const, content: "complete body" },
      { span: spans[2], artifactId: "partial-artifact", fidelity: "PARTIAL" as const, content: "partial body" },
      { span: spans[0], artifactId: "removed-artifact", fidelity: "REMOVED" as const, content: null },
    ];
    for (const { span, artifactId, fidelity, content } of fidelityCases) {
      fixtureDetails[span.id] = {
        ...span,
        artifactRoles: [{
          id: artifactId,
          role: "RESPONSE",
          fidelity,
          reason: fidelity === "REMOVED" ? "ARTIFACT_REMOVED" : null,
          mediaType: content === null ? null : "text/plain",
          sizeBytes: content === null ? null : content.length,
        }],
      };
      fixtureArtifacts[artifactId] = {
        id: artifactId,
        spanId: span.id,
        role: "RESPONSE",
        fidelity,
        reason: fidelity === "REMOVED" ? "ARTIFACT_REMOVED" : null,
        mediaType: content === null ? null : "text/plain",
        content,
        schemaVersion: content === null ? null : "v1",
        captureVersion: content === null ? null : "v1",
        sanitizerVersion: content === null ? null : "v1",
        sizeBytes: content === null ? null : content.length,
      };
    }
    fixtureDetails["model-retry"] = {
      ...spans[3],
      artifactRoles: [{ id: null, role: "RESPONSE", fidelity: "UNAVAILABLE", reason: "BODY_UNAVAILABLE", mediaType: null, sizeBytes: null }],
    };
    executionMocks.useExecutionSpan.mockImplementation((analysisRunId: string, spanId: string | null) => {
      executionMocks.spanRequests(analysisRunId, spanId);
      const data = spanId ? fixtureDetails[spanId] : undefined;
      return { data, isPending: Boolean(spanId) && !data, isError: false, error: null };
    });
    executionMocks.useExecutionArtifact.mockImplementation((analysisRunId: string, artifactId: string | null, spanId: string | null, role: string | null) => {
      executionMocks.artifactRequests(analysisRunId, artifactId, spanId, role);
      return { data: artifactId ? fixtureArtifacts[artifactId] : undefined, isPending: false, isError: false, error: null };
    });
    window.history.replaceState(null, "", `/analysis-runs/${run.id}?view=execution`);
    renderRun();

    for (const { span, fidelity, content } of fidelityCases) {
      const rowName = span.id === "model"
        ? /Model request, Failed, 10 sec/
        : span.id === "model-retry"
          ? /Model request, Succeeded, 7 sec/
          : new RegExp(`${span.name}, Succeeded`);
      fireEvent.click(await screen.findByRole("button", { name: rowName }));
      fireEvent.click(await screen.findByRole("tab", { name: "Response" }));
      expect(await screen.findByText(fidelity[0] + fidelity.slice(1).toLowerCase())).toBeTruthy();
      if (content) expect(screen.getByText(content).closest("pre")).toBeTruthy();
      else expect(screen.getByText("This artifact was removed. Its captured content is no longer available.")).toBeTruthy();
    }

    fireEvent.click(await screen.findByRole("button", { name: /Model request, Succeeded/ }));
    fireEvent.click(await screen.findByRole("tab", { name: "Response" }));
    expect(await screen.findByText("Unavailable")).toBeTruthy();
    expect(screen.getByText("BODY_UNAVAILABLE")).toBeTruthy();
  });

  it("reports historical unavailability for legacy NOT_RECORDED runs even when captureEnabled is false", async () => {
    configureExecutionMocks();
    executionMocks.useExecutionSummary.mockReturnValue({
      data: {
        ...summary,
        captureEnabled: false,
        recordingState: "NOT_RECORDED",
        completeness: "NOT_RECORDED",
        startedAt: null,
        finishedAt: null,
        totalDurationMillis: null,
      },
      isPending: false,
      isError: false,
      error: null,
    });
    window.history.replaceState(null, "", `/analysis-runs/${run.id}?view=execution`);
    renderRun();

    expect(await screen.findByText("Historical execution unavailable")).toBeTruthy();
    expect(screen.queryByText("Execution capture disabled")).toBeNull();
    expect(screen.getByText(/predates execution recording/)).toBeTruthy();
  });

  it("renders an omitted nullable descriptor without requesting content or crashing the inspector", async () => {
    configureExecutionMocks();
    const omittedDetail: ExecutionSpanDetail = {
      ...detail,
      artifactRoles: [{ id: null, role: "REQUEST", fidelity: "OMITTED", reason: "CAPTURE_DISABLED", mediaType: null, sizeBytes: null }],
    };
    executionMocks.useExecutionSpan.mockImplementation((analysisRunId: string, spanId: string | null) => {
      executionMocks.spanRequests(analysisRunId, spanId);
      return { data: spanId === "model" ? omittedDetail : undefined, isPending: false, isError: false, error: null };
    });
    window.history.replaceState(null, "", `/analysis-runs/${run.id}?view=execution&span=model`);
    renderRun();

    fireEvent.click(await screen.findByRole("tab", { name: "Input / Request" }));
    expect(await screen.findByText("Omitted")).toBeTruthy();
    expect(screen.getAllByText("CAPTURE_DISABLED").length).toBeGreaterThan(0);
    expect(screen.getByText("Media type unknown · Unknown size bytes")).toBeTruthy();
    expect(executionMocks.artifactRequests).toHaveBeenCalledWith(run.id, null, "model", "REQUEST");
    expect(screen.queryByLabelText("Loading captured artifact")).toBeNull();
  });

  it("does not display a deduplicated artifact association from a different linked span", async () => {
    configureExecutionMocks();
    const artifactFromAnotherSpan = { ...responseArtifact, spanId: "another-linked-span" };
    executionMocks.useExecutionArtifact.mockImplementation((analysisRunId: string, artifactId: string | null, spanId: string | null, role: string | null) => {
      executionMocks.artifactRequests(analysisRunId, artifactId, spanId, role);
      return { data: artifactId === responseArtifact.id ? artifactFromAnotherSpan : undefined, isPending: false, isError: false, error: null };
    });
    window.history.replaceState(null, "", `/analysis-runs/${run.id}?view=execution&span=model`);
    renderRun();

    fireEvent.click(await screen.findByRole("tab", { name: "Response" }));
    expect(await screen.findByText("The artifact response did not match this operation and is unavailable.")).toBeTruthy();
    expect(screen.queryByText(responseArtifact.content!)).toBeNull();
    expect(executionMocks.artifactRequests).toHaveBeenCalledWith(run.id, responseArtifact.id, "model", "RESPONSE");
  });

  it("renders a deep-linked span beyond the first cursor page with bounded ancestor context", async () => {
    configureExecutionMocks();
    const selectedSpan: ExecutionSpanDetail = {
      ...spans[1],
      id: "selected-page-two",
      parentSpanId: "parent-page-two",
      name: "Selected beyond first page",
      artifactRoles: [],
    };
    const pageTwoParent: ExecutionSpanDetail = {
      ...spans[1],
      id: "parent-page-two",
      parentSpanId: "stage-source",
      operationId: "parent-operation",
      name: "Bounded ancestor context",
      artifactRoles: [],
    };
    executionMocks.useExecutionSpan.mockImplementation((analysisRunId: string, spanId: string | null) => {
      executionMocks.spanRequests(analysisRunId, spanId);
      return { data: spanId === selectedSpan.id ? selectedSpan : undefined, isPending: false, isError: false, error: null };
    });
    const fetchNextPage = vi.fn();
    executionMocks.useExecutionSpans.mockReturnValue({
      data: { pages: [{ items: [spans[0]], nextCursor: "page-two-cursor" }] },
      isPending: false, isError: false, isFetchingNextPage: false, fetchNextPage,
    });
    const fetch = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === `/api/v1/analysis-runs/${run.id}/execution/spans/${pageTwoParent.id}`) {
        return new Response(JSON.stringify(pageTwoParent), { status: 200, headers: { "content-type": "application/json" } });
      }
      throw new Error(`Unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetch);
    window.history.replaceState(null, "", `/analysis-runs/${run.id}?view=execution&span=${selectedSpan.id}`);
    renderRun();

    const trace = await screen.findByRole("list", { name: "Execution operations" });
    const selectedButton = await within(trace).findByRole("button", { name: /Selected beyond first page/ });
    expect(selectedButton.getAttribute("aria-pressed")).toBe("true");
    expect(within(trace).getByText("Bounded ancestor context")).toBeTruthy();
    expect(within(trace).getByText("Read the PDF")).toBeTruthy();
    expect(fetch).toHaveBeenCalledWith(`/api/v1/analysis-runs/${run.id}/execution/spans/${pageTwoParent.id}`, expect.objectContaining({ cache: "no-store" }));
    expect(executionMocks.spanRequests).toHaveBeenCalledWith(run.id, selectedSpan.id);
    expect(fetchNextPage).not.toHaveBeenCalled();
  });

  it("keeps matching descendants visible with their stage and parent context", async () => {
    configureExecutionMocks();
    window.history.replaceState(null, "", `/analysis-runs/${run.id}?view=execution`);
    renderRun();

    const search = await screen.findByRole("textbox", { name: "Find operation" });
    fireEvent.change(search, { target: { value: "Model request" } });
    const trace = screen.getByRole("list", { name: "Execution operations" });
    expect(within(trace).getByText("Read the PDF")).toBeTruthy();
    expect(within(trace).getAllByText("Model request").length).toBeGreaterThan(0);
    expect(within(trace).queryByText("Parse source")).toBeNull();
  });
});
