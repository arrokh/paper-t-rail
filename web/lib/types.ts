export type AnalysisRun = {
  id: string;
  documentId: string;
  filename: string;
  sourceContentSha256: string;
  status: "QUEUED" | "PROCESSING" | "PARSED" | "COMPLETED" | "COMPLETED_WITH_WARNINGS" | "FAILED";
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

export type ParsedDocument = {
  parser: { provider: string; version: string };
  sourceContentSha256: string;
  normalizedSourceText: string;
  sections: Array<{
    id: string;
    sectionOrder: number;
    heading: string | null;
    text: string;
    startOffset: number;
    endOffset: number;
  }>;
  citationContexts: Array<{
    id: string;
    sectionId: string;
    boundaryKind: "CLAUSE" | "SENTENCE_FALLBACK";
    text: string;
    startOffset: number;
    endOffset: number;
    occurrences: Array<{
      id: string;
      markerText: string;
      startOffset: number;
      endOffset: number;
      bibliographyReferenceKeys: string[];
    }>;
  }>;
  bibliographyEntries: Array<{
    entryOrder: number;
    localReferenceKey: string;
    rawText: string;
    title: string | null;
    authors: string[];
    year: number | null;
    doi: string | null;
    referenceType: string;
    resolutionStatus: string;
  }>;
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
