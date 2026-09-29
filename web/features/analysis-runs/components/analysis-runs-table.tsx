"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { ArrowLeft, ArrowRight, ArrowUpRight, FileText, Plus, RefreshCw, Search, Trash2 } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { useDeleteSourceDocument, useRecentAnalysisRuns } from "@/features/analysis-runs/queries/analysis-run-queries";
import { WorkspaceBreadcrumb } from "@/features/workspace/components/workspace-breadcrumb";
import { PageTransitionLink } from "@/features/workspace/components/page-transition";
import type { AnalysisRun } from "@/features/analysis-runs/types";
import { cn } from "@/lib/utils";

const PAGE_SIZE = 20;
const STATUS_FILTERS = [
  ["QUEUED", "Queued"],
  ["PROCESSING", "Processing"],
  ["PARSED", "Parsed"],
  ["COMPLETED", "Completed"],
  ["COMPLETED_WITH_WARNINGS", "Completed with warnings"],
  ["FAILED", "Failed"],
] as const;
type RunStatus = AnalysisRun["status"];
const EMPTY_RUNS: AnalysisRun[] = [];

const STATUS_CLASS_NAMES: Record<RunStatus, string> = {
  QUEUED: "border-border bg-muted text-muted-foreground",
  PROCESSING: "border-primary/25 bg-primary/10 text-primary",
  PARSED: "border-primary/20 bg-primary/5 text-primary",
  COMPLETED: "border-primary/20 bg-primary/5 text-primary",
  COMPLETED_WITH_WARNINGS: "border-warning/40 bg-warning/10 text-warning-foreground",
  FAILED: "border-destructive/25 bg-destructive/10 text-destructive",
};

function isRunStatus(value: string | null): value is RunStatus {
  return STATUS_FILTERS.some(([status]) => status === value);
}

function formatStatus(status: RunStatus): string {
  return status.replaceAll("_", " ").toLowerCase();
}

function formatCreatedAt(value: string): string {
  return new Intl.DateTimeFormat("en-GB", {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "Asia/Jakarta",
  }).format(new Date(value));
}

function getListHref(runId: string, query: string, status: string, page: number): string {
  const params = new URLSearchParams();
  if (query) params.set("q", query);
  if (status !== "ALL") params.set("status", status);
  if (page > 1) params.set("page", String(page));
  const suffix = params.size > 0 ? `?${params.toString()}` : "";
  return `/analysis-runs/${encodeURIComponent(runId)}${suffix}`;
}

function updateSearchParams(
  searchParams: ReturnType<typeof useSearchParams>,
  router: ReturnType<typeof useRouter>,
  pathname: string,
  updates: Record<string, string | null>,
) {
  const next = new URLSearchParams(searchParams.toString());
  Object.entries(updates).forEach(([key, value]) => {
    if (value) next.set(key, value);
    else next.delete(key);
  });
  const suffix = next.size > 0 ? `?${next.toString()}` : "";
  router.replace(`${pathname}${suffix}`, { scroll: false });
}

