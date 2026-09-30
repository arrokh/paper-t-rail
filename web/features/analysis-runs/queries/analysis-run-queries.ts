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
  AnalysisRun,
  AnalysisRunConfiguration,
  AnalysisRunPage,
  CreatedRun,
  ParsedDocument,
  ReferenceResolutionReportResponse,
  HumanReview,
  HumanReviewAction,
  HumanReviewStatus,
} from "../types.ts";

const RUN_PAGE_SIZE = 20;
const RUN_POLL_INTERVAL_MS = 2500;

export const RECENT_ANALYSIS_RUNS_QUERY_KEY = ["analysis-runs", "recent"] as const;
export const UPLOAD_ANALYSIS_RUN_MUTATION_KEY = ["analysis-runs", "upload"] as const;
export const REANALYZE_ANALYSIS_RUN_MUTATION_KEY = ["analysis-runs", "reanalyze"] as const;
export const DELETE_SOURCE_DOCUMENT_MUTATION_KEY = ["analysis-runs", "delete-document"] as const;
export const HUMAN_REVIEW_MUTATION_KEY = ["analysis-runs", "human-review"] as const;

function isTerminalRunStatus(status: string): boolean {
  return status === "PARSED"
    || status === "COMPLETED"
    || status === "COMPLETED_WITH_WARNINGS"
    || status === "FAILED";
}

export function isTerminalAnalysisRun(run: Pick<AnalysisRun, "status">): boolean {
  return isTerminalRunStatus(run.status);
}

type RecentAnalysisRunsOptions = { cursor?: string | null; query?: string; status?: string };

async function fetchRecentAnalysisRuns(signal: AbortSignal, options: RecentAnalysisRunsOptions): Promise<AnalysisRunPage> {
  const params = new URLSearchParams({ limit: String(RUN_PAGE_SIZE) });
  if (options.cursor) params.set("cursor", options.cursor);
  if (options.query) params.set("q", options.query);
  if (options.status) params.set("status", options.status);

  const response = await fetch(`/api/v1/analysis-runs?${params.toString()}`, { cache: "no-store", signal });
  if (!response.ok) throw new Error(await readApiError(response));
  return (await response.json()) as AnalysisRunPage;
}

function normalizeRecentOptions(input?: string | null | RecentAnalysisRunsOptions): Required<RecentAnalysisRunsOptions> {
  if (typeof input === "string" || input === null) return { cursor: input, query: "", status: "" };
  return { cursor: input?.cursor ?? null, query: input?.query?.trim() ?? "", status: input?.status ?? "" };
}

export function recentAnalysisRunsQueryOptions(input?: string | null | RecentAnalysisRunsOptions) {
  const options = normalizeRecentOptions(input);
  return queryOptions({
    queryKey: [...RECENT_ANALYSIS_RUNS_QUERY_KEY, options.cursor, options.query, options.status] as const,
    queryFn: ({ signal }) => fetchRecentAnalysisRuns(signal, options),
    refetchInterval: (query) => query.state.data?.items.some((run) => !isTerminalAnalysisRun(run))
      ? RUN_POLL_INTERVAL_MS
      : false,
    refetchOnWindowFocus: true,
    retry: false,
  });
}

export function useRecentAnalysisRuns(input?: string | null | RecentAnalysisRunsOptions) {
  return useQuery(recentAnalysisRunsQueryOptions(input));
}

export function analysisRunQueryKey(analysisRunId: string) {
  return ["analysis-runs", "detail", analysisRunId] as const;
}

export function analysisRunQueryOptions(analysisRunId: string) {
  return queryOptions({
    queryKey: analysisRunQueryKey(analysisRunId),
    queryFn: async ({ signal }): Promise<AnalysisRun> => {
      const response = await fetch(`/api/v1/analysis-runs/${encodeURIComponent(analysisRunId)}`, {
        cache: "no-store",
        signal,
      });
      if (!response.ok) throw new Error(await readApiError(response));
      return (await response.json()) as AnalysisRun;
    },
    refetchInterval: (query) => query.state.data && !isTerminalAnalysisRun(query.state.data)
      ? RUN_POLL_INTERVAL_MS
      : false,
    refetchOnWindowFocus: false,
    retry: false,
  });
}

