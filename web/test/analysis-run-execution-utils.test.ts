import { describe, expect, it } from "vitest";
import {
  executionGapReasonDescription,
  filterExecutionSpans,
  formatExecutionDuration,
  getExecutionAncestorContext,
  MAX_SELECTED_SPAN_ANCESTORS,
  getExecutionTimeline,
} from "@/features/analysis-runs/execution/execution-trace-utils";
import type { ExecutionSpan, ExecutionSummary } from "@/features/analysis-runs/execution/execution-types";
import { executionSpansQueryOptions, executionSummaryQueryOptions } from "@/features/analysis-runs/queries/analysis-run-queries";

const spans: ExecutionSpan[] = [
  {
    id: "stage", parentSpanId: null, operationId: "stage", stageId: "source", kind: "STAGE", name: "Read the PDF",
    startedAt: "2026-01-01T00:00:00.000Z", endedAt: "2026-01-01T00:00:12.000Z", durationMillis: 12_000,
    status: "SUCCEEDED", attempt: 1, providerId: null, modelId: null, httpStatus: null, safeErrorCode: null,
    attributes: {}, trustBoundary: "INTERNAL", httpRoute: null, domainLinks: [], artifactRoles: [],
  },
  {
    id: "child-a", parentSpanId: "stage", operationId: "parse", stageId: "source", kind: "TRANSFORM", name: "Parse source",
    startedAt: "2026-01-01T00:00:00.000Z", endedAt: "2026-01-01T00:00:10.000Z", durationMillis: 10_000,
    status: "SUCCEEDED", attempt: 1, providerId: null, modelId: null, httpStatus: null, safeErrorCode: null,
    attributes: {}, trustBoundary: "INTERNAL", httpRoute: null, domainLinks: [], artifactRoles: [],
  },
  {
    id: "child-b", parentSpanId: "stage", operationId: "model", stageId: "source", kind: "PROVIDER_CALL", name: "Model request",
    startedAt: "2026-01-01T00:00:02.000Z", endedAt: "2026-01-01T00:00:12.000Z", durationMillis: 10_000,
    status: "FAILED", attempt: 1, providerId: "local", modelId: "test-model", httpStatus: 503, safeErrorCode: "PROVIDER_UNAVAILABLE",
    attributes: {}, trustBoundary: "LOCAL", httpRoute: null, domainLinks: [], artifactRoles: [],
  },
];

