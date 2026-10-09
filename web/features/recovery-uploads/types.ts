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

export type RecoveryBatch = {
  id: string;
  analysisRunId: string;
  status: "OPEN" | "EXPIRED";
  rightsDeclarationVersion: string;
  rightsDeclarationText: string;
  rightsDeclaredAt: string;
  createdAt: string;
  lastActivityAt: string;
  expiresAt: string;
  uploads: RecoveryUpload[];
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
  } | null;
  selection: {
    id: string;
    batchId: string;
    analysisRunId: string;
    bibliographyEntryId: string;
    uploadId: string;
    validationAttemptId: string;
    contentSha256: string;
    selectionMethod: "MACHINE_VALIDATED" | "HUMAN_CONFIRMED";
    selectedAt: string;
  } | null;
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
