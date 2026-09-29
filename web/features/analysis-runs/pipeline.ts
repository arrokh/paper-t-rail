import type { AnalysisRun } from "./types";

export const PIPELINE_STAGES = [
  {
    id: "source",
    number: "01",
    label: "Read the PDF",
    description: "Parse source structure and attach annotations to the document.",
    subprocesses: [
      { id: "sections", label: "Extract sections", description: "Read the selectable text and persist ordered document sections." },
      { id: "annotations", label: "Locate citations", description: "Record Citation Occurrences and group them into Citation Contexts." },
      { id: "claims", label: "Extract Atomic Claims", description: "Extract claims from each context and link them to its Citation Targets as inferred associations." },
    ],
  },
  {
    id: "references",
    number: "02",
    label: "Resolve references",
    description: "Match Bibliography Entries to candidate Canonical Papers.",
    subprocesses: [
      { id: "normalize", label: "Normalize entries", description: "Prepare bibliography metadata for lookup." },
      { id: "match", label: "Match papers", description: "Compare candidate papers with the source Bibliography Entry." },
      { id: "score", label: "Apply threshold", description: "Keep uncertain or below-threshold matches unresolved." },
    ],
  },
  {
    id: "access",
    number: "03",
    label: "Check access",
    description: "Check legal full-text locations and record the available Cited Paper Asset.",
    subprocesses: [
      { id: "discover", label: "Discover access", description: "Check the configured access source and record its license and location." },
      { id: "acquire", label: "Acquire full text", description: "Fetch and verify the exact asset when a legal full-text location is available." },
    ],
  },
  {
    id: "indexing",
    number: "04",
    label: "Prepare evidence",
    description: "Gate by language, then parse, chunk, embed and index available full text.",
    subprocesses: [
      { id: "language", label: "Check language", description: "Detect whether the acquired Cited Paper Asset is supported for verification." },
      { id: "index", label: "Build evidence index", description: "Parse the asset and prepare indexed passages for retrieval." },
    ],
  },
  {
    id: "verification",
    number: "05",
    label: "Assess evidence",
    description: "Retrieve Evidence Passages and assess each eligible Claim × Cited Reference pair.",
    subprocesses: [
      { id: "retrieve", label: "Retrieve passages", description: "Rank candidate passages for each Atomic Claim × Cited Reference." },
      { id: "judge", label: "Judge evidence", description: "Record persisted Evidence Judgements for retrieved passages." },
    ],
  },
  {
    id: "report",
    number: "06",
    label: "Build report",
    description: "Aggregate persisted outcomes into the Evidence Coverage Report.",
    subprocesses: [
      { id: "aggregate", label: "Aggregate outcomes", description: "Apply the run-pinned deterministic policy to completed evidence assessments." },
      { id: "summary", label: "Review report", description: "Present claim-level outcomes, provenance, limitations and Human Reviews." },
    ],
  },
] as const;

export type PipelineStageId = (typeof PIPELINE_STAGES)[number]["id"];
export type PipelineSubprocessId = (typeof PIPELINE_STAGES)[number]["subprocesses"][number]["id"];
export type PipelineStageState = "waiting" | "active" | "pending" | "complete" | "ready" | "unavailable" | "failed" | "warning";

export const PIPELINE_STAGE_STATE_LABELS: Record<PipelineStageState, string> = {
  waiting: "Waiting",
  active: "In progress",
  pending: "Results pending",
  complete: "Complete",
  ready: "Ready",
  unavailable: "Unavailable",
  failed: "Failed",
  warning: "Complete with warnings",
};

export function pipelineStage(id: string | null | undefined) {
  return PIPELINE_STAGES.find((stage) => stage.id === id) ?? PIPELINE_STAGES[0];
}

export function pipelineStageState(run: AnalysisRun | null | undefined, stageId: PipelineStageId): PipelineStageState {
  const stageIndex = PIPELINE_STAGES.findIndex((stage) => stage.id === stageId);
  if (!run || run.status === "QUEUED") return "waiting";

  if (run.status === "PROCESSING") {
    const reportedStage = run.progress.stage;
    const activeIndex = reportedStage === "PARSING_DOCUMENT"
      ? 0
      : reportedStage === "RESOLVING_REFERENCES"
        ? 1
        : -1;
    if (stageIndex < activeIndex) return "pending";
    if (stageIndex === activeIndex) return "active";
    return "waiting";
  }

  if (run.status === "PARSED") {
    if (stageIndex <= 3) return "complete";
    return stageIndex === 4 ? "ready" : "waiting";
  }

  if (run.status === "COMPLETED") return "complete";
  if (run.status === "COMPLETED_WITH_WARNINGS") return stageIndex === 5 ? "warning" : "complete";

  const failedIndex = run.progress.stage === "PARSING_DOCUMENT"
    ? 0
    : run.progress.stage === "RESOLVING_REFERENCES"
      ? 1
      : -1;
  if (failedIndex < 0) return "waiting";
  if (stageIndex < failedIndex) return "unavailable";
  return stageIndex === failedIndex ? "failed" : "waiting";
}

export function defaultPipelineStage(run: AnalysisRun | null | undefined): PipelineStageId {
  if (!run) return "source";
  if (run.status === "COMPLETED" || run.status === "COMPLETED_WITH_WARNINGS") return "report";
  if (run.status === "PROCESSING" && run.progress.stage === "RESOLVING_REFERENCES") return "references";
  if (run.status === "FAILED" && run.progress.stage === "RESOLVING_REFERENCES") return "references";
  return "source";
}

export function defaultPipelineSubprocess(run: AnalysisRun | null | undefined, stageId: PipelineStageId): string {
  if (run?.status === "PARSED" && stageId === "source") return "annotations";
  if ((run?.status === "COMPLETED" || run?.status === "COMPLETED_WITH_WARNINGS") && stageId === "report") return "summary";
  return pipelineStage(stageId).subprocesses[0].id;
}
