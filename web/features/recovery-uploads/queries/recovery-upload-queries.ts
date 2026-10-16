import {
  mutationOptions,
  queryOptions,
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from "@tanstack/react-query";
import { readApiError } from "../../../lib/read-api-error.ts";
import type {
  RecoveryBatch,
  RecoveryRightsDeclaration,
  RecoveryUpload,
  RecoveryUploadIntent,
  RecoveryUploadValidation,
  RecoveryHistoryPage,
  RecoverySuccessorPreview,
  RecoverySuccessorRunConfiguration,
  RecoverySuccessorSubmission,
} from "../types.ts";

const recoveryRightsDeclarationQueryKey = ["recovery-uploads", "rights-declaration"] as const;
const recoverySubmissionsDisabledQueryKey = ["recovery-uploads", "submissions-disabled"] as const;
const recoverySubmissionsDisabledCode = "RECOVERY_SUBMISSIONS_DISABLED";

class RecoverySubmissionsDisabledError extends Error {
  readonly code = recoverySubmissionsDisabledCode;

  constructor(message: string) {
    super(`${message} (${recoverySubmissionsDisabledCode})`);
    this.name = "RecoverySubmissionsDisabledError";
  }
}

async function recoveryApiError(response: Response): Promise<Error> {
  try {
    const error = (await response.clone().json()) as { code?: unknown; message?: unknown };
    if (response.status === 503 && error.code === recoverySubmissionsDisabledCode) {
      const message = typeof error.message === "string" && error.message.length > 0
        ? error.message
        : "New recovery submissions are disabled.";
      return new RecoverySubmissionsDisabledError(message);
    }
  } catch {
    // Fall back to the shared API error message for other or non-JSON responses.
  }
  return new Error(await readApiError(response));
}

function latchRecoverySubmissionsDisabled(queryClient: QueryClient) {
  queryClient.setQueryData(recoverySubmissionsDisabledQueryKey, true);
}

function failClosedRecoverySubmissionCapability(queryClient: QueryClient, error: unknown) {
  if (!(error instanceof RecoverySubmissionsDisabledError)) return;

  latchRecoverySubmissionsDisabled(queryClient);
  queryClient.setQueryData<RecoveryRightsDeclaration>(recoveryRightsDeclarationQueryKey, (current) =>
    current ? { ...current, submissionsEnabled: false } : current,
  );
  void queryClient.invalidateQueries({ queryKey: recoveryRightsDeclarationQueryKey, refetchType: "active" });
}

function recoverySubmissionsDisabledQueryOptions() {
  return queryOptions({
    queryKey: recoverySubmissionsDisabledQueryKey,
    queryFn: () => false,
    initialData: false,
    staleTime: Infinity,
    gcTime: Infinity,
  });
}

export function recoveryRightsDeclarationQueryOptions() {
  return queryOptions({
    queryKey: recoveryRightsDeclarationQueryKey,
    queryFn: async ({ signal, client }): Promise<RecoveryRightsDeclaration> => {
      const response = await fetch("/api/v1/recovery-rights-declaration", {
        cache: "no-store",
        signal,
      });
      if (!response.ok) throw new Error(await readApiError(response));

      const declaration = (await response.json()) as RecoveryRightsDeclaration;
      const capabilityWasDisabled = client.getQueryData<boolean>(recoverySubmissionsDisabledQueryKey) === true;
      if (declaration.submissionsEnabled !== true) latchRecoverySubmissionsDisabled(client);
      return capabilityWasDisabled || declaration.submissionsEnabled !== true
        ? { ...declaration, submissionsEnabled: false }
        : declaration;
    },
    retry: false,
    refetchOnWindowFocus: true,
  });
}

export function recoveryBatchQueryKey(analysisRunId: string) {
  return ["recovery-uploads", "batch", analysisRunId] as const;
}

export function recoveryBatchQueryOptions(analysisRunId: string) {
  return queryOptions({
    queryKey: recoveryBatchQueryKey(analysisRunId),
    queryFn: async ({ signal }): Promise<RecoveryBatch | null> => {
      const response = await fetch(`/api/v1/analysis-runs/${encodeURIComponent(analysisRunId)}/recovery-batches`, {
        cache: "no-store",
        signal,
      });
      if (!response.ok) throw new Error(await readApiError(response));
      const batches = (await response.json()) as RecoveryBatch[];
      return batches[0] ?? null;
    },
    retry: false,
    refetchOnWindowFocus: true,
  });
}

export function useRecoveryRightsDeclaration() {
  const declarationQuery = useQuery(recoveryRightsDeclarationQueryOptions());
  const submissionsDisabledQuery = useQuery(recoverySubmissionsDisabledQueryOptions());

  return {
    ...declarationQuery,
    data: submissionsDisabledQuery.data && declarationQuery.data
      ? { ...declarationQuery.data, submissionsEnabled: false }
      : declarationQuery.data,
  };
}

export function useRecoveryBatch(analysisRunId: string, enabled: boolean) {
  return useQuery({ ...recoveryBatchQueryOptions(analysisRunId), enabled });
}

export function recoveryUploadValidationQueryKey(batchId: string, uploadId: string) {
  return ["recovery-uploads", "validation", batchId, uploadId] as const;
}

export function recoveryUploadValidationQueryOptions(batchId: string, uploadId: string) {
  return queryOptions({
    queryKey: recoveryUploadValidationQueryKey(batchId, uploadId),
    queryFn: async ({ signal }): Promise<RecoveryUploadValidation | null> => {
      const response = await fetch(
        `/api/v1/recovery-batches/${encodeURIComponent(batchId)}/uploads/${encodeURIComponent(uploadId)}/validation`,
        { cache: "no-store", signal },
      );
      if (response.status === 204) return null;
      if (!response.ok) throw new Error(await readApiError(response));
      return (await response.json()) as RecoveryUploadValidation;
    },
    retry: false,
    refetchOnWindowFocus: false,
  });
}

export function useRecoveryUploadValidation(batchId: string, uploadId: string, enabled: boolean) {
  return useQuery({ ...recoveryUploadValidationQueryOptions(batchId, uploadId), enabled });
}

export function recoveryHistoryQueryKey(analysisRunId: string) {
  return ["recovery-uploads", "history", analysisRunId] as const;
}

export function recoveryHistoryQueryOptions(analysisRunId: string) {
  return {
    queryKey: recoveryHistoryQueryKey(analysisRunId),
    queryFn: async ({ pageParam, signal }: { pageParam: string | null; signal: AbortSignal }): Promise<RecoveryHistoryPage> => {
      const params = new URLSearchParams({ limit: "50" });
      if (pageParam) params.set("cursor", pageParam);
      const response = await fetch(
        `/api/v1/analysis-runs/${encodeURIComponent(analysisRunId)}/recovery-history?${params.toString()}`,
        { cache: "no-store", signal },
      );
      if (!response.ok) throw new Error(await readApiError(response));
      return (await response.json()) as RecoveryHistoryPage;
    },
    initialPageParam: null as string | null,
    getNextPageParam: (page: RecoveryHistoryPage) => page.nextCursor ?? undefined,
    retry: false,
    refetchOnWindowFocus: true,
  };
}

export function useRecoveryHistory(analysisRunId: string, enabled: boolean) {
  return useInfiniteQuery({ ...recoveryHistoryQueryOptions(analysisRunId), enabled });
}

function invalidateRecoveryBatch(queryClient: QueryClient, analysisRunId: string) {
  return Promise.all([
    queryClient.invalidateQueries({
      queryKey: recoveryBatchQueryKey(analysisRunId),
      refetchType: "active",
    }),
    queryClient.invalidateQueries({
      queryKey: recoveryHistoryQueryKey(analysisRunId),
      refetchType: "active",
    }),
  ]);
}

export type CreateRecoveryBatch = {
  analysisRunId: string;
  idempotencyKey: string;
  rightsDeclarationVersion?: string;
  rightsDeclarationAccepted?: true;
};

export function createRecoveryBatchMutationOptions(queryClient: QueryClient) {
  return mutationOptions({
    mutationKey: ["recovery-uploads", "create-batch"] as const,
    mutationFn: async ({
      analysisRunId,
      idempotencyKey,
      rightsDeclarationVersion,
      rightsDeclarationAccepted,
    }: CreateRecoveryBatch): Promise<RecoveryBatch> => {
      const response = await fetch(`/api/v1/analysis-runs/${encodeURIComponent(analysisRunId)}/recovery-batches`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          idempotencyKey,
          ...(rightsDeclarationVersion && rightsDeclarationAccepted
            ? { rightsDeclarationVersion, rightsDeclarationAccepted }
            : {}),
        }),
      });
      if (!response.ok) throw await recoveryApiError(response);
      return (await response.json()) as RecoveryBatch;
    },
    onSettled: (_batch, error, input) => {
      failClosedRecoverySubmissionCapability(queryClient, error);
      return invalidateRecoveryBatch(queryClient, input.analysisRunId);
    },
  });
}

