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

const RUN_PAGE_SIZE = 100;
const RUN_POLL_INTERVAL_MS = 2500;

export const RECENT_ANALYSIS_RUNS_QUERY_KEY = ["analysis-runs", "recent"] as const;
export const UPLOAD_ANALYSIS_RUN_MUTATION_KEY = ["analysis-runs", "upload"] as const;
export const REANALYZE_ANALYSIS_RUN_MUTATION_KEY = ["analysis-runs", "reanalyze"] as const;
export const DELETE_SOURCE_DOCUMENT_MUTATION_KEY = ["analysis-runs", "delete-document"] as const;
export const HUMAN_REVIEW_MUTATION_KEY = ["analysis-runs", "human-review"] as const;

export function isTerminalAnalysisRun(run: Pick<AnalysisRun, "status">): boolean {
  return run.status === "COMPLETED"
    || run.status === "COMPLETED_WITH_WARNINGS"
    || run.status === "FAILED";
}

async function fetchRecentAnalysisRuns(signal: AbortSignal): Promise<AnalysisRunPage> {
  const items: AnalysisRun[] = [];
  const visitedCursors = new Set<string>();
  let cursor: string | null = null;

  do {
    const query = new URLSearchParams({ limit: String(RUN_PAGE_SIZE) });
    if (cursor) query.set("cursor", cursor);

    const response = await fetch(`/api/v1/analysis-runs?${query.toString()}`, {
      cache: "no-store",
      signal,
    });
    if (!response.ok) throw new Error(await readApiError(response));

    const page = (await response.json()) as AnalysisRunPage;
    items.push(...page.items);
    cursor = page.nextCursor;
    if (cursor && visitedCursors.has(cursor)) {
      throw new Error("The Analysis Run list returned a repeated pagination cursor.");
    }
    if (cursor) visitedCursors.add(cursor);
  } while (cursor);

  return { items, nextCursor: null };
}

export function recentAnalysisRunsQueryOptions(cursor?: string | null) {
  return queryOptions({
    queryKey: cursor === undefined ? RECENT_ANALYSIS_RUNS_QUERY_KEY : [...RECENT_ANALYSIS_RUNS_QUERY_KEY, cursor] as const,
    queryFn: ({ signal }) => fetchRecentAnalysisRuns(signal),
    refetchOnWindowFocus: true,
    retry: false,
  });
}

export function useRecentAnalysisRuns(cursor?: string | null) {
  return useQuery(recentAnalysisRunsQueryOptions(cursor));
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
    refetchIntervalInBackground: true,
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
