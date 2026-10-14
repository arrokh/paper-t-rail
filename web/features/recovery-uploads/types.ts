export type RecoveryRightsDeclaration = {
  version: string;
  text: string;
  maxFileBytes: number;
  maxFilesPerBatch: number;
  maxBatchBytes: number;
  uploadUrlTtlSeconds: number;
  inactivityTtlSeconds: number;
};

export type RecoveryUploadStatus =
  | "PENDING_UPLOAD"
  | "FINALIZING"
  | "STAGED"
  | "REJECTED"
  | "REMOVED"
  | "EXPIRED";

export type RecoveryUpload = {
  id: string;
  localReferenceKey: string;
  idempotencyKey: string;
  filename: string;
  expectedSize: number;
  expectedSha256: string;
  status: RecoveryUploadStatus;
  failureCode: string | null;
  actualSize: number | null;
  actualSha256: string | null;
  createdAt: string;
  finalizedAt: string | null;
};

export type RecoveryAssetSelection = {
  id: string;
  batchId: string;
  analysisRunId: string;
  bibliographyEntryId: string;
  uploadId: string;
  validationAttemptId: string;
  contentSha256: string;
  selectionMethod: "MACHINE_VALIDATED" | "HUMAN_CONFIRMED";
  selectedAt: string;
};

export type RecoveryIdentifierAssistance = {
  bibliographyEntryId: string;
  localReferenceKey: string;
  identifierType: "DOI";
  identifierValue: string;
  recordedAt: string;
};

export type RecoveryBatch = {
  id: string;
  analysisRunId: string;
  status: "OPEN" | "SUBMITTED" | "EXPIRED";
  rightsDeclarationVersion: string | null;
  rightsDeclarationText: string | null;
  rightsDeclaredAt: string | null;
  createdAt: string;
  lastActivityAt: string;
  expiresAt: string;
  revision: number;
  uploads: RecoveryUpload[];
  selectedAssets: RecoveryAssetSelection[];
  identifierAssistance: RecoveryIdentifierAssistance[];
};

export type RecoveryIdentityOutcome = "VALIDATED" | "NEEDS_CONFIRMATION" | "MISMATCH";
export type RecoveryLanguageEligibility = "ELIGIBLE" | "INELIGIBLE" | "INDETERMINATE";
export type RecoveryValidationStatus = "COMPLETED" | "FAILED";

export type RecoveryMetadataCandidate = {
  field: "TITLE" | "AUTHORS" | "DOI";
  value: string;
  pageNumber: number;
  sourceLabel: string;
  extractionMethod: string;
  sourceElementId: string | null;
  sourceCharSpanStart: number | null;
  sourceCharSpanEnd: number | null;
  candidateFingerprint: string;
  normalizedDoi: string | null;
};

export type RecoveryUploadValidation = {
  id: string;
  batchId: string;
  uploadId: string;
  analysisRunId: string;
  contentSha256: string;
  parserId: string;
  parserVersion: string;
  metadataExtractionPolicyVersion: string;
  identityPolicyVersion?: string;
  parserOptions: Record<string, string>;
  languageDetectorId: string;
  languageDetectorVersion: string;
  minimumLanguageConfidence: number;
  minimumExtractedCharacters?: number | null;
  languagePolicyFingerprint?: string | null;
  validationStatus: RecoveryValidationStatus;
  identityOutcome: RecoveryIdentityOutcome | null;
  identityReasonCode: string | null;
  humanConfirmation: {
    id: string;
    batchId: string;
    uploadId: string;
    validationAttemptId: string;
    contentSha256: string;
    decision: string;
    confirmedAt: string;
    identifierConfirmation: {
      id: string;
      batchId: string;
      uploadId: string;
      analysisRunId: string;
      validationAttemptId: string;
      contentSha256: string;
      identifierType: "DOI";
      identifierValue: string;
      candidateFingerprint: string;
      confirmedAt: string;
    } | null;
  } | null;
  selection: RecoveryAssetSelection | null;
  metadataCandidates: RecoveryMetadataCandidate[];
  languageEligibility: RecoveryLanguageEligibility;
  detectedLanguage: string | null;
  languageConfidence: number | null;
  languageReasonCode: string;
  failureCode: string | null;
  createdAt: string;
};

export type RecoveryUploadIntent = {
  upload: RecoveryUpload;
  uploadUrl: string | null;
  requiredHeaders: Record<string, string>;
  uploadUrlExpiresAt: string | null;
};

export type RecoveryHistorySubmission = {
  successorAnalysisRunId: string;
  status: string;
  selectedEntryCount: number;
  identifierAssistanceEntryCount: number;
  resolutionQueuedEntryCount: number;
};

export type RecoveryHistoryEvent = {
  id: string;
  batchId: string;
  batchStatus: string;
  type: string;
  outcomeCode: string;
  stageCode: string | null;
  attemptNumber: number | null;
  bibliographyEntryId: string | null;
  uploadId: string | null;
  previousUploadId: string | null;
  submission: RecoveryHistorySubmission | null;
  createdAt: string;
};

export type RecoveryHistoryPage = {
  analysisRunId: string;
  events: RecoveryHistoryEvent[];
  nextCursor: string | null;
};

export type RecoverySuccessorStageDecision = {
  stage: string;
  disposition: "REUSE" | "REPROCESS" | "RETAIN" | "SKIP";
  reasonCode: string;
};

export type RecoverySuccessorPreview = {
  batchId: string;
  predecessorAnalysisRunId: string;
  revision: number;
  planFingerprint: string;
  eligible: boolean;
  selectedEntries: Array<{
    predecessorBibliographyEntryId: string;
    localReferenceKey: string;
    uploadId: string;
    contentSha256: string;
    assistedDoi: string | null;
    stages: RecoverySuccessorStageDecision[];
    blockers: string[];
  }>;
  identifierAssistanceEntries: Array<{
    predecessorBibliographyEntryId: string;
    localReferenceKey: string;
    identifierType: "DOI";
    identifierValue: string;
    stages: RecoverySuccessorStageDecision[];
    blockers: string[];
  }>;
  retainedReferenceKeys: string[];
  retainedDecision: RecoverySuccessorStageDecision;
  invalidationReasons: string[];
  blockers: string[];
  expectedProviderWork: Array<{
    stage: string;
    providerId: string;
    trustBoundary: string;
    dataCategories: string[];
    retentionDisclosure: string | null;
  }>;
  requiredConsents: Array<{
    providerId: string;
    dataCategories: string[];
    retentionDisclosure: string | null;
  }>;
};

export type RecoverySuccessorSubmission = {
  submissionId: string;
  successorAnalysisRunId: string;
  predecessorAnalysisRunId: string;
  revision: number;
  planFingerprint: string;
  selectedEntryCount: number;
  identifierAssistanceEntryCount: number;
  resolutionQueuedEntryCount: number;
  retainedEntryCount: number;
  queuedEntryCount: number;
  replayed: boolean;
};

export type RecoverySuccessorRunConfiguration = {
  captureExecution: boolean;
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
