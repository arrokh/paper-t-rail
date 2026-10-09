import {
  mutationOptions,
  queryOptions,
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
} from "../types.ts";

export function recoveryRightsDeclarationQueryOptions() {
  return queryOptions({
    queryKey: ["recovery-uploads", "rights-declaration"] as const,
    queryFn: async ({ signal }): Promise<RecoveryRightsDeclaration> => {
      const response = await fetch("/api/v1/recovery-rights-declaration", {
        cache: "no-store",
        signal,
      });
      if (!response.ok) throw new Error(await readApiError(response));
      return (await response.json()) as RecoveryRightsDeclaration;
    },
    retry: false,
    refetchOnWindowFocus: false,
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
  return useQuery(recoveryRightsDeclarationQueryOptions());
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

function invalidateRecoveryBatch(queryClient: QueryClient, analysisRunId: string) {
  return queryClient.invalidateQueries({
    queryKey: recoveryBatchQueryKey(analysisRunId),
    refetchType: "active",
  });
}

export type CreateRecoveryBatch = {
  analysisRunId: string;
  idempotencyKey: string;
  rightsDeclarationVersion: string;
  rightsDeclarationAccepted: true;
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
        body: JSON.stringify({ idempotencyKey, rightsDeclarationVersion, rightsDeclarationAccepted }),
      });
      if (!response.ok) throw new Error(await readApiError(response));
      return (await response.json()) as RecoveryBatch;
    },
    onSettled: (_batch, _error, input) => invalidateRecoveryBatch(queryClient, input.analysisRunId),
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
  if (!response.ok) throw new Error(await readApiError(response));
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
  if (!response.ok) throw new Error(await readApiError(response));
  return (await response.json()) as RecoveryUpload;
}

export function uploadRecoveryPdfMutationOptions(queryClient: QueryClient) {
  return mutationOptions({
    mutationKey: ["recovery-uploads", "upload-pdf"] as const,
    mutationFn: uploadRecoveryPdf,
    onSettled: (_upload, _error, input) => invalidateRecoveryBatch(queryClient, input.analysisRunId),
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
      if (!response.ok) throw new Error(await readApiError(response));
      return (await response.json()) as RecoveryUpload;
    },
    onSettled: (_upload, _error, input) => invalidateRecoveryBatch(queryClient, input.analysisRunId),
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
      if (!response.ok) throw new Error(await readApiError(response));
      return (await response.json()) as RecoveryUploadValidation;
    },
    onSettled: (_attempt, _error, input) => queryClient.invalidateQueries({
      queryKey: recoveryUploadValidationQueryKey(input.batchId, input.uploadId),
      refetchType: "active",
    }),
  });
}

export type ConfirmRecoveryIdentity = {
  analysisRunId: string;
  batchId: string;
  uploadId: string;
  validationAttemptId: string;
};

export function confirmRecoveryIdentityMutationOptions(queryClient: QueryClient) {
  return mutationOptions({
    mutationKey: ["recovery-uploads", "confirm-identity"] as const,
    mutationFn: async ({ batchId, uploadId, validationAttemptId }: ConfirmRecoveryIdentity) => {
      const response = await fetch(
        `/api/v1/recovery-batches/${encodeURIComponent(batchId)}/uploads/${encodeURIComponent(uploadId)}/validation/${encodeURIComponent(validationAttemptId)}/confirm-identity`,
        {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ confirmExactVersion: true }),
        },
      );
      if (!response.ok) throw new Error(await readApiError(response));
      return response.json();
    },
    onSettled: (_confirmation, _error, input) => queryClient.invalidateQueries({
      queryKey: recoveryUploadValidationQueryKey(input.batchId, input.uploadId),
      refetchType: "active",
    }),
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
      if (!response.ok) throw new Error(await readApiError(response));
      return response.json();
    },
    onSettled: (_selection, _error, input) => queryClient.invalidateQueries({
      queryKey: recoveryUploadValidationQueryKey(input.batchId, input.uploadId),
      refetchType: "active",
    }),
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

export function useCreateRecoveryBatch() {
  const queryClient = useQueryClient();
  return useMutation(createRecoveryBatchMutationOptions(queryClient));
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