export type UploadRecoveryPdf = {
  analysisRunId: string;
  batchId: string;
  localReferenceKey: string;
  file: File;
  idempotencyKey: string;
};

async function sha256(file: File): Promise<string> {
  if (!globalThis.crypto?.subtle) {
    throw new Error("This browser cannot securely prepare a checksum. Use a current browser over a secure connection.");
  }
  const digest = await globalThis.crypto.subtle.digest("SHA-256", await file.arrayBuffer());
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function createUploadIntent(input: UploadRecoveryPdf, expectedSha256: string): Promise<RecoveryUploadIntent> {
  const response = await fetch(
    `/api/v1/recovery-batches/${encodeURIComponent(input.batchId)}/entries/${encodeURIComponent(input.localReferenceKey)}/uploads`,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        idempotencyKey: input.idempotencyKey,
        filename: input.file.name,
        expectedSize: input.file.size,
        expectedSha256,
      }),
    },
  );
  if (!response.ok) throw await recoveryApiError(response);
  return (await response.json()) as RecoveryUploadIntent;
}

async function uploadRecoveryPdf(input: UploadRecoveryPdf): Promise<RecoveryUpload> {
  if (!input.file.name.toLocaleLowerCase().endsWith(".pdf") || input.file.size === 0) {
    throw new Error("Choose a non-empty PDF file.");
  }

  const expectedSha256 = await sha256(input.file);
  const intent = await createUploadIntent(input, expectedSha256);
  if (intent.upload.status === "STAGED") return intent.upload;
  if (!intent.uploadUrl) throw new Error("The upload is not ready for a browser transfer. Refresh the batch and try again.");

  let uploadResponse: Response;
  try {
    uploadResponse = await fetch(intent.uploadUrl, {
      method: "PUT",
      mode: "cors",
      credentials: "omit",
      referrerPolicy: "no-referrer",
      cache: "no-store",
      headers: intent.requiredHeaders,
      body: input.file,
    });
  } catch {
    throw new Error("The private storage upload did not complete. Select the same file to resume this upload.");
  }
  if (!uploadResponse.ok) {
    throw new Error("The private storage upload did not complete. Select the same file to resume this upload.");
  }

  const response = await fetch(
    `/api/v1/recovery-batches/${encodeURIComponent(input.batchId)}/uploads/${encodeURIComponent(intent.upload.id)}/finalize`,
    { method: "POST" },
  );
  if (!response.ok) throw await recoveryApiError(response);
  return (await response.json()) as RecoveryUpload;
}

