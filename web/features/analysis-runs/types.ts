export type EvidenceJudgement = {
  providerId: string;
  modelId: string | null;
  providerVersion: string;
  judgement: "DIRECT_SUPPORT" | "PARTIAL_SUPPORT" | "CONTRADICTS" | "UNRELATED" | "INSUFFICIENT";
  evidenceRole: "PRIMARY_FINDING" | "AUTHOR_SYNTHESIS" | "SECONDARY_REPORT";
  confidence: number;
  directness: number;
  claimScopeMatch: number;
  studyDesignQuality: number;
  relevance: number;
  calibratedStrength: number;
};

export type EvidencePassageSpanDiagnostic = {
  id: string;
  spanIndex: number;
  coreStartOffset: number;
  coreEndOffset: number;
  contextStartOffset: number;
  contextEndOffset: number;
  coreText: string;
  contextText: string;
  tokenCounts: number[];
  status: "PENDING" | "COMPLETED" | "FAILED" | "INCOMPLETE";
  failureReason: string | null;
  providerId: string;
  modelId: string;
  providerVersion: string;
  judgementRubricVersion: string;
  splittingPolicyVersion: string;
  evidenceJudgement: EvidenceJudgement | null;
};

export type EvidenceRetrievalProfile = {
  profileId: string;
  vectorCandidateLimit: number;
  lexicalCandidateLimit: number;
  finalCandidateLimit: number;
  reciprocalRankFusionConstant: number;
  embeddingProvider: string;
  embeddingModel: string;
  embeddingVersion: string;
  embeddingDimension: number;
  embeddingProfileHash: string;
};

export type HumanReviewAction = "AGREE" | "DISAGREE" | "OVERRIDE";
export type HumanReviewStatus =
  | "SUPPORTED"
  | "PARTIALLY_SUPPORTED"
  | "CONTRADICTED"
  | "INSUFFICIENT_EVIDENCE"
  | "INACCESSIBLE"
  | "UNRESOLVED"
  | "UNSUPPORTED_REFERENCE_TYPE";

export type HumanReview = {
  id: string;
  analysisRunId: string;
  verificationId: string;
  action: HumanReviewAction;
  overrideStatus: HumanReviewStatus | null;
  note: string | null;
  createdAt: string;
};

export type ClaimReferenceVerificationOutcome = {
  id: string;
  atomicClaimId: string;
  claimText: string;
  claimSourceStartOffset: number;
  claimSourceEndOffset: number;
  citationContextText: string;
  citationMarkers: string[];
  associationKind: "INFERRED_PROVISIONAL";
  processingStatus: "PENDING" | "COMPLETED" | "INCOMPLETE";
  processingFailureReason: string | null;
  finalStatus: "SUPPORTED" | "PARTIALLY_SUPPORTED" | "CONTRADICTED" | "INSUFFICIENT_EVIDENCE" | "INACCESSIBLE" | "UNRESOLVED" | "UNSUPPORTED_REFERENCE_TYPE" | null;
  verificationScope: "FULL_TEXT" | "ABSTRACT_ONLY" | "NONE";
  terminalReason: string | null;
  evidenceConflict: boolean;
  aggregatorVersion: string | null;
  evidencePassages: Array<{
    id: string;
    text: string;
    sectionOrder: number;
    sectionHeading: string | null;
    paragraphStart: number;
    paragraphEnd: number;
    pageNumber: number | null;
    vectorRank: number | null;
    lexicalRank: number | null;
    fusedRank: number;
    fusionScore: number;
    sourceAssetId: string;
    contentSha256: string;
    parserProvider: string;
    parserVersion: string;
    language: string;
    languageDetectorVersion: string;
    retrievalProfile: EvidenceRetrievalProfile;
    evidenceJudgement: EvidenceJudgement | null;
    diagnosticSpans: EvidencePassageSpanDiagnostic[];
  }>;
  humanReviews: HumanReview[];
};