export function AnalysisRunsTable({
  newestCreatedRunId,
  onAddRun,
}: {
  newestCreatedRunId: string | null;
  onAddRun: () => void;
}) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const query = searchParams.get("q")?.trim() ?? "";
  const focusRunId = searchParams.get("focus");
  const requestedStatus = searchParams.get("status");
  const status = isRunStatus(requestedStatus) ? requestedStatus : "ALL";
  const parsedPage = Number.parseInt(searchParams.get("page") ?? "1", 10);
  const requestedPage = Number.isFinite(parsedPage) && parsedPage > 0 ? parsedPage : 1;
  const [runToDelete, setRunToDelete] = useState<AnalysisRun | null>(null);
  const [searchDraft, setSearchDraft] = useState({ query, value: query });
  const [focusAnimationRunId, setFocusAnimationRunId] = useState<string | null>(null);
  if (searchDraft.query !== query) setSearchDraft({ query, value: query });
  const searchText = searchDraft.value;
  const runsQuery = useRecentAnalysisRuns();
  const deleteMutation = useDeleteSourceDocument();
  const runs = runsQuery.data?.items ?? EMPTY_RUNS;
  const filteredRuns = useMemo(() => runs.filter((run) => {
    const matchesName = !query || run.filename.toLocaleLowerCase().includes(query.toLocaleLowerCase());
    return matchesName && (status === "ALL" || run.status === status);
  }), [runs, query, status]);
  const pageCount = Math.max(1, Math.ceil(filteredRuns.length / PAGE_SIZE));
  const currentPage = Math.min(requestedPage, pageCount);
  const pageRuns = filteredRuns.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE);
  const listError = runsQuery.error instanceof Error
    ? runsQuery.error.message
    : runsQuery.error
      ? "Could not load saved Analysis Runs."
      : null;

  useEffect(() => {
    if (!runsQuery.isPending && requestedPage !== currentPage) {
      updateSearchParams(searchParams, router, pathname, { page: currentPage > 1 ? String(currentPage) : null });
    }
  }, [currentPage, pathname, requestedPage, router, runsQuery.isPending, searchParams]);

  useEffect(() => {
    if (!focusRunId || runsQuery.isPending) return;

    const focusedRow = document.getElementById(`analysis-run-${focusRunId}`);
    if (!focusedRow) return;

    setFocusAnimationRunId(focusRunId);
    focusedRow.scrollIntoView({
      behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth",
      block: "center",
    });
  }, [focusRunId, runsQuery.isPending]);

  useEffect(() => {
    if (!focusAnimationRunId) return;

    const timeoutId = window.setTimeout(() => setFocusAnimationRunId(null), 1400);
    return () => window.clearTimeout(timeoutId);
  }, [focusAnimationRunId]);

  useEffect(() => {
    const nextQuery = searchText.trim();
    if (!nextQuery || nextQuery === query) return;

    const timeoutId = window.setTimeout(() => {
      updateSearchParams(searchParams, router, pathname, {
        q: nextQuery || null,
        page: null,
      });
    }, 300);

    return () => window.clearTimeout(timeoutId);
  }, [pathname, query, router, searchParams, searchText]);

  function changeStatus(nextStatus: string) {
    updateSearchParams(searchParams, router, pathname, {
      status: nextStatus === "ALL" ? null : nextStatus,
      page: null,
    });
  }

  function changeSearchText(value: string) {
    setSearchDraft({ query, value });

    if (!value.trim()) {
      updateSearchParams(searchParams, router, pathname, {
        q: null,
        page: null,
      });
    }
  }

  function changePage(page: number) {
    updateSearchParams(searchParams, router, pathname, {
      page: page > 1 ? String(page) : null,
    });
  }

  function confirmDeletion() {
    if (!runToDelete || deleteMutation.isPending) return;
    deleteMutation.mutate(runToDelete.documentId, {
      onSuccess: () => setRunToDelete(null),
    });
  }

  const siblingCount = runToDelete
    ? runs.filter((run) => run.documentId === runToDelete.documentId).length
    : 0;

  return (
    <section className="analysis-runs-page space-y-6" aria-labelledby="runs-heading">
      <WorkspaceBreadcrumb items={[{ label: "Workspace", href: "/" }, { label: "Analysis Runs" }]} />
      <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div className="space-y-2">
          <p className="font-mono text-xs tracking-[0.13em] text-muted-foreground uppercase">Your research workspace</p>
          <h1 id="runs-heading" className="font-serif text-3xl font-semibold tracking-tight sm:text-4xl">Analysis Runs</h1>
          <p className="max-w-2xl text-sm leading-relaxed text-muted-foreground">
            Trace each paper from citation parsing and reference matching through claim-level evidence review, with saved results and run provenance in one place.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <Button type="button" className="min-h-11" onClick={onAddRun}>
            <Plus aria-hidden="true" /> New Analysis Run
          </Button>
        </div>
      </div>

      <div className="space-y-4 rounded-xl border border-border bg-card p-4 shadow-sm sm:p-5">
        <div className="grid gap-3 sm:mx-auto sm:w-full sm:max-w-[36rem] sm:grid-cols-[minmax(0,1fr)_12rem_auto]">
          <div className="grid min-w-0 gap-1.5">
            <label htmlFor="analysis-run-filename-search" className="text-xs font-medium text-muted-foreground">Filename</label>
            <div className="relative min-w-0" role="search" aria-label="Search Analysis Runs by filename">
              <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
              <Input
                id="analysis-run-filename-search"
                type="search"
                value={searchText}
                onChange={(event) => changeSearchText(event.currentTarget.value)}
                placeholder="Search filenames"
                aria-describedby="filename-search-hint"
                className="h-11 pl-9"
              />
              <span id="filename-search-hint" className="sr-only">Results update as you type.</span>
            </div>
          </div>
          <div className="grid grid-cols-[minmax(0,1fr)_auto] items-end gap-3 sm:contents">
            <label className="grid gap-1.5 text-xs font-medium text-muted-foreground">
              Status
              <NativeSelect value={status} onChange={(event) => changeStatus(event.currentTarget.value)} className="w-full [&_select]:h-11">
                <NativeSelectOption value="ALL">All statuses</NativeSelectOption>
                {STATUS_FILTERS.map(([value, label]) => <NativeSelectOption key={value} value={value}>{label}</NativeSelectOption>)}
              </NativeSelect>
            </label>
            <Button
              type="button"
              variant="outline"
              size="icon-lg"
              aria-label="Refresh Analysis Runs"
              title="Refresh Analysis Runs"
              aria-busy={runsQuery.isFetching}
              disabled={runsQuery.isFetching}
              onClick={() => { void runsQuery.refetch(); }}
              className="h-11 w-11 self-end"
            >
              <RefreshCw className={cn(runsQuery.isFetching && "animate-spin motion-reduce:animate-none")} aria-hidden="true" />
            </Button>
          </div>
        </div>

        {listError && (
          <Alert variant="destructive">
            <AlertTitle>Could not load Analysis Runs</AlertTitle>
            <AlertDescription>{listError}</AlertDescription>
          </Alert>
        )}

        <div className="overflow-x-auto rounded-lg border border-border">
          <table className="w-full min-w-[42rem] border-collapse text-left text-sm">
            <caption className="sr-only">Analysis Runs, newest first</caption>
            <thead className="bg-muted/50 text-xs text-muted-foreground">
              <tr>
                <th scope="col" className="px-4 py-3 font-medium">Source Document</th>
                <th scope="col" className="w-52 px-4 py-3 font-medium">Created</th>
                <th scope="col" className="w-48 px-4 py-3 font-medium">Status</th>
                <th scope="col" className="w-28 px-4 py-3 text-right font-medium">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {runsQuery.isPending ? (
                Array.from({ length: 5 }, (_, index) => (
                  <tr key={index}>
                    <td className="px-4 py-4"><Skeleton className="h-4 w-52" /></td>
                    <td className="px-4 py-4"><Skeleton className="h-4 w-32" /></td>
                    <td className="px-4 py-4"><Skeleton className="h-6 w-28 rounded-full" /></td>
                    <td className="px-4 py-4" />
                  </tr>
                ))
              ) : pageRuns.length === 0 ? (
                <tr>
                  <td colSpan={4} className="px-4 py-14 text-center">
                    <FileText className="mx-auto size-7 text-muted-foreground" aria-hidden="true" />
                    <p className="mt-3 font-medium">{query || status !== "ALL" ? "No runs match these filters." : "No Analysis Runs yet."}</p>
                    <p className="mt-1 text-sm text-muted-foreground">
                      {query || status !== "ALL" ? "Try another filename or status." : "Start with a PDF to see its progress here."}
                    </p>
                  </td>
                </tr>
              ) : pageRuns.map((run) => (
                <tr
                  key={run.id}
                  id={`analysis-run-${run.id}`}
                  data-new-run={run.id === newestCreatedRunId ? "true" : undefined}
                  className={cn(
                    "analysis-run-table-row transition-colors hover:bg-muted/20",
                    run.id === newestCreatedRunId && "analysis-run-arrival",
                    run.id === focusAnimationRunId && "analysis-run-return-focus",
                  )}
                >
                  <th scope="row" className="max-w-[28rem] px-4 py-4 font-medium">
                    <span className="block break-words">{run.filename}</span>
                  </th>
                  <td className="px-4 py-4 text-sm text-muted-foreground">
                    <time dateTime={run.createdAt}>{formatCreatedAt(run.createdAt)}</time>
                  </td>
                  <td className="px-4 py-4">
                    <Badge variant="outline" className={cn("capitalize", STATUS_CLASS_NAMES[run.status])}>
                      {formatStatus(run.status)}
                    </Badge>
                  </td>
                  <td className="px-3 py-3">
                    <div className="flex justify-end gap-1">
                      <PageTransitionLink
                        direction="forward"
                        href={getListHref(run.id, query, status, currentPage)}
                        aria-label={`Open Analysis Run for ${run.filename}`}
                        title="Open Analysis Run"
                        className={buttonVariants({ variant: "ghost", size: "icon-lg" })}
                      >
                        <ArrowUpRight aria-hidden="true" />
                      </PageTransitionLink>
                      <Button
                        type="button"
                        variant="ghost"
                        size="icon-lg"
                        aria-label={`Delete Source Document for ${run.filename}`}
                        title="Delete Source Document and all its runs"
                        onClick={() => {
                          deleteMutation.reset();
                          setRunToDelete(run);
                        }}
                      >
                        <Trash2 aria-hidden="true" className="text-destructive" />
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <nav className="flex items-center justify-between gap-3" aria-label="Analysis Run pages">
          <span className="font-mono text-xs text-muted-foreground" aria-live="polite">Page {currentPage} of {pageCount}</span>
          <div className="flex gap-2">
            <Button type="button" variant="outline" className="min-h-11" disabled={currentPage <= 1} onClick={() => changePage(currentPage - 1)}>
              <ArrowLeft aria-hidden="true" /> Previous
            </Button>
            <Button type="button" variant="outline" className="min-h-11" disabled={currentPage >= pageCount} onClick={() => changePage(currentPage + 1)}>
              Next <ArrowRight aria-hidden="true" />
            </Button>
          </div>
        </nav>
      </div>

      <Dialog open={Boolean(runToDelete)} onOpenChange={(open) => { if (!open && !deleteMutation.isPending) setRunToDelete(null); }}>
        <DialogContent className="max-h-[90dvh] max-w-[calc(100%-2rem)] overflow-y-auto sm:max-w-[calc(100%-2rem)] lg:max-w-xl">
          <DialogHeader>
            <DialogTitle>Delete this Source Document?</DialogTitle>
            <DialogDescription>
              {runToDelete?.filename} and every Analysis Run created from it will be permanently removed from this local installation.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3 text-sm leading-relaxed text-muted-foreground">
            <p>
              This also removes parsed sections, Citation Contexts, Atomic Claims, bibliography and resolution results, acquired and indexed Cited Paper data, Evidence Coverage Reports, Human Reviews, and provider consent/configuration snapshots.
              {siblingCount > 1 ? ` ${siblingCount} runs for this Source Document are currently listed.` : ""}
            </p>
            <p>Content already sent to external providers cannot be retracted by Paper T-Rail.</p>
            {deleteMutation.error && (
              <Alert variant="destructive">
                <AlertTitle>Deletion failed</AlertTitle>
                <AlertDescription>{deleteMutation.error instanceof Error ? deleteMutation.error.message : "The Source Document could not be deleted."}</AlertDescription>
              </Alert>
            )}
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" className="min-h-11" disabled={deleteMutation.isPending} onClick={() => setRunToDelete(null)}>Cancel</Button>
            <Button type="button" variant="destructive" className="min-h-11" disabled={deleteMutation.isPending} onClick={confirmDeletion}>
              {deleteMutation.isPending ? <Spinner aria-hidden="true" /> : <Trash2 aria-hidden="true" />}
              Permanently delete
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  );
}
