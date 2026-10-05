export type ExecutionRecordingState = "RECORDING" | "STOPPED" | "NOT_RECORDED";
export type ExecutionCompleteness = "COMPLETE" | "INCOMPLETE" | "RECORDING" | "NOT_RECORDED";
export type ExecutionArtifactRole = "input" | "request" | "response" | "result";
export type ExecutionArtifactFidelity = "COMPLETE" | "SANITIZED" | "PARTIAL" | "OMITTED" | "REMOVED" | "UNAVAILABLE" | string;

export type ExecutionSummary = {
  analysisRunId: string;
  captureEnabled: boolean;
  recordingState: ExecutionRecordingState;
  completeness: ExecutionCompleteness;
  startedAt: string | null;
  finishedAt: string | null;
  totalDurationMillis: number | null;
};

export type ExecutionSpan = {
  id: string;
  parentSpanId: string | null;
  operationId: string;
  stageId: string | null;
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
  artifactRoles: ExecutionArtifactRole[];
};

export type ExecutionSpanPage = {
  items: ExecutionSpan[];
  nextCursor: string | null;
};

export type ExecutionArtifactDescriptor = {
  id: string;
  role: ExecutionArtifactRole;
  fidelity: ExecutionArtifactFidelity;
  reason: string | null;
  mediaType: string;
  sizeBytes: number;
};

export type ExecutionSpanDetail = ExecutionSpan & {
  artifactDescriptors: ExecutionArtifactDescriptor[];
  domainLinks?: Array<{ label: string; href: string }>;
};

export type ExecutionArtifact = {
  id: string;
  spanId: string;
  role: ExecutionArtifactRole;
  fidelity: ExecutionArtifactFidelity;
  reason: string | null;
  mediaType: string;
  content?: string | null;
  schemaVersion: string | null;
  captureVersion: string | null;
  sanitizerVersion: string | null;
  sizeBytes: number;
};

export type ExecutionSpanFilters = {
  query: string;
  status: string;
  kind: string;
};