export function uploadRecoveryPdfMutationOptions(queryClient: QueryClient) {
  return mutationOptions({
    mutationKey: ["recovery-uploads", "upload-pdf"] as const,
    mutationFn: uploadRecoveryPdf,
    onSettled: (_upload, error, input) => {
      failClosedRecoverySubmissionCapability(queryClient, error);
      return invalidateRecoveryBatch(queryClient, input.analysisRunId);
    },
  });
}

export type FinalizeRecoveryUpload = {
  analysisRunId: string;
  batchId: string;
  uploadId: string;
};

export function finalizeRecoveryUploadMutationOptions(queryClient: QueryClient) {
  return mutationOptions({
    mutationKey: ["recovery-uploads", "finalize"] as const,
    mutationFn: async ({ batchId, uploadId }: FinalizeRecoveryUpload): Promise<RecoveryUpload> => {
      const response = await fetch(
        `/api/v1/recovery-batches/${encodeURIComponent(batchId)}/uploads/${encodeURIComponent(uploadId)}/finalize`,
        { method: "POST" },
      );
      if (!response.ok) throw await recoveryApiError(response);
      return (await response.json()) as RecoveryUpload;
    },
    onSettled: (_upload, error, input) => {
      failClosedRecoverySubmissionCapability(queryClient, error);
      return invalidateRecoveryBatch(queryClient, input.analysisRunId);
    },
  });
}

