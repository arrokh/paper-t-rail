import { Suspense } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { AnalysisRunTableRowsSkeleton } from "@/features/analysis-runs/components/analysis-run-loading";
import { UploadDashboard } from "@/features/workspace/components/upload-dashboard";
import { WorkspaceBreadcrumb } from "@/features/workspace/components/workspace-breadcrumb";
import { BackToTopFab } from "@/features/workspace/components/back-to-top-fab";
import { WorkspaceShell } from "@/features/workspace/components/workspace-shell";

function AnalysisRunsFallback() {
  return (
    <div className="analysis-runs-page space-y-6" aria-busy="true">
      <WorkspaceBreadcrumb items={[{ label: "Workspace", href: "/" }, { label: "Analysis Runs" }]} />
      <header className="space-y-2">
        <p className="font-mono text-xs tracking-[0.13em] text-muted-foreground uppercase">Your research workspace</p>
        <h1 className="font-serif text-3xl font-semibold tracking-tight sm:text-4xl">Analysis Runs</h1>
        <p className="max-w-2xl text-sm leading-relaxed text-muted-foreground">
          Trace each paper from citation parsing and reference matching through claim-level evidence review, with saved results and run provenance in one place.
        </p>
      </header>

      <section className="space-y-4 rounded-xl border border-border bg-card p-4 shadow-sm sm:p-5" aria-label="Loading Analysis Runs">
        <span role="status" className="sr-only">Loading Analysis Runs</span>
        <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between" aria-hidden="true">
          <div className="grid gap-3 sm:w-full sm:max-w-[36rem] sm:grid-cols-[minmax(0,1fr)_12rem_auto]">
            <div className="grid gap-1.5">
              <span className="text-xs font-medium text-muted-foreground">Filename</span>
              <Skeleton className="h-11 w-full" />
            </div>
            <div className="grid grid-cols-[minmax(0,1fr)_auto] items-end gap-3 sm:contents">
              <div className="grid gap-1.5">
                <span className="text-xs font-medium text-muted-foreground">Status</span>
                <Skeleton className="h-11 w-full" />
              </div>
              <Skeleton className="h-11 w-11 self-end" />
            </div>
          </div>
          <Skeleton className="h-11 w-full sm:w-44" />
        </div>

        <div className="overflow-x-auto rounded-lg border border-border" aria-hidden="true">
          <table className="w-full min-w-[42rem] border-collapse text-left text-sm">
            <thead className="bg-muted/50 text-xs text-muted-foreground">
              <tr>
                <th scope="col" className="px-4 py-3 font-medium">Source Document</th>
                <th scope="col" className="w-52 px-4 py-3 font-medium">Created</th>
                <th scope="col" className="w-48 px-4 py-3 font-medium">Status</th>
                <th scope="col" className="w-28 px-4 py-3 text-right font-medium">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              <AnalysisRunTableRowsSkeleton />
            </tbody>
          </table>
        </div>
        <div className="flex items-center justify-between gap-3" aria-hidden="true">
          <Skeleton className="h-4 w-28" />
          <Skeleton className="h-11 w-24" />
        </div>
      </section>
    </div>
  );
}

export default function Home() {
  return (
    <WorkspaceShell>
      <Suspense fallback={<AnalysisRunsFallback />}>
        <UploadDashboard />
      </Suspense>
      <BackToTopFab />
    </WorkspaceShell>
  );
}