export type AnalysisRun = {
  id: string;
  documentId: string;
  filename: string;
  sourceContentSha256: string;
  status: "QUEUED" | "PROCESSING" | "PARSED" | "COMPLETED" | "COMPLETED_WITH_WARNINGS" | "FAILED";
  progress: {
    stage?: string;
    message?: string;
    sectionCount?: number;
    citationContextCount?: number;
    citationOccurrenceCount?: number;
    bibliographyEntryCount?: number;
    atomicClaimCount?: number;
    inferredClaimTargetLinkCount?: number;
    resolvedReferenceCount?: number;
    unresolvedReferenceCount?: number;
    unsupportedReferenceTypeCount?: number;
    notAttemptedReferenceCount?: number;
    failedReferenceResolutionCount?: number;
    acquiredCitedPaperCount?: number;
    failedCitedPaperAcquisitionCount?: number;
    indexedCitedPaperCount?: number;
    failedEvidenceIndexingCount?: number;
    totalVerifications?: number;
    completedVerifications?: number;
    incompleteVerifications?: number;
  };
  pipeline?: {
    stages: Array<{
      id: string;
      status: "WAITING" | "IN_PROGRESS" | "COMPLETED" | "COMPLETED_WITH_WARNINGS" | "SKIPPED" | "FAILED";
      counts: { total: number; waiting: number; inProgress: number; completed: number; skipped: number; failed: number };
      steps: Array<{
        id: string;
        status: "WAITING" | "IN_PROGRESS" | "COMPLETED" | "COMPLETED_WITH_WARNINGS" | "SKIPPED" | "FAILED";
        counts: { total: number; waiting: number; inProgress: number; completed: number; skipped: number; failed: number };
        items: Array<{ id: string; label: string; status: string; reasonCode: string | null }>;
      }>;
    }>;
  } | null;
  configuration: {
    claimExtractor: { provider: string; model?: string; version: string; trustBoundary?: string; dataCategories?: string[] };
    embedding: { provider: string; model?: string; version: string; trustBoundary?: string; dataCategories?: string[] };
    retrieval: {
      profileId: string;
      vectorCandidateLimit: number;
      lexicalCandidateLimit: number;
      finalCandidateLimit: number;
      reciprocalRankFusionConstant: number;
      embeddingProfileHash: string;
    };
    systemOne: { provider: string; model?: string; version: string; trustBoundary?: string; dataCategories?: string[] };
    sourceParser: { provider: string; version: string };
    citedPaperParser?: { provider: string; version: string } | null;
    openAccess?: { provider: string; model?: string; version: string; trustBoundary?: string; dataCategories?: string[] };
    referenceResolution?: {
      executionStatus: string;
      provider: { provider: string; model?: string; version: string; trustBoundary?: string; dataCategories?: string[] } | null;
      scorePolicyVersion: string | null;
      confidenceThreshold: number | null;
    };
    validationLimits?: { minimumLanguageConfidence?: number; maxClaimCitationPairs?: number };
    aggregation?: {
      executionStatus: string;
      verificationPolicyVersion: string | null;
      aggregationPolicyVersion: string | null;
      thresholds: Record<string, number> | null;
    };
    languageDetector: { provider: string; version: string };
    externalProviderConsents?: { providerId: string; dataCategories: string[]; retentionDisclosure?: string | null }[];
  };
  createdAt: string;
  startedAt: string | null;
  failureReason: string | null;
};

export type SourceDocumentPdfAccess = {
  filename: string;
  viewUrl: string;
  downloadUrl: string;
  expiresAt: string;
};

export type AnalysisRunPage = {
  items: AnalysisRun[];
  nextCursor: string | null;
  previousCursor?: string | null;
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

export type EvidenceCoverageSummary = {
  totalVerifications: number;
  completedVerifications: number;
  incompleteVerifications: number;
  evidenceConflicts: number;
  supported: number;
  partiallySupported: number;
  contradicted: number;
  insufficientEvidence: number;
  inaccessible: number;
  unresolved: number;
  unsupportedReferenceType: number;
};

export type ReferenceResolutionReportResponse = {
  analysisRunId: string;
  runStatus: string;
  evidenceCoverage: {
    executionStatus: "NOT_RUN" | "PENDING" | "COMPLETED" | "COMPLETED_WITH_WARNINGS" | "FAILED";
    verificationPolicyVersion: string | null;
    aggregationPolicyVersion: string | null;
    thresholds: Record<string, number> | null;
    summary: EvidenceCoverageSummary;
    triageDisclaimer: string;
  };
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
      citedPaperAccess: {
        accessStatus: "FULL_TEXT_AVAILABLE" | "ABSTRACT_ONLY" | "METADATA_ONLY" | "UNAVAILABLE";
        accessReason: "ABSTRACT_ONLY" | "NO_LEGAL_FULL_TEXT_LOCATION" | "NO_ACCESSIBLE_METADATA" | "FULL_TEXT_ACQUISITION_FAILED" | null;
        providerId: string;
        sourceUrl: string | null;
        license: string | null;
        version: string | null;
        hostType: string | null;
        discoveredAt: string;
        contentSha256: string | null;
        language: string | null;
        languageDetectorVersion: string | null;
        verificationOutcomes: ClaimReferenceVerificationOutcome[];
        evidenceIndexing: {
          status: "PENDING" | "COMPLETED" | "FAILED";
          failureReason: string | null;
          assetId: string | null;
          parserProvider: string | null;
          parserVersion: string | null;
          contentSha256: string | null;
          language: string | null;
          languageDetectorVersion: string | null;
          retrievalProfile: EvidenceRetrievalProfile;
        } | null;
      } | null;
      verificationOutcomes: ClaimReferenceVerificationOutcome[];
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

export type AnalysisRunConfiguration = {
  claimExtractorProvider: string;
  embeddingProvider: string;
  systemOneProvider: string;
  scholarlyMetadataProvider: string;
  openAccessProvider: string;
  externalProviderConsents: Array<{
    providerId: string;
    dataCategories: string[];
    retentionDisclosureFingerprint: string;
  }>;
};
