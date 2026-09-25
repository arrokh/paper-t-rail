export type AnalysisRun = {
  id: string;
  documentId: string;
  filename: string;
  sourceContentSha256: string;
  status: "QUEUED" | "PROCESSING" | "PARSED" | "COMPLETED" | "COMPLETED_WITH_WARNINGS" | "FAILED";
  progress: { stage?: string; message?: string };
  configuration: {
    claimExtractor: { provider: string; model?: string; version: string; trustBoundary?: string; dataCategories?: string[] };
    embedding: { provider: string; model?: string; version: string; trustBoundary?: string; dataCategories?: string[] };
    systemOne: { provider: string; model?: string; version: string; trustBoundary?: string; dataCategories?: string[] };
    sourceParser: { provider: string; version: string };
    referenceResolution?: {
      executionStatus: string;
      provider: { provider: string; model?: string; version: string; trustBoundary?: string; dataCategories?: string[] } | null;
      scorePolicyVersion: string | null;
      confidenceThreshold: number | null;
    };
    languageDetector: { provider: string; version: string };
    externalProviderConsents?: { providerId: string; dataCategories: string[] }[];
  };
  createdAt: string;
  startedAt: string | null;
  failureReason: string | null;
};

export type AnalysisRunPage = {
  items: AnalysisRun[];
  nextCursor: string | null;
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
    atomicClaims: Array<{
      id: string;
      text: string;
      sourceStartOffset: number;
      sourceEndOffset: number;
      citationTargets: Array<{
        id: string;
        markerText: string;
        bibliographyReferenceKey: string;
        bibliographyTitle: string | null;
        associationKind: "INFERRED_PROVISIONAL";
      }>;
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

export type ReferenceResolutionReportResponse = {
  analysisRunId: string;
  runStatus: string;
  referenceResolution: {
    executionStatus: string;
    scorePolicyVersion: string | null;
    confidenceThreshold: number | null;
    summary: {
      total: number;
      resolved: number;
      unresolved: number;
      unsupportedReferenceType: number;
      notAttempted: number;
      failed: number;
    };
    entries: Array<{
      entryOrder: number;
      localReferenceKey: string;
      rawText: string;
      title: string | null;
      authors: string[];
      year: number | null;
      doi: string | null;
      referenceType: string;
      status: string;
      reasonCode: string | null;
      canonicalPaper: { id: string; doi: string | null; title: string; authors: string[]; year: number | null } | null;
      confidenceScore: number | null;
      matchMethod: string | null;
    }>;
  };
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
