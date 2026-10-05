import { describe, expect, it } from "vitest";
import {
  filterExecutionSpans,
  formatExecutionDuration,
  getExecutionTimeline,
} from "@/features/analysis-runs/execution/execution-trace-utils";
import type { ExecutionSpan, ExecutionSummary } from "@/features/analysis-runs/execution/execution-types";
import { executionSpansQueryOptions, executionSummaryQueryOptions } from "@/features/analysis-runs/queries/analysis-run-queries";

const spans: ExecutionSpan[] = [
  {
    id: "stage", parentSpanId: null, operationId: "stage", stageId: "source", kind: "STAGE", name: "Read the PDF",
    startedAt: "2026-01-01T00:00:00.000Z", endedAt: "2026-01-01T00:00:12.000Z", durationMillis: 12_000,
    status: "SUCCEEDED", attempt: 1, providerId: null, modelId: null, httpStatus: null, safeErrorCode: null,
    attributes: {}, artifactRoles: [],
  },
  {
    id: "child-a", parentSpanId: "stage", operationId: "parse", stageId: "source", kind: "TRANSFORM", name: "Parse source",
    startedAt: "2026-01-01T00:00:00.000Z", endedAt: "2026-01-01T00:00:10.000Z", durationMillis: 10_000,
    status: "SUCCEEDED", attempt: 1, providerId: null, modelId: null, httpStatus: null, safeErrorCode: null,
    attributes: {}, artifactRoles: [],
  },
  {
    id: "child-b", parentSpanId: "stage", operationId: "model", stageId: "source", kind: "PROVIDER_CALL", name: "Model request",
    startedAt: "2026-01-01T00:00:02.000Z", endedAt: "2026-01-01T00:00:12.000Z", durationMillis: 10_000,
    status: "FAILED", attempt: 1, providerId: "local", modelId: "test-model", httpStatus: 503, safeErrorCode: "PROVIDER_UNAVAILABLE",
    attributes: {}, artifactRoles: [],
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

  it("retains each matching span's ancestors while filtering unrelated operations", () => {
    const filtered = filterExecutionSpans(spans, { query: "model", status: "all", kind: "all" });

    expect([...filtered].sort()).toEqual(["child-b", "stage"]);
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

  it("stops execution polling for terminal Analysis Runs", () => {
    const recordedSummary = {
      analysisRunId: "run",
      captureEnabled: true,
      recordingState: "RECORDING",
      completeness: "RECORDING",
      startedAt: "2026-01-01T00:00:00.000Z",
      finishedAt: null,
      totalDurationMillis: null,
    } satisfies ExecutionSummary;
    const summaryPoll = executionSummaryQueryOptions("run", true).refetchInterval;
    const spansPoll = executionSpansQueryOptions("run", true, true).refetchInterval;

    expect(typeof summaryPoll).toBe("function");
    expect(typeof spansPoll).toBe("function");
    if (typeof summaryPoll === "function") expect(summaryPoll({ state: { data: recordedSummary } } as never)).toBe(false);
    if (typeof spansPoll === "function") expect(spansPoll({ state: { data: { pages: [{ items: spans, nextCursor: null }] } } } as never)).toBe(false);
    const activeSummaryPoll = executionSummaryQueryOptions("run", false).refetchInterval;
    if (typeof activeSummaryPoll === "function") expect(activeSummaryPoll({ state: { data: recordedSummary } } as never)).toBe(3000);
  });
});