export function useAnalysisRun(analysisRunId: string) {
  return useQuery(analysisRunQueryOptions(analysisRunId));
}

async function refreshRecentAnalysisRuns(queryClient: QueryClient): Promise<void> {
  await queryClient.cancelQueries({ queryKey: RECENT_ANALYSIS_RUNS_QUERY_KEY });
  await queryClient.invalidateQueries({
    queryKey: RECENT_ANALYSIS_RUNS_QUERY_KEY,
    refetchType: "active",
  });
}

export type UploadAnalysisRun = {
  file: File;
  configuration: AnalysisRunConfiguration;
};

export type ReanalyzeDocument = {
  documentId: string;
  configuration: AnalysisRunConfiguration;
};

async function uploadAnalysisRun({ file, configuration }: UploadAnalysisRun): Promise<CreatedRun> {
  const data = new FormData();
  data.append("file", file);
  data.append("configuration", JSON.stringify(configuration));

  const response = await fetch("/api/v1/analysis-runs", { method: "POST", body: data });
  if (!response.ok) throw new Error(await readApiError(response));
  return (await response.json()) as CreatedRun;
}

async function reanalyzeDocument({ documentId, configuration }: ReanalyzeDocument): Promise<CreatedRun> {
  const response = await fetch(`/api/v1/documents/${encodeURIComponent(documentId)}/analysis-runs`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(configuration),
  });
  if (!response.ok) throw new Error(await readApiError(response));
  return (await response.json()) as CreatedRun;
}

async function deleteSourceDocument(documentId: string): Promise<void> {
  const response = await fetch(`/api/v1/documents/${encodeURIComponent(documentId)}`, { method: "DELETE" });
  if (!response.ok) throw new Error(await readApiError(response));
}

export function uploadAnalysisRunMutationOptions(queryClient: QueryClient) {
  return mutationOptions({
    mutationKey: UPLOAD_ANALYSIS_RUN_MUTATION_KEY,
    mutationFn: uploadAnalysisRun,
    onSuccess: () => refreshRecentAnalysisRuns(queryClient),
  });
}

export function reanalyzeDocumentMutationOptions(queryClient: QueryClient) {
  return mutationOptions({
    mutationKey: REANALYZE_ANALYSIS_RUN_MUTATION_KEY,
    mutationFn: reanalyzeDocument,
    onSuccess: () => refreshRecentAnalysisRuns(queryClient),
  });
}

export function deleteSourceDocumentMutationOptions(queryClient: QueryClient) {
  return mutationOptions({
    mutationKey: DELETE_SOURCE_DOCUMENT_MUTATION_KEY,
    mutationFn: deleteSourceDocument,
    onSuccess: async (_data, documentId) => {
      const cachedRunPages = queryClient.getQueriesData<AnalysisRunPage>({ queryKey: RECENT_ANALYSIS_RUNS_QUERY_KEY });
      const deletedRunIds = cachedRunPages.flatMap(([, page]) =>
        page?.items.filter((run) => run.documentId === documentId).map((run) => run.id) ?? [],
      );
      queryClient.setQueriesData<AnalysisRunPage>({ queryKey: RECENT_ANALYSIS_RUNS_QUERY_KEY }, (page) => page && ({
        ...page,
        items: page.items.filter((run) => run.documentId !== documentId),
      }));
      deletedRunIds.forEach((runId) => {
        queryClient.removeQueries({ queryKey: analysisRunQueryKey(runId), exact: true });
        queryClient.removeQueries({ queryKey: ["analysis-runs", "parsed-document", runId], exact: true });
        queryClient.removeQueries({ queryKey: referenceResolutionReportQueryKey(runId), exact: true });
        queryClient.removeQueries({ queryKey: sourceDocumentPdfQueryKey(runId), exact: true });
      });
      await refreshRecentAnalysisRuns(queryClient);
    },
  });
}

export function useUploadAnalysisRun() {
  const queryClient = useQueryClient();
  return useMutation(uploadAnalysisRunMutationOptions(queryClient));
}

