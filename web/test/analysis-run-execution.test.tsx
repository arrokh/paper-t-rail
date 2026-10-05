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
    attributes: { trustBoundary: "local", httpRoute: "POST https://private-host.example/v1/chat?secret=hidden" }, artifactRoles: ["response"],
  },
  {
    id: "model-retry", parentSpanId: "stage-source", operationId: "model", stageId: "source", kind: "PROVIDER_CALL", name: "Model request",
    startedAt: "2026-01-01T00:00:04.000Z", endedAt: "2026-01-01T00:00:11.000Z", durationMillis: 7_000,
    status: "SUCCEEDED", attempt: 2, providerId: "local", modelId: "demo-model", httpStatus: 200, safeErrorCode: null,
    attributes: { trustBoundary: "local" }, artifactRoles: [],
  },
];

const responseArtifact: ExecutionArtifact = {
  id: "response-artifact", spanId: "model", role: "response", fidelity: "SANITIZED", reason: null,
  mediaType: "text/plain", content: "<img src=x onerror=alert(1)> sanitized response", schemaVersion: "v1", captureVersion: "v1", sanitizerVersion: "v2", sizeBytes: 44,
};

const detail: ExecutionSpanDetail = {
  ...spans[2],
  artifactDescriptors: [{ id: responseArtifact.id, role: "response", fidelity: "SANITIZED", reason: null, mediaType: "text/plain", sizeBytes: 44 }],
};

function configureExecutionMocks() {
  executionMocks.useAnalysisRun.mockReturnValue({ data: run, isPending: false, error: null });
  executionMocks.useParsedDocument.mockReturnValue({ data: null, isPending: false, isError: false, error: null });
  executionMocks.useReferenceResolutionReport.mockReturnValue({ data: null, isPending: false, isError: false, error: null });
  executionMocks.useExecutionSummary.mockReturnValue({ data: summary, isPending: false, isError: false, error: null });
  executionMocks.useExecutionSpans.mockReturnValue({
    data: { pages: [{ items: spans, nextCursor: null }] }, isPending: false, isError: false, isFetchingNextPage: false,
    fetchNextPage: vi.fn(),
  });
  executionMocks.useExecutionSpan.mockImplementation((_runId: string, spanId: string | null) => ({
    data: spanId === "model" ? detail : undefined,
    isPending: Boolean(spanId) && spanId !== "model",
    isError: false,
    error: null,
  }));
  executionMocks.useExecutionArtifact.mockImplementation((_runId: string, artifactId: string | null) => ({
    data: artifactId === responseArtifact.id ? responseArtifact : undefined,
    isPending: false,
    isError: false,
    error: null,
  }));
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
    expect(executionMocks.removeArtifact).toHaveBeenCalledWith(responseArtifact.id);
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
