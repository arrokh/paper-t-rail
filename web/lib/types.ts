export type AnalysisRun = {
  id: string;
  documentId: string;
  filename: string;
  sourceContentSha256: string;
  status: "QUEUED" | "PROCESSING" | "COMPLETED" | "COMPLETED_WITH_WARNINGS" | "FAILED";
  progress: { stage?: string; message?: string };
  configuration: {
    claimExtractor: { provider: string; version: string };
    embedding: { provider: string; model?: string; version: string };
    systemOne: { provider: string; model?: string; version: string };
    sourceParser: { provider: string; version: string };
    languageDetector: { provider: string; version: string };
  };
  createdAt: string;
  startedAt: string | null;
  failureReason: string | null;
};

export type CreatedRun = {
  documentId: string;
  analysisRunId: string;
  filename: string;
  sourceContentSha256: string;
  status: "QUEUED";
  createdAt: string;
};

export type ApiError = { code: string; message: string };