export function useReanalyzeDocument() {
  const queryClient = useQueryClient();
  return useMutation(reanalyzeDocumentMutationOptions(queryClient));
}

export function useDeleteSourceDocument() {
  const queryClient = useQueryClient();
  return useMutation(deleteSourceDocumentMutationOptions(queryClient));
}

export function parsedDocumentQueryOptions(analysisRunId: string) {
  return queryOptions({
    queryKey: ["analysis-runs", "parsed-document", analysisRunId] as const,
    queryFn: async ({ signal }): Promise<ParsedDocument> => {
      const response = await fetch(`/api/v1/analysis-runs/${encodeURIComponent(analysisRunId)}/parsed-document`, {
        cache: "no-store",
        signal,
      });
      if (!response.ok) throw new Error(await readApiError(response));
      return (await response.json()) as ParsedDocument;
    },
    retry: false,
  });
}

export function referenceResolutionReportQueryKey(analysisRunId: string) {
  return ["analysis-runs", "report", analysisRunId] as const;
}

export function sourceDocumentPdfQueryKey(analysisRunId: string) {
  return ["analysis-runs", "source-pdf", analysisRunId] as const;
}

export function sourceDocumentPdfQueryOptions(analysisRunId: string) {
  return queryOptions({
    queryKey: sourceDocumentPdfQueryKey(analysisRunId),
    queryFn: async ({ signal }): Promise<Blob> => {
      const response = await fetch(`/api/v1/analysis-runs/${encodeURIComponent(analysisRunId)}/source-document`, {
        headers: { accept: "application/pdf" },
        cache: "no-store",
        signal,
      });
      if (!response.ok) throw new Error(await readApiError(response));
      return response.blob();
    },
    staleTime: Infinity,
    gcTime: 0,
    retry: false,
  });
}

export function referenceResolutionReportQueryOptions(analysisRunId: string) {
  return queryOptions({
    queryKey: referenceResolutionReportQueryKey(analysisRunId),
    queryFn: async ({ signal }): Promise<ReferenceResolutionReportResponse> => {
      const response = await fetch(`/api/v1/analysis-runs/${encodeURIComponent(analysisRunId)}/report`, {
        cache: "no-store",
        signal,
      });
      if (!response.ok) throw new Error(await readApiError(response));
      return (await response.json()) as ReferenceResolutionReportResponse;
    },
    refetchInterval: (query) => query.state.data && !isTerminalRunStatus(query.state.data.runStatus)
      ? RUN_POLL_INTERVAL_MS
      : false,
    retry: false,
  });
}

export function useParsedDocument(analysisRunId: string | null, enabled: boolean) {
  return useQuery({
    ...parsedDocumentQueryOptions(analysisRunId ?? ""),
    enabled: Boolean(analysisRunId && enabled),
  });
}

export type RecordHumanReview = {
  verificationId: string;
  action: HumanReviewAction;
  overrideStatus?: HumanReviewStatus;
  note?: string;
};

export function recordHumanReviewMutationOptions(queryClient: QueryClient, analysisRunId: string) {
  return mutationOptions({
    mutationKey: [...HUMAN_REVIEW_MUTATION_KEY, analysisRunId] as const,
    mutationFn: async ({ verificationId, ...review }: RecordHumanReview): Promise<HumanReview> => {
      const response = await fetch(`/api/v1/verifications/${encodeURIComponent(verificationId)}/reviews`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(review),
      });
      if (!response.ok) throw new Error(await readApiError(response));
      return (await response.json()) as HumanReview;
    },
    onSuccess: () => queryClient.invalidateQueries({
      queryKey: referenceResolutionReportQueryKey(analysisRunId),
      refetchType: "active",
    }),
  });
}

export function useRecordHumanReview(analysisRunId: string) {
  const queryClient = useQueryClient();
  return useMutation(recordHumanReviewMutationOptions(queryClient, analysisRunId));
}

export function useReferenceResolutionReport(analysisRunId: string | null, enabled: boolean) {
  return useQuery({
    ...referenceResolutionReportQueryOptions(analysisRunId ?? ""),
    enabled: Boolean(analysisRunId && enabled),
  });
}
