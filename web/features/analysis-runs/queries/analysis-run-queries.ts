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
} from "../types.ts";

const RUN_PAGE_SIZE = 25;
const RUN_POLL_INTERVAL_MS = 2500;

export const RECENT_ANALYSIS_RUNS_QUERY_KEY = ["analysis-runs", "recent"] as const;
export const UPLOAD_ANALYSIS_RUN_MUTATION_KEY = ["analysis-runs", "upload"] as const;
export const REANALYZE_ANALYSIS_RUN_MUTATION_KEY = ["analysis-runs", "reanalyze"] as const;

export function isTerminalAnalysisRun(run: Pick<AnalysisRun, "status">): boolean {
  return run.status === "COMPLETED"
    || run.status === "COMPLETED_WITH_WARNINGS"
    || run.status === "FAILED";
}

async function fetchAnalysisRunPage(cursor: string | null, signal: AbortSignal): Promise<AnalysisRunPage> {
  const query = new URLSearchParams({ limit: String(RUN_PAGE_SIZE) });
  if (cursor) query.set("cursor", cursor);

  const response = await fetch(`/api/v1/analysis-runs?${query.toString()}`, {
    cache: "no-store",
    signal,
  });
  if (!response.ok) throw new Error(await readApiError(response));
  return (await response.json()) as AnalysisRunPage;
}

export function recentAnalysisRunsQueryOptions(cursor: string | null) {
  return queryOptions({
    queryKey: [...RECENT_ANALYSIS_RUNS_QUERY_KEY, cursor] as const,
    queryFn: ({ signal }) => fetchAnalysisRunPage(cursor, signal),
    refetchInterval: (query) =>
      query.state.data?.items.some((run) => !isTerminalAnalysisRun(run))
        ? RUN_POLL_INTERVAL_MS
        : false,
    refetchIntervalInBackground: true,
    refetchOnWindowFocus: false,
    retry: false,
  });
}

export function useRecentAnalysisRuns(cursor: string | null) {
  return useQuery(recentAnalysisRunsQueryOptions(cursor));
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

export function useUploadAnalysisRun() {
  const queryClient = useQueryClient();
  return useMutation(uploadAnalysisRunMutationOptions(queryClient));
}

export function useReanalyzeDocument() {
  const queryClient = useQueryClient();
  return useMutation(reanalyzeDocumentMutationOptions(queryClient));
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

export function referenceResolutionReportQueryOptions(analysisRunId: string) {
  return queryOptions({
    queryKey: ["analysis-runs", "report", analysisRunId] as const,
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

export function useReferenceResolutionReport(analysisRunId: string | null, enabled: boolean) {
  return useQuery({
    ...referenceResolutionReportQueryOptions(analysisRunId ?? ""),
    enabled: Boolean(analysisRunId && enabled),
  });
}
