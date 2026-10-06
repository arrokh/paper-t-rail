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
  refetchSummary: vi.fn(),
  refetchSpans: vi.fn(),
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
  captureRequested: true,
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
    attributes: {}, trustBoundary: "INTERNAL", httpRoute: null, domainLinks: [], artifactRoles: [],
  },
  {
    id: "parse", parentSpanId: "stage-source", operationId: "parse", stageId: "source", kind: "TRANSFORM", name: "Parse source",
    startedAt: "2026-01-01T00:00:00.000Z", endedAt: "2026-01-01T00:00:10.000Z", durationMillis: 10_000,
    status: "SUCCEEDED", attempt: 1, providerId: null, modelId: null, httpStatus: null, safeErrorCode: null,
    attributes: {}, trustBoundary: "INTERNAL", httpRoute: null, domainLinks: [], artifactRoles: [],
  },
  {
    id: "model", parentSpanId: "stage-source", operationId: "model", stageId: "source", kind: "PROVIDER_CALL", name: "Model request",
    startedAt: "2026-01-01T00:00:02.000Z", endedAt: "2026-01-01T00:00:12.000Z", durationMillis: 10_000,
    status: "FAILED", attempt: 1, providerId: "local", modelId: "demo-model", httpStatus: 503, safeErrorCode: "PROVIDER_UNAVAILABLE",
    attributes: {}, trustBoundary: "LOCAL", httpRoute: "POST https://private-host.example/v1/chat?secret=hidden", domainLinks: [], artifactRoles: [{ id: "response-artifact", role: "RESPONSE", fidelity: "SANITIZED", reason: null, mediaType: "text/plain", sizeBytes: 44 }],
  },
  {
    id: "model-retry", parentSpanId: "stage-source", operationId: "model", stageId: "source", kind: "PROVIDER_CALL", name: "Model request",
    startedAt: "2026-01-01T00:00:04.000Z", endedAt: "2026-01-01T00:00:11.000Z", durationMillis: 7_000,
    status: "SUCCEEDED", attempt: 2, providerId: "local", modelId: "demo-model", httpStatus: 200, safeErrorCode: null,
    attributes: {}, trustBoundary: "LOCAL", httpRoute: null, domainLinks: [], artifactRoles: [],
  },
  {
    id: "event-queue", parentSpanId: "stage-source", operationId: "analysis-event", stageId: "source", kind: "QUEUE", name: "Queue wait",
    startedAt: "2026-01-01T00:00:00.000Z", endedAt: "2026-01-01T00:00:00.100Z", durationMillis: 100,
    status: "SUCCEEDED", attempt: 1, providerId: null, modelId: null, httpStatus: null, safeErrorCode: null,
    attributes: {}, trustBoundary: "LOCAL", httpRoute: null, domainLinks: [], artifactRoles: [],
  },
  {
    id: "event-processing", parentSpanId: "stage-source", operationId: "analysis-event", stageId: "source", kind: "QUEUE", name: "Source analysis attempt",
    startedAt: "2026-01-01T00:00:00.100Z", endedAt: "2026-01-01T00:00:12.000Z", durationMillis: 11_900,
    status: "SUCCEEDED", attempt: 1, providerId: null, modelId: null, httpStatus: null, safeErrorCode: null,
    attributes: {}, trustBoundary: "LOCAL", httpRoute: null, domainLinks: [], artifactRoles: [],
  },
];

const responseArtifact: ExecutionArtifact = {
  id: "response-artifact", spanId: "model", role: "RESPONSE", fidelity: "SANITIZED", reason: null,
  mediaType: "text/plain", content: "<img src=x onerror=alert(1)> sanitized response", schemaVersion: "v1", captureVersion: "v1", sanitizerVersion: "v2", sizeBytes: 44,
};

const detail: ExecutionSpanDetail = {
  ...spans[2],
  artifactRoles: [{ id: responseArtifact.id, role: "RESPONSE", fidelity: "SANITIZED", reason: null, mediaType: "text/plain", sizeBytes: 44 }],
  domainLinks: [{ type: "BIBLIOGRAPHY_ENTRY", id: "b20f6121-a3d5-4d8b-a3bd-2c1ddc10ea98", href: "/api/v1/analysis-runs/execution-run/report" }],
};

