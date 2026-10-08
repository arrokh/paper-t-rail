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

export type RecoveryUploadIntent = {
  upload: RecoveryUpload;
  uploadUrl: string | null;
  requiredHeaders: Record<string, string>;
  uploadUrlExpiresAt: string | null;
};