export type ValidateRecoveryUpload = {
  analysisRunId: string;
  batchId: string;
  uploadId: string;
};

export function validateRecoveryUploadMutationOptions(queryClient: QueryClient) {
  return mutationOptions({
    mutationKey: ["recovery-uploads", "validate"] as const,
    mutationFn: async ({ batchId, uploadId }: ValidateRecoveryUpload): Promise<RecoveryUploadValidation> => {
      const response = await fetch(
        `/api/v1/recovery-batches/${encodeURIComponent(batchId)}/uploads/${encodeURIComponent(uploadId)}/validate`,
        { method: "POST" },
      );
      if (!response.ok) throw await recoveryApiError(response);
      return (await response.json()) as RecoveryUploadValidation;
    },
    onSettled: (_attempt, error, input) => {
      failClosedRecoverySubmissionCapability(queryClient, error);
      return Promise.all([
        queryClient.invalidateQueries({
          queryKey: recoveryUploadValidationQueryKey(input.batchId, input.uploadId),
          refetchType: "active",
        }),
        queryClient.invalidateQueries({ queryKey: recoveryHistoryQueryKey(input.analysisRunId), refetchType: "active" }),
      ]);
    },
  });
}

export type ConfirmRecoveryIdentity = {
  analysisRunId: string;
  batchId: string;
  uploadId: string;
  validationAttemptId: string;
  confirmedDoi?: string;
  doiCandidateFingerprint?: string;
};

export function confirmRecoveryIdentityMutationOptions(queryClient: QueryClient) {
  return mutationOptions({
    mutationKey: ["recovery-uploads", "confirm-identity"] as const,
    mutationFn: async ({ batchId, uploadId, validationAttemptId, confirmedDoi, doiCandidateFingerprint }: ConfirmRecoveryIdentity) => {
      const response = await fetch(
        `/api/v1/recovery-batches/${encodeURIComponent(batchId)}/uploads/${encodeURIComponent(uploadId)}/validation/${encodeURIComponent(validationAttemptId)}/confirm-identity`,
        {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({
            confirmExactVersion: true,
            ...(confirmedDoi && doiCandidateFingerprint ? { confirmedDoi, doiCandidateFingerprint } : {}),
          }),
        },
      );
      if (!response.ok) throw await recoveryApiError(response);
      return response.json();
    },
    onSettled: (_confirmation, error, input) => {
      failClosedRecoverySubmissionCapability(queryClient, error);
      return Promise.all([
        queryClient.invalidateQueries({
          queryKey: recoveryUploadValidationQueryKey(input.batchId, input.uploadId),
          refetchType: "active",
        }),
        invalidateRecoveryBatch(queryClient, input.analysisRunId),
      ]);
    },
  });
}

export type SelectRecoveryUpload = {
  analysisRunId: string;
  batchId: string;
  uploadId: string;
};