function configureExecutionMocks() {
  vi.clearAllMocks();
  executionMocks.useAnalysisRun.mockReturnValue({ data: run, isPending: false, error: null });
  executionMocks.useParsedDocument.mockReturnValue({ data: null, isPending: false, isError: false, error: null });
  executionMocks.useReferenceResolutionReport.mockReturnValue({ data: null, isPending: false, isError: false, error: null });
  executionMocks.refetchSummary.mockReset();
  executionMocks.useExecutionSummary.mockReturnValue({ data: summary, isPending: false, isError: false, error: null, refetch: executionMocks.refetchSummary });
  executionMocks.refetchSpans.mockReset();
  executionMocks.useExecutionSpans.mockReturnValue({
    data: { pages: [{ items: spans, nextCursor: null }] }, isPending: false, isError: false, isFetchingNextPage: false,
    fetchNextPage: vi.fn(), refetch: executionMocks.refetchSpans,
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
  const tree = () => (
    <QueryClientProvider client={queryClient}>
      <WorkspaceShell><AnalysisRunDetailPage analysisRunId={run.id} /></WorkspaceShell>
    </QueryClientProvider>
  );
  const rendered = render(tree());
  return { ...rendered, rerenderRun: () => rendered.rerender(tree()) };
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

    const executionTab = screen.getByRole("tab", { name: "Execution Trace" });
    fireEvent.click(executionTab);
    const trace = await screen.findByRole("list", { name: "Execution operations" });
    const controlledPanelId = executionTab.getAttribute("aria-controls");
    expect(controlledPanelId).toBeTruthy();
    expect(controlledPanelId && document.getElementById(controlledPanelId)).toBeTruthy();
    expect(within(trace).getByText("Read the PDF")).toBeTruthy();
    expect(within(trace).queryByText("Parse source")).toBeTruthy();
    expect(within(trace).getByText("2 attempts")).toBeTruthy();
    expect(within(trace).queryByRole("button", { name: /Queue wait 2 attempts/i })).toBeNull();
    expect(within(trace).getByRole("button", { name: /Queue wait, Succeeded, 100 ms/i })).toBeTruthy();
    expect(within(trace).getByRole("button", { name: /Source analysis attempt, Succeeded, 11.9 sec/i })).toBeTruthy();
    expect(within(trace).getByRole("button", { name: /Model request, Failed, 10 sec/i })).toBeTruthy();
    expect(within(trace).getByRole("button", { name: /Model request, Succeeded, 7 sec/i })).toBeTruthy();
    expect(screen.getByText("Elapsed").nextElementSibling?.textContent).toBe("12 sec");

    fireEvent.click(within(trace).getByRole("button", { name: /Model request, Failed, 10 sec/i }));
    await waitFor(() => expect(new URLSearchParams(window.location.search).get("span")).toBe("model"));
    expect(screen.getByRole("heading", { name: "Model request" })).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Overview", level: 4 })).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Input / Request", level: 4 })).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Response", level: 4 })).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Result", level: 4 })).toBeTruthy();
    expect(screen.queryByRole("tab", { name: "Response" })).toBeNull();
    expect(screen.getByText("/v1/chat")).toBeTruthy();
    expect(screen.getByText("Trust boundary").nextElementSibling?.textContent).toBe("local");
    const startedAtElement = screen.getByText("Started").nextElementSibling?.querySelector("time");
    expect(startedAtElement?.getAttribute("dateTime")).toBe(detail.startedAt);
    expect(startedAtElement?.textContent).not.toBe(detail.startedAt);
    expect(screen.getByRole("link", { name: "Bibliography entry · b20f6121" })).toBeTruthy();
    expect(screen.queryByText("private-host.example")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Stop future capture" }));
    expect(executionMocks.stopCapture).toHaveBeenCalledOnce();

    fireEvent.click(screen.getByRole("tab", { name: "Analysis Pipeline" }));
    expect(new URLSearchParams(window.location.search).get("span")).toBe("model");
    fireEvent.click(screen.getByRole("tab", { name: "Execution Trace" }));
    expect(new URLSearchParams(window.location.search).get("span")).toBe("model");

    const sanitizedContent = await screen.findByText("<img src=x onerror=alert(1)> sanitized response");
    expect(sanitizedContent.closest("pre")).toBeTruthy();
    expect(sanitizedContent.querySelector("img")).toBeNull();
    vi.spyOn(window, "confirm").mockReturnValue(true);
    fireEvent.click(screen.getByRole("button", { name: "Remove artifact body" }));
    expect(window.confirm).toHaveBeenCalledWith("Remove this deduplicated artifact body from all linked spans in this Analysis Run? The analysis result will not change.");
    expect(executionMocks.removeArtifact).toHaveBeenCalledWith(responseArtifact.id);
    expect(executionMocks.artifactRequests).toHaveBeenCalledWith(run.id, responseArtifact.id, "model", "RESPONSE");
  });

  it("refreshes a still-recording summary once after the Analysis Run becomes terminal", async () => {
    configureExecutionMocks();
    executionMocks.useAnalysisRun.mockReturnValue({
      data: { ...run, status: "COMPLETED_WITH_WARNINGS" },
      isPending: false,
      error: null,
    });
    window.history.replaceState(null, "", `/analysis-runs/${run.id}?view=execution`);
    renderRun();

    await waitFor(() => expect(executionMocks.refetchSummary).toHaveBeenCalledOnce());
  });

  it("refreshes the complete span list once when a run becomes terminal", async () => {
    configureExecutionMocks();
    let currentRun = run;
    executionMocks.useAnalysisRun.mockImplementation(() => ({ data: currentRun, isPending: false, error: null }));
    window.history.replaceState(null, "", `/analysis-runs/${run.id}?view=execution`);
    const rendered = renderRun();

    expect(executionMocks.refetchSpans).not.toHaveBeenCalled();
    currentRun = { ...run, status: "PARSED" };
    rendered.rerenderRun();
    await waitFor(() => expect(executionMocks.refetchSpans).toHaveBeenCalledOnce());
    rendered.rerenderRun();
    expect(executionMocks.refetchSpans).toHaveBeenCalledOnce();
  });

  it("announces operation status changes without announcing unchanged polling updates", async () => {
    configureExecutionMocks();
    let currentSpans = spans;
    executionMocks.useExecutionSpans.mockImplementation(() => ({
      data: { pages: [{ items: currentSpans, nextCursor: null }] },
      isPending: false,
      isError: false,
      isFetchingNextPage: false,
      fetchNextPage: vi.fn(),
    }));
    window.history.replaceState(null, "", `/analysis-runs/${run.id}?view=execution`);
    const rendered = renderRun();
    const announcement = screen.getByRole("status");
    expect(announcement.textContent).toBe("");

    currentSpans = spans.map((span) => span.id === "model"
      ? { ...span, status: "RUNNING", endedAt: null, durationMillis: null }
      : span);
    rendered.rerenderRun();
    await waitFor(() => expect(announcement.textContent).toBe("Execution status changed: Model request: running."));

    rendered.rerenderRun();
    expect(announcement.textContent).toBe("Execution status changed: Model request: running.");
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
      expect(await screen.findByText(fidelity[0] + fidelity.slice(1).toLowerCase())).toBeTruthy();
      if (content) expect(screen.getByText(content).closest("pre")).toBeTruthy();
      else expect(screen.getByText("This artifact was removed. Its captured content is no longer available.")).toBeTruthy();
    }

    fireEvent.click(await screen.findByRole("button", { name: /Model request, Succeeded/ }));
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

  it("keeps the original capture choice visible after future capture stops", async () => {
    configureExecutionMocks();
    executionMocks.useExecutionSummary.mockReturnValue({
      data: {
        ...summary,
        captureRequested: true,
        captureEnabled: false,
        recordingState: "STOPPED",
        completeness: "COMPLETE",
        finishedAt: "2026-01-01T00:00:12.000Z",
      },
      isPending: false,
      isError: false,
      error: null,
    });
    window.history.replaceState(null, "", `/analysis-runs/${run.id}?view=execution`);
    renderRun();

    expect(await screen.findByText("Recording stopped")).toBeTruthy();
    expect(screen.getByText("Payload capture at start").nextElementSibling?.textContent).toBe("Enabled");
    expect(screen.getByText(/Execution recording was stopped/)).toBeTruthy();
  });

  it("distinguishes a completed recording from a manual stop", async () => {
    configureExecutionMocks();
    executionMocks.useAnalysisRun.mockReturnValue({
      data: { ...run, status: "COMPLETED" },
      isPending: false,
      error: null,
    });
    executionMocks.useExecutionSummary.mockReturnValue({
      data: {
        ...summary,
        captureRequested: true,
        captureEnabled: true,
        recordingState: "STOPPED",
        completeness: "INCOMPLETE",
        finishedAt: "2026-01-01T00:00:12.000Z",
      },
      isPending: false,
      isError: false,
      error: null,
    });
    window.history.replaceState(null, "", `/analysis-runs/${run.id}?view=execution`);
    renderRun();

    expect(await screen.findByText("Recording complete")).toBeTruthy();
    expect(screen.queryByText("Recording stopped")).toBeNull();
    expect(screen.queryByText(/Execution recording was stopped/)).toBeNull();
    expect(screen.getByText("Incomplete trace")).toBeTruthy();
    expect(screen.getByText("The recording is marked incomplete. Missing spans are not reconstructed.")).toBeTruthy();
  });

  it("renders an omitted nullable descriptor without requesting content or crashing the inspector", async () => {
    configureExecutionMocks();
    const omittedDetail: ExecutionSpanDetail = {
      ...detail,
      artifactRoles: [{ id: null, role: "REQUEST", fidelity: "OMITTED", reason: "UNSUPPORTED_OR_UNSAFE_FIELDS", mediaType: null, sizeBytes: null }],
    };
    executionMocks.useExecutionSpan.mockImplementation((analysisRunId: string, spanId: string | null) => {
      executionMocks.spanRequests(analysisRunId, spanId);
      return { data: spanId === "model" ? omittedDetail : undefined, isPending: false, isError: false, error: null };
    });
    window.history.replaceState(null, "", `/analysis-runs/${run.id}?view=execution&span=model`);
    renderRun();

    expect(await screen.findByText("Omitted")).toBeTruthy();
    expect(screen.queryByText("UNSUPPORTED_OR_UNSAFE_FIELDS")).toBeNull();
    expect(screen.getByText("No artifact content was captured for this operation.")).toBeTruthy();
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

  it("filters the trace by a canonical pipeline stage and omits unrelated operations", async () => {
    configureExecutionMocks();
    const referenceSpan: ExecutionSpan = {
      ...spans[1],
      id: "reference-operation",
      operationId: "reference-operation",
      stageId: "references",
      name: "Resolve references",
    };
    executionMocks.useExecutionSpans.mockReturnValue({
      data: { pages: [{ items: [...spans, referenceSpan], nextCursor: null }] },
      isPending: false,
      isError: false,
      isFetchingNextPage: false,
      fetchNextPage: vi.fn(),
    });
    window.history.replaceState(null, "", `/analysis-runs/${run.id}?view=execution`);
    renderRun();

    const stageFilter = await screen.findByRole("combobox", { name: "Pipeline stage" });
    fireEvent.change(stageFilter, { target: { value: "references" } });
    const trace = screen.getByRole("list", { name: "Execution operations" });
    expect(within(trace).getByText("Resolve references")).toBeTruthy();
    expect(within(trace).queryByText("Parse source")).toBeNull();
    expect(screen.queryByRole("button", { name: "Load more operations" })).toBeNull();
  });

  it("keeps matching descendants visible with their stage and parent context", async () => {
    configureExecutionMocks();
    window.history.replaceState(null, "", `/analysis-runs/${run.id}?view=execution`);
    renderRun();

    const search = await screen.findByRole("textbox", { name: "Find operation" });
    fireEvent.change(search, { target: { value: "Model request" } });
    const trace = screen.getByRole("list", { name: "Execution operations" });
    expect(within(trace).getByText("Pipeline stage: Read the PDF")).toBeTruthy();
    expect(within(trace).getAllByText("Model request").length).toBeGreaterThan(0);
    expect(within(trace).queryByText("Parse source")).toBeNull();
  });

  it("wraps long operation labels and aligns Duration on a fixed-width track on mobile and desktop", async () => {
    configureExecutionMocks();
    const longName = "Unbroken-operation-label-" + "x".repeat(180);
    const longSpan: ExecutionSpan = {
      ...spans[0],
      id: "long-operation",
      parentSpanId: null,
      operationId: "long-operation",
      kind: "TRANSFORM",
      name: longName,
      status: "RUNNING",
      endedAt: null,
      durationMillis: null,
    };
    executionMocks.useExecutionSpans.mockReturnValue({
      data: { pages: [{ items: [longSpan], nextCursor: null }] },
      isPending: false,
      isError: false,
      isFetchingNextPage: false,
      fetchNextPage: vi.fn(),
    });
    window.history.replaceState(null, "", `/analysis-runs/${run.id}?view=execution`);
    renderRun();

    const trace = await screen.findByRole("list", { name: "Execution operations" });
    const operation = within(trace).getByRole("button", { name: new RegExp(`^${longName}, Running`) });
    expect(operation.textContent).toContain(longName);
    expect(operation.className).toContain("whitespace-normal");
    expect(operation.className).not.toContain("overflow-x-auto");
    expect(within(operation).getByText(longName).className).toContain("[overflow-wrap:anywhere]");

    const rowGrid = operation.closest(".grid");
    const rulerGrid = screen.getByText("Duration").closest(".grid");
    expect(rowGrid?.className).toContain("grid-cols-[minmax(0,1fr)_5rem]");
    expect(rowGrid?.className).toContain("sm:grid-cols-[minmax(12rem,1.2fr)_5rem_minmax(12rem,2fr)]");
    expect(rulerGrid?.className).toContain("grid-cols-[minmax(0,1fr)_5rem]");
    expect(rulerGrid?.className).toContain("sm:grid-cols-[minmax(12rem,1.2fr)_5rem_minmax(12rem,2fr)]");
  });

  it.each([
    { sizeBytes: 64 * 1024, opensDialog: false },
    { sizeBytes: 64 * 1024 + 1, opensDialog: true },
  ])("keeps an artifact of $sizeBytes bytes inline only at or below the 64 KiB threshold", async ({ sizeBytes, opensDialog }) => {
    configureExecutionMocks();
    const prefix = "<img src=x onerror=alert(1)>";
    const content = `${prefix}${"x".repeat(sizeBytes - prefix.length)}`;
    const storedArtifact: ExecutionArtifact = { ...responseArtifact, content, sizeBytes };
    const storedDetail: ExecutionSpanDetail = {
      ...detail,
      artifactRoles: [{ ...detail.artifactRoles[0], sizeBytes }],
    };
    executionMocks.useExecutionSpan.mockImplementation((analysisRunId: string, spanId: string | null) => {
      executionMocks.spanRequests(analysisRunId, spanId);
      return { data: spanId === "model" ? storedDetail : undefined, isPending: false, isError: false, error: null };
    });
    executionMocks.useExecutionArtifact.mockImplementation((analysisRunId: string, artifactId: string | null, spanId: string | null, role: string | null) => {
      executionMocks.artifactRequests(analysisRunId, artifactId, spanId, role);
      return { data: artifactId === storedArtifact.id ? storedArtifact : undefined, isPending: false, isError: false, error: null };
    });
    window.history.replaceState(null, "", `/analysis-runs/${run.id}?view=execution&span=model`);
    renderRun();

    const artifactArea = await screen.findByRole("region", { name: "response artifact" });
    expect(executionMocks.artifactRequests).toHaveBeenCalledWith(run.id, storedArtifact.id, "model", "RESPONSE");
    if (!opensDialog) {
      expect(within(artifactArea).queryByRole("button", { name: "Open full artifact" })).toBeNull();
      const inlineContent = Array.from(artifactArea.querySelectorAll("pre")).find((pre) => pre.textContent === content);
      expect(inlineContent).toBeTruthy();
      expect(inlineContent?.querySelector("img")).toBeNull();
      return;
    }

    const openButton = within(artifactArea).getByRole("button", { name: "Open full artifact" });
    expect(Array.from(artifactArea.querySelectorAll("pre")).some((pre) => pre.textContent === content)).toBe(false);
    fireEvent.click(openButton);
    const dialog = await screen.findByRole("dialog", { name: "Full response artifact" });
    const fullText = within(dialog).getByLabelText("response artifact content");
    expect(fullText.tagName).toBe("PRE");
    expect(fullText.textContent).toBe(content);
    expect(fullText.getAttribute("tabindex")).toBe("0");
    expect(fullText.className).toContain("overflow-auto");
    expect(fullText.className).toContain("select-text");
    expect(fullText.querySelector("img")).toBeNull();
  });

  it("groups operations by one of the canonical pipeline stage IDs and keeps unknown stages in Other", async () => {
    configureExecutionMocks();
    const stageAssignments = [
      { id: "source-operation", stageId: "source", operationId: "references", name: "Resolve references operation", group: "Read the PDF" },
      { id: "references-operation", stageId: "references", operationId: "source", name: "Read the PDF operation", group: "Resolve references" },
      { id: "access-operation", stageId: "access", operationId: "verification", name: "Acquire source operation", group: "Acquire cited sources" },
      { id: "evidence-operation", stageId: "evidence", operationId: "access", name: "Evidence indexing operation", group: "Prepare evidence" },
      { id: "verification-operation", stageId: "verification", operationId: "evidence", name: "Claim assessment operation", group: "Assess evidence" },
      { id: "unknown-operation", stageId: "future-analysis-stage", operationId: "access", name: "Future stage operation", group: "Other operations" },
    ];
    const assignedSpans: ExecutionSpan[] = stageAssignments.map((assignment) => ({
      ...spans[0],
      id: assignment.id,
      parentSpanId: null,
      operationId: assignment.operationId,
      stageId: assignment.stageId,
      kind: "TRANSFORM",
      name: assignment.name,
    }));
    executionMocks.useExecutionSpans.mockReturnValue({
      data: { pages: [{ items: assignedSpans, nextCursor: null }] },
      isPending: false,
      isError: false,
      isFetchingNextPage: false,
      fetchNextPage: vi.fn(),
    });
    window.history.replaceState(null, "", `/analysis-runs/${run.id}?view=execution`);
    renderRun();

    const trace = await screen.findByRole("list", { name: "Execution operations" });
    for (const { group, name } of stageAssignments) {
      const heading = within(trace).getByRole("button", { name: new RegExp(group === "Other operations" ? "Other operations" : `Pipeline stage: ${group}`) });
      const stageGroup = heading.closest('[role="listitem"]');
      expect(stageGroup).toBeTruthy();
      expect(within(stageGroup as HTMLElement).getByRole("button", { name: new RegExp(`${name}, Succeeded`) })).toBeTruthy();
    }
    expect(within(trace).queryByRole("button", { name: /Pipeline stage: Future analysis stage/ })).toBeNull();
  });

  it("shows canonical stage groups for nested operations that cross stage boundaries", async () => {
    configureExecutionMocks();
    const nestedStages = [
      { id: "nested-source", stageId: "source", parentSpanId: null, name: "Source operation", label: "Read the PDF" },
      { id: "nested-references", stageId: "references", parentSpanId: "nested-source", name: "Reference operation", label: "Resolve references" },
      { id: "nested-access", stageId: "access", parentSpanId: "nested-references", name: "Access operation", label: "Acquire cited sources" },
      { id: "nested-evidence", stageId: "evidence", parentSpanId: "nested-access", name: "Evidence operation", label: "Prepare evidence" },
      { id: "nested-verification", stageId: "verification", parentSpanId: "nested-evidence", name: "Verification operation", label: "Assess evidence" },
    ];
    const nestedSpans: ExecutionSpan[] = nestedStages.map((stage) => ({
      ...spans[0],
      id: stage.id,
      parentSpanId: stage.parentSpanId,
      operationId: `${stage.id}-operation`,
      stageId: stage.stageId,
      kind: "INTERNAL",
      name: stage.name,
      status: "SUCCEEDED",
    }));
    executionMocks.useExecutionSpans.mockReturnValue({
      data: { pages: [{ items: nestedSpans, nextCursor: null }] },
      isPending: false,
      isError: false,
      isFetchingNextPage: false,
      fetchNextPage: vi.fn(),
    });
    window.history.replaceState(null, "", `/analysis-runs/${run.id}?view=execution`);
    renderRun();

    const trace = await screen.findByRole("list", { name: "Execution operations" });
    expect(within(trace).getAllByRole("button", { name: /^Pipeline stage:/ })).toHaveLength(5);
    for (const { label, name } of nestedStages) {
      const heading = within(trace).getByRole("button", { name: new RegExp(`Pipeline stage: ${label}`) });
      const stageGroup = heading.closest('[role="listitem"]');
      expect(stageGroup).toBeTruthy();
      expect(within(stageGroup as HTMLElement).getByRole("button", { name: new RegExp(`${name}, Succeeded`) })).toBeTruthy();
      expect(within(trace).getAllByRole("button", { name: new RegExp(`${name}, Succeeded`) })).toHaveLength(1);
    }
  });
});
