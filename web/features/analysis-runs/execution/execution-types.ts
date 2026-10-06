export type ExecutionRecordingState = "RECORDING" | "STOPPED" | "NOT_RECORDED";
export type ExecutionCompleteness = "COMPLETE" | "INCOMPLETE" | "RECORDING" | "NOT_RECORDED";
export type ExecutionGapReason =
  | "UNSAFE_SPAN_METADATA_OMITTED"
  | "UNSAFE_SPAN_RESULT_OMITTED"
  | "INTERVAL_TIMESTAMPS_UNAVAILABLE"
  | "SPAN_STORAGE_UNAVAILABLE"
  | "RETRY_SCHEDULE_TIMESTAMPS_UNAVAILABLE"
  | "QUEUE_ENQUEUE_TIMESTAMP_UNAVAILABLE"
  | "UNSAFE_ARTIFACT_METADATA_OMITTED"
  | "ARTIFACT_STORAGE_UNAVAILABLE"
  | "UNSAFE_SPAN_ATTRIBUTES_OMITTED"
  | "CAPTURE_STOPPED"
  | "INTERRUPTED_OPERATION";
export type ExecutionArtifactRole = "INPUT" | "REQUEST" | "RESPONSE" | "RESULT";
export type ExecutionArtifactFidelity = "COMPLETE" | "SANITIZED" | "PARTIAL" | "OMITTED" | "REMOVED" | "UNAVAILABLE";

export type ExecutionSummary = {
  analysisRunId: string;
  captureRequested: boolean | null;
  captureEnabled: boolean;
  recordingState: ExecutionRecordingState;
  completeness: ExecutionCompleteness;
  startedAt: string | null;
  finishedAt: string | null;
  totalDurationMillis: number | null;
  gapReason: ExecutionGapReason | null;
};

export type ExecutionSpan = {
  id: string;
  parentSpanId: string | null;
  operationId: string;
  stageId: string;
  kind: string;
  name: string;
  startedAt: string;
  endedAt: string | null;
  durationMillis: number | null;
  status: string;
  attempt: number;
  providerId: string | null;
  modelId: string | null;
  httpStatus: number | null;
  safeErrorCode: string | null;
  attributes: Record<string, unknown>;
  trustBoundary: string;
  httpRoute: string | null;
  domainLinks: ExecutionDomainLink[];
  artifactRoles: ExecutionArtifactDescriptor[];
};

export type ExecutionSpanPage = {
  items: ExecutionSpan[];
  nextCursor: string | null;
};

export type ExecutionArtifactDescriptor = {
  id: string | null;
  role: ExecutionArtifactRole;
  fidelity: ExecutionArtifactFidelity;
  reason: string | null;
  mediaType: string | null;
  sizeBytes: number | null;
};

export type ExecutionDomainLink = {
  type: string;
  id: string;
  href: string;
};

export type ExecutionSpanDetail = ExecutionSpan;

export type ExecutionArtifact = {
  id: string;
  spanId: string;
  role: ExecutionArtifactRole;
  fidelity: ExecutionArtifactFidelity;
  reason: string | null;
  mediaType: string | null;
  content: string | null;
  schemaVersion: string | null;
  captureVersion: string | null;
  sanitizerVersion: string | null;
  sizeBytes: number | null;
};

export type ExecutionSpanFilters = {
  query: string;
  stage: string;
  status: string;
  kind: string;
};