export function selectRecoveryUploadMutationOptions(queryClient: QueryClient) {
  return mutationOptions({
    mutationKey: ["recovery-uploads", "select-version"] as const,
    mutationFn: async ({ batchId, uploadId }: SelectRecoveryUpload) => {
      const response = await fetch(
        `/api/v1/recovery-batches/${encodeURIComponent(batchId)}/uploads/${encodeURIComponent(uploadId)}/select`,
        { method: "POST" },
      );
      if (!response.ok) throw await recoveryApiError(response);
      return response.json();
    },
    onSettled: (_selection, error, input) => {
      failClosedRecoverySubmissionCapability(queryClient, error);
      return Promise.all([
        queryClient.invalidateQueries({
          queryKey: recoveryUploadValidationQueryKey(input.batchId, input.uploadId),
          refetchType: "active",
        }),
        invalidateRecoveryBatch(queryClient, input.analysisRunId),
      ]);
    },
  });
}

export type RemoveRecoveryUpload = {
  analysisRunId: string;
  batchId: string;
  uploadId: string;
};

export function removeRecoveryUploadMutationOptions(queryClient: QueryClient) {
  return mutationOptions({
    mutationKey: ["recovery-uploads", "remove"] as const,
    mutationFn: async ({ batchId, uploadId }: RemoveRecoveryUpload): Promise<RecoveryUpload> => {
      const response = await fetch(
        `/api/v1/recovery-batches/${encodeURIComponent(batchId)}/uploads/${encodeURIComponent(uploadId)}`,
        { method: "DELETE" },
      );
      if (!response.ok) throw new Error(await readApiError(response));
      return (await response.json()) as RecoveryUpload;
    },
    onSettled: (_upload, _error, input) => invalidateRecoveryBatch(queryClient, input.analysisRunId),
  });
}

export type AcceptRecoveryRights = {
  analysisRunId: string;
  batchId: string;
  rightsDeclarationVersion: string;
};

export function acceptRecoveryRightsMutationOptions(queryClient: QueryClient) {
  return mutationOptions({
    mutationKey: ["recovery-uploads", "accept-rights"] as const,
    mutationFn: async ({ batchId, rightsDeclarationVersion }: AcceptRecoveryRights): Promise<RecoveryBatch> => {
      const response = await fetch(`/api/v1/recovery-batches/${encodeURIComponent(batchId)}/rights-declaration`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ rightsDeclarationVersion, accepted: true }),
      });
      if (!response.ok) throw await recoveryApiError(response);
      return (await response.json()) as RecoveryBatch;
    },
    onSettled: (_batch, error, input) => {
      failClosedRecoverySubmissionCapability(queryClient, error);
      return invalidateRecoveryBatch(queryClient, input.analysisRunId);
    },
  });
}

export type StageRecoveryIdentifierAssistance = {
  analysisRunId: string;
  batchId: string;
  localReferenceKey: string;
  identifierValue: string;
};

export function stageRecoveryIdentifierAssistanceMutationOptions(queryClient: QueryClient) {
  return mutationOptions({
    mutationKey: ["recovery-uploads", "stage-identifier-assistance"] as const,
    mutationFn: async ({ batchId, localReferenceKey, identifierValue }: StageRecoveryIdentifierAssistance) => {
      const response = await fetch(
        `/api/v1/recovery-batches/${encodeURIComponent(batchId)}/entries/${encodeURIComponent(localReferenceKey)}/identifier-assistance`,
        {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({
            identifierType: "DOI",
            identifierValue: identifierValue.trim(),
            confirmsBibliographyIdentity: true,
          }),
        },
      );
      if (!response.ok) throw await recoveryApiError(response);
      return response.json();
    },
    onSettled: (_assistance, error, input) => {
      failClosedRecoverySubmissionCapability(queryClient, error);
      return invalidateRecoveryBatch(queryClient, input.analysisRunId);
    },
  });
}

export type RemoveRecoveryIdentifierAssistance = {
  analysisRunId: string;
  batchId: string;
  localReferenceKey: string;
};

export function removeRecoveryIdentifierAssistanceMutationOptions(queryClient: QueryClient) {
  return mutationOptions({
    mutationKey: ["recovery-uploads", "remove-identifier-assistance"] as const,
    mutationFn: async ({ batchId, localReferenceKey }: RemoveRecoveryIdentifierAssistance): Promise<void> => {
      const response = await fetch(
        `/api/v1/recovery-batches/${encodeURIComponent(batchId)}/entries/${encodeURIComponent(localReferenceKey)}/identifier-assistance`,
        { method: "DELETE" },
      );
      if (!response.ok) throw new Error(await readApiError(response));
    },
    onSettled: (_result, _error, input) => invalidateRecoveryBatch(queryClient, input.analysisRunId),
  });
}