describe("execution trace timeline and filtering", () => {
  it("keeps overlapping work on one run-relative timeline instead of summing durations", () => {
    const timeline = getExecutionTimeline(spans, "2026-01-01T00:00:00.000Z", 12_000);

    expect(timeline.elapsedMillis).toBe(12_000);
    expect(timeline.rows.map((row) => row.durationMillis)).toEqual([12_000, 10_000, 10_000]);
    expect(timeline.rows[1].leftPercent).toBe(0);
    expect(timeline.rows[1].widthPercent).toBeCloseTo(83.33, 1);
    expect(timeline.rows[2].leftPercent).toBeCloseTo(16.67, 1);
    expect(timeline.rows[2].widthPercent).toBeCloseTo(83.33, 1);
    expect(formatExecutionDuration(12_000)).toBe("12 sec");
  });

  it("explains a persisted trace-gap reason without conflating it with run status", () => {
    expect(executionGapReasonDescription("UNSAFE_SPAN_METADATA_OMITTED")).toBe(
      "An operation's provider or model metadata did not meet trace safety rules, so its span was omitted.",
    );
    expect(executionGapReasonDescription(null)).toBe("One or more operation spans could not be recorded.");
  });

  it("loads only bounded missing ancestor context for a selected deep link", () => {
    const deepSpan: ExecutionSpan = { ...spans[2], id: "deep-selected", parentSpanId: "parent-not-loaded" };
    const context = getExecutionAncestorContext(deepSpan, [spans[0]], () => undefined);
    expect(context.spans).toEqual([]);
    expect(context.missingAncestorSpanId).toBe("parent-not-loaded");

    const chain = Array.from({ length: MAX_SELECTED_SPAN_ANCESTORS + 5 }, (_, index) => ({
      ...spans[0],
      id: `ancestor-${index}`,
      parentSpanId: index === MAX_SELECTED_SPAN_ANCESTORS + 4 ? null : `ancestor-${index + 1}`,
    }));
    const bounded = getExecutionAncestorContext(
      { ...deepSpan, parentSpanId: chain[0].id },
      chain,
      () => undefined,
    );
    expect(bounded.spans).toHaveLength(MAX_SELECTED_SPAN_ANCESTORS);
    expect(bounded.missingAncestorSpanId).toBeNull();
  });

  it("retains each matching span's ancestors while filtering unrelated operations", () => {
    const filtered = filterExecutionSpans(spans, { query: "model", stage: "all", status: "all", kind: "all" });

    expect([...filtered].sort()).toEqual(["child-b", "stage"]);
  });

  it("filters spans by an Analysis Pipeline stage while retaining matching ancestor context", () => {
    const referenceSpan = {
      ...spans[1],
      id: "reference-span",
      parentSpanId: null,
      operationId: "reference-operation",
      stageId: "references",
      name: "Resolve references",
    };
    const filtered = filterExecutionSpans(
      [...spans, referenceSpan],
      { query: "", stage: "references", status: "all", kind: "all" },
    );

    expect([...filtered]).toEqual(["reference-span"]);
  });

  it("uses elapsed time for running spans but leaves incomplete interrupted duration unknown", () => {
    const runningSpan: ExecutionSpan = {
      ...spans[0], id: "running", endedAt: null, durationMillis: null, status: "RUNNING",
    };
    const interruptedSpan: ExecutionSpan = {
      ...spans[0], id: "interrupted", endedAt: null, durationMillis: null, status: "INTERRUPTED",
    };
    const running = getExecutionTimeline([runningSpan], runningSpan.startedAt, null, Date.parse("2026-01-01T00:00:03.000Z"));
    const interrupted = getExecutionTimeline([interruptedSpan], interruptedSpan.startedAt, null, Date.parse("2026-01-01T00:00:03.000Z"));

    expect(running.rows[0].durationMillis).toBe(3_000);
    expect(running.elapsedMillis).toBe(3_000);
    expect(interrupted.rows[0].durationMillis).toBeNull();
    expect(interrupted.elapsedMillis).toBe(0);
  });

  it("loads all cursor pages before exposing the complete execution list", async () => {
    const originalFetch = globalThis.fetch;
    const requestedUrls: string[] = [];
    const pages = [
      { items: [spans[0]], nextCursor: "cursor-2" },
      { items: [spans[1]], nextCursor: "cursor-3" },
      { items: [spans[2]], nextCursor: null },
    ];
    globalThis.fetch = async (input) => {
      requestedUrls.push(String(input));
      const page = pages[requestedUrls.length - 1];
      return new Response(JSON.stringify(page), { status: 200, headers: { "content-type": "application/json" } });
    };

    try {
      const query = executionSpansQueryOptions("run", true, false);
      const completePage = await query.queryFn({ signal: new AbortController().signal } as never);

      expect(requestedUrls).toEqual([
        "/api/v1/analysis-runs/run/execution/spans",
        "/api/v1/analysis-runs/run/execution/spans?cursor=cursor-2",
        "/api/v1/analysis-runs/run/execution/spans?cursor=cursor-3",
      ]);
      expect(completePage.items.map((span) => span.id)).toEqual(["stage", "child-a", "child-b"]);
      expect(completePage.nextCursor).toBeNull();
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it("surfaces a failure from any cursor page instead of returning a partial trace", async () => {
    const originalFetch = globalThis.fetch;
    let requestCount = 0;
    globalThis.fetch = async () => {
      requestCount += 1;
      if (requestCount === 1) {
        return new Response(JSON.stringify({ items: [spans[0]], nextCursor: "cursor-2" }), { status: 200 });
      }
      return new Response(JSON.stringify({ message: "The next execution page could not be loaded." }), { status: 503 });
    };

    try {
      const query = executionSpansQueryOptions("run", true, false);
      await expect(query.queryFn({ signal: new AbortController().signal } as never))
        .rejects.toThrow("The next execution page could not be loaded.");
      expect(requestCount).toBe(2);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it("fails clearly if the server repeats a cursor", async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({ items: [], nextCursor: "same-cursor" }), { status: 200 });

    try {
      const query = executionSpansQueryOptions("run", true, false);
      await expect(query.queryFn({ signal: new AbortController().signal } as never))
        .rejects.toThrow("Execution trace pagination returned a repeated cursor.");
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it("stops execution polling for terminal Analysis Runs", () => {
    const recordedSummary = {
      analysisRunId: "run",
      captureRequested: true,
      captureEnabled: true,
      recordingState: "RECORDING",
      completeness: "RECORDING",
      startedAt: "2026-01-01T00:00:00.000Z",
      finishedAt: null,
      totalDurationMillis: null,
      gapReason: null,
    } satisfies ExecutionSummary;
    const summaryPoll = executionSummaryQueryOptions("run", true).refetchInterval;
    const spansPoll = executionSpansQueryOptions("run", true, true).refetchInterval;

    expect(typeof summaryPoll).toBe("function");
    expect(typeof spansPoll).toBe("function");
    if (typeof summaryPoll === "function") expect(summaryPoll({ state: { data: recordedSummary } } as never)).toBe(false);
    if (typeof spansPoll === "function") expect(spansPoll({ state: { data: { pages: [{ items: spans, nextCursor: null }] } } } as never)).toBe(false);
    const activeSummaryPoll = executionSummaryQueryOptions("run", false).refetchInterval;
    if (typeof activeSummaryPoll === "function") expect(activeSummaryPoll({ state: { data: recordedSummary } } as never)).toBe(3000);
    const activeSpansPoll = executionSpansQueryOptions("run", false, true).refetchInterval;
    if (typeof activeSpansPoll === "function") {
      expect(activeSpansPoll({ state: { data: { pages: [{ items: spans, nextCursor: null }] } } } as never)).toBe(15_000);
    }
  });
});
