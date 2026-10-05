import type { ExecutionSpan, ExecutionSpanFilters } from "./execution-types";

export type ExecutionTimelineRow = {
  span: ExecutionSpan;
  leftPercent: number;
  widthPercent: number;
  durationMillis: number | null;
  isInstant: boolean;
};

export type ExecutionTimeline = {
  elapsedMillis: number;
  startedAtMillis: number | null;
  rows: ExecutionTimelineRow[];
};

function parseTimestamp(value: string | null | undefined): number | null {
  if (!value) return null;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : null;
}

export function isActiveExecutionSpan(span: ExecutionSpan): boolean {
  return span.endedAt === null && ["RUNNING", "IN_PROGRESS"].includes(span.status.toUpperCase());
}

function spanDuration(span: ExecutionSpan, now: number): number | null {
  if (!isActiveExecutionSpan(span) && span.durationMillis !== null && Number.isFinite(span.durationMillis)) {
    return Math.max(0, span.durationMillis);
  }
  const startedAt = parseTimestamp(span.startedAt);
  if (startedAt === null) return null;
  if (span.endedAt === null && !isActiveExecutionSpan(span)) return span.durationMillis;
  const endedAt = parseTimestamp(span.endedAt) ?? now;
  return Math.max(0, endedAt - startedAt);
}

export function getExecutionTimeline(
  spans: ExecutionSpan[],
  summaryStartedAt: string | null,
  summaryDurationMillis: number | null,
  now = Date.now(),
  recordingActive = spans.some(isActiveExecutionSpan),
): ExecutionTimeline {
  const spanStarts = spans.map((span) => parseTimestamp(span.startedAt)).filter((value): value is number => value !== null);
  const startedAtMillis = parseTimestamp(summaryStartedAt) ?? (spanStarts.length ? Math.min(...spanStarts) : null);
  const latestKnownOffset = spans.reduce((latest, span) => {
    const start = parseTimestamp(span.startedAt);
    const end = parseTimestamp(span.endedAt) ?? (isActiveExecutionSpan(span) ? now : null);
    if (startedAtMillis === null || start === null || end === null) return latest;
    return Math.max(latest, end - startedAtMillis);
  }, 0);
  const currentElapsed = startedAtMillis === null ? 0 : Math.max(0, now - startedAtMillis);
  const elapsedMillis = Math.max(
    0,
    summaryDurationMillis ?? 0,
    latestKnownOffset,
    recordingActive ? currentElapsed : 0,
  );

  const rows = spans.map((span): ExecutionTimelineRow => {
    const start = parseTimestamp(span.startedAt);
    const relativeStart = startedAtMillis === null || start === null ? 0 : Math.max(0, start - startedAtMillis);
    const durationMillis = spanDuration(span, now);
    const safeElapsed = elapsedMillis || durationMillis || 1;
    return {
      span,
      leftPercent: Math.min(100, (relativeStart / safeElapsed) * 100),
      widthPercent: !durationMillis ? 0 : Math.min(100, (durationMillis / safeElapsed) * 100),
      durationMillis,
      isInstant: durationMillis === 0,
    };
  });

  return { elapsedMillis, startedAtMillis, rows };
}

export function formatExecutionDuration(durationMillis: number | null): string {
  if (durationMillis === null || !Number.isFinite(durationMillis)) return "Unknown duration";
  if (durationMillis < 1_000) return `${Math.max(0, Math.round(durationMillis))} ms`;
  const seconds = durationMillis / 1_000;
  const formatted = Number.isInteger(seconds) ? String(seconds) : seconds.toFixed(1).replace(/\.0$/, "");
  return `${formatted} sec`;
}

export function filterExecutionSpans(spans: ExecutionSpan[], filters: ExecutionSpanFilters): Set<string> {
  const byId = new Map(spans.map((span) => [span.id, span]));
  const normalizedQuery = filters.query.trim().toLowerCase();
  const matches = spans.filter((span) => {
    const textMatches = !normalizedQuery || [
      span.name,
      span.kind,
      span.providerId ?? "",
      span.modelId ?? "",
      span.safeErrorCode ?? "",
      span.operationId,
    ].some((value) => value.toLowerCase().includes(normalizedQuery));
    const statusMatches = filters.status === "all" || span.status.toLowerCase() === filters.status.toLowerCase();
    const kindMatches = filters.kind === "all" || span.kind.toLowerCase() === filters.kind.toLowerCase();
    return textMatches && statusMatches && kindMatches;
  });

  const visible = new Set<string>();
  for (const match of matches) {
    let current: ExecutionSpan | undefined = match;
    while (current && !visible.has(current.id)) {
      visible.add(current.id);
      current = current.parentSpanId ? byId.get(current.parentSpanId) : undefined;
    }
  }
  return visible;
}

export function executionStatusLabel(status: string): string {
  const key = status.toUpperCase();
  const labels: Record<string, string> = {
    WAITING: "Waiting",
    PENDING: "Waiting",
    RUNNING: "Running",
    IN_PROGRESS: "Running",
    SUCCEEDED: "Succeeded",
    SUCCESS: "Succeeded",
    COMPLETED: "Succeeded",
    FAILED: "Failed",
    SKIPPED: "Skipped",
    REUSED: "Reused",
    INTERRUPTED: "Interrupted",
    UNKNOWN: "Unknown",
  };
  return labels[key] ?? status.replaceAll("_", " ").toLowerCase().replace(/^./, (character) => character.toUpperCase());
}