export type PreviewRecoverySuccessor = {
  analysisRunId: string;
  batchId: string;
  expectedRevision: number;
  configuration: RecoverySuccessorRunConfiguration;
};

export function previewRecoverySuccessorMutationOptions() {
  return mutationOptions({
    mutationKey: ["recovery-uploads", "successor-preview"] as const,
    mutationFn: async ({ batchId, expectedRevision, configuration }: PreviewRecoverySuccessor): Promise<RecoverySuccessorPreview> => {
      const response = await fetch(`/api/v1/recovery-batches/${encodeURIComponent(batchId)}/successor/preview`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ expectedRevision, configuration }),
      });
      if (!response.ok) throw new Error(await readApiError(response));
      return (await response.json()) as RecoverySuccessorPreview;
    },
  });
}

export type SubmitRecoverySuccessor = PreviewRecoverySuccessor & { idempotencyKey: string };

export function submitRecoverySuccessorMutationOptions(queryClient: QueryClient) {
  return mutationOptions({
    mutationKey: ["recovery-uploads", "successor-submit"] as const,
    mutationFn: async ({ batchId, idempotencyKey, expectedRevision, configuration }: SubmitRecoverySuccessor): Promise<RecoverySuccessorSubmission> => {
      const response = await fetch(`/api/v1/recovery-batches/${encodeURIComponent(batchId)}/successor`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ idempotencyKey, expectedRevision, configuration }),
      });
      if (!response.ok) throw await recoveryApiError(response);
      return (await response.json()) as RecoverySuccessorSubmission;
    },
    onSettled: (_submission, error, input) => {
      failClosedRecoverySubmissionCapability(queryClient, error);
      return invalidateRecoveryBatch(queryClient, input.analysisRunId);
    },
  });
}

export function useCreateRecoveryBatch() {
  const queryClient = useQueryClient();
  return useMutation(createRecoveryBatchMutationOptions(queryClient));
}

export function useAcceptRecoveryRights() {
  const queryClient = useQueryClient();
  return useMutation(acceptRecoveryRightsMutationOptions(queryClient));
}

export function useStageRecoveryIdentifierAssistance() {
  const queryClient = useQueryClient();
  return useMutation(stageRecoveryIdentifierAssistanceMutationOptions(queryClient));
}

export function useRemoveRecoveryIdentifierAssistance() {
  const queryClient = useQueryClient();
  return useMutation(removeRecoveryIdentifierAssistanceMutationOptions(queryClient));
}

export function usePreviewRecoverySuccessor() {
  return useMutation(previewRecoverySuccessorMutationOptions());
}

export function useSubmitRecoverySuccessor() {
  const queryClient = useQueryClient();
  return useMutation(submitRecoverySuccessorMutationOptions(queryClient));
}

export function useUploadRecoveryPdf() {
  const queryClient = useQueryClient();
  return useMutation(uploadRecoveryPdfMutationOptions(queryClient));
}

export function useFinalizeRecoveryUpload() {
  const queryClient = useQueryClient();
  return useMutation(finalizeRecoveryUploadMutationOptions(queryClient));
}

export function useRemoveRecoveryUpload() {
  const queryClient = useQueryClient();
  return useMutation(removeRecoveryUploadMutationOptions(queryClient));
}

export function useValidateRecoveryUpload() {
  const queryClient = useQueryClient();
  return useMutation(validateRecoveryUploadMutationOptions(queryClient));
}

export function useConfirmRecoveryIdentity() {
  const queryClient = useQueryClient();
  return useMutation(confirmRecoveryIdentityMutationOptions(queryClient));
}

export function useSelectRecoveryUpload() {
  const queryClient = useQueryClient();
  return useMutation(selectRecoveryUploadMutationOptions(queryClient));
}
