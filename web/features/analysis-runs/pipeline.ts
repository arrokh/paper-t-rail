import type { AnalysisRun } from "./types";

export const PIPELINE_STAGES = [
  {
    id: "source",
    number: "01",
    label: "Read the PDF",
    description: "Parse the source PDF and persist its structure, citations, and Atomic Claims.",
    steps: [
      { id: "parse-document", label: "Parse source and extract claims", description: "Verify the stored PDF, parse its structure, extract claims, and persist the parsed result." },
    ],
  },
  {
    id: "references",
    number: "02",
    label: "Resolve references",
    description: "Resolve each supported Bibliography Entry to a Canonical Paper.",
    steps: [
      { id: "resolve-entry", label: "Resolve bibliography entries", description: "Normalize supported metadata, look up candidates, score matches, and persist the resolution outcome." },
    ],
  },
  {
    id: "access",
    number: "03",
    label: "Acquire cited sources",
    description: "Discover legal full-text sources and record acquired Cited Paper Assets.",
    steps: [
      { id: "acquire-source", label: "Discover and acquire cited sources", description: "Check configured legal locations, fetch eligible full text, extract text, and record access and language outcomes." },
    ],
  },
  {
    id: "evidence",
    number: "04",
    label: "Prepare evidence",
    description: "Prepare eligible cited full text for claim-level evidence assessment.",
    steps: [
      { id: "prepare-evidence", label: "Prepare evidence index", description: "Parse, chunk, embed, and retrieve ranked Evidence Passages from eligible full text." },
    ],
  },
  {
    id: "verification",
    number: "05",
    label: "Assess evidence",
    description: "Assess eligible Claim–Reference pairs and persist aggregated outcomes.",
    steps: [
      { id: "assess-and-aggregate", label: "Assess claim-reference pairs", description: "Record System One passage judgements and apply the run-pinned deterministic aggregation policy when configured." },
    ],
  },
] as const;

export type PipelineStageId = (typeof PIPELINE_STAGES)[number]["id"];
export type PipelineStepId = (typeof PIPELINE_STAGES)[number]["steps"][number]["id"];
export type PipelineStageState = "waiting" | "active" | "pending" | "complete" | "ready" | "unavailable" | "failed" | "warning";

export const PIPELINE_STAGE_STATE_LABELS: Record<PipelineStageState, string> = {
  waiting: "Waiting",
  active: "In progress",
  pending: "Results pending",
  complete: "Complete",
  ready: "Ready",
  unavailable: "Skipped",
  failed: "Failed",
  warning: "Complete with warnings",
};

const LEGACY_STAGE_ALIASES: Record<string, PipelineStageId> = {
  indexing: "evidence",
  report: "verification",
};

export function normalizePipelineStageId(value: string | null): PipelineStageId | null {
  if (!value) return null;
  if (PIPELINE_STAGES.some((stage) => stage.id === value)) return value as PipelineStageId;
  return LEGACY_STAGE_ALIASES[value] ?? null;
}

export function pipelineStage(id: string | null | undefined) {
  const normalizedId = id ? LEGACY_STAGE_ALIASES[id] ?? id : undefined;
  return PIPELINE_STAGES.find((stage) => stage.id === normalizedId) ?? PIPELINE_STAGES[0];
}

function stateFromPersistedStatus(status: string | undefined): PipelineStageState | null {
  switch (status) {
    case "WAITING": return "waiting";
    case "IN_PROGRESS": return "active";
    case "COMPLETED": return "complete";
    case "COMPLETED_WITH_WARNINGS": return "warning";
    case "SKIPPED": return "unavailable";
    case "FAILED": return "failed";
    default: return null;
  }
}

function hasPositiveCount(value: number | undefined): boolean {
  return (value ?? 0) > 0;
}

export function pipelineStageState(run: AnalysisRun | null | undefined, stageId: PipelineStageId): PipelineStageState {
  if (!run) return "waiting";

  const persisted = run.pipeline?.stages.find((stage) => stage.id === stageId);
  const persistedState = stateFromPersistedStatus(persisted?.status);
  if (persistedState) return persistedState;

  const stageIndex = PIPELINE_STAGES.findIndex((stage) => stage.id === stageId);
  if (run.status === "QUEUED") return "waiting";

  if (run.status === "PROCESSING") {
    const reportedStage = run.progress.stage;
    const activeIndex = reportedStage === "PARSING_DOCUMENT" ? 0 : reportedStage === "RESOLVING_REFERENCES" ? 1 : -1;
    if (activeIndex < 0) return "waiting";
    if (stageIndex < activeIndex) return "complete";
    if (stageIndex === activeIndex) return "active";
    return "waiting";
  }

  if (run.status === "PARSED") return stageIndex < 4 ? "complete" : "ready";
  if (run.status === "COMPLETED" || run.status === "COMPLETED_WITH_WARNINGS") {
    const warningByStage = [
      false,
      hasPositiveCount(run.progress.failedReferenceResolutionCount),
      hasPositiveCount(run.progress.failedCitedPaperAcquisitionCount),
      hasPositiveCount(run.progress.failedEvidenceIndexingCount),
      hasPositiveCount(run.progress.incompleteVerifications),
    ];
    if (run.status === "COMPLETED_WITH_WARNINGS" &&
        !warningByStage.some(Boolean) &&
        run.progress.failedReferenceResolutionCount === undefined &&
        run.progress.failedCitedPaperAcquisitionCount === undefined &&
        run.progress.failedEvidenceIndexingCount === undefined &&
        run.progress.incompleteVerifications === undefined) {
      return stageIndex === 4 ? "warning" : "complete";
    }
    return warningByStage[stageIndex] ? "warning" : "complete";
  }
  return "unavailable";
}

export function defaultPipelineStep(_run: AnalysisRun | null | undefined, stageId: PipelineStageId): string {
  return pipelineStage(stageId).steps[0].id;
}
