import { Suspense } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { AnalysisRunDetailPage } from "@/features/analysis-runs/components/analysis-run-detail-page";
import { WorkspaceBreadcrumb } from "@/features/workspace/components/workspace-breadcrumb";
import { WorkspaceShell } from "@/features/workspace/components/workspace-shell";

function AnalysisRunDetailFallback() {
  return (
    <div className="space-y-6">
      <WorkspaceBreadcrumb items={[
        { label: "Back to Analysis Runs", href: "/", backButton: true },
        { label: "Workspace", href: "/" },
        { label: "Analysis Runs", href: "/" },
        { label: "Analysis Run details" },
      ]} />
      <div role="status" aria-label="Loading Analysis Run details">
        <span className="sr-only">Loading Analysis Run details</span>
        <Skeleton className="h-32 w-full" />
        <Skeleton className="h-52 w-full" />
        <Skeleton className="h-72 w-full" />
      </div>
    </div>
  );
}

export default async function AnalysisRunRoute({ params }: PageProps<"/analysis-runs/[runId]">) {
  const { runId } = await params;
  return (
    <WorkspaceShell>
      <Suspense fallback={<AnalysisRunDetailFallback />}>
        <AnalysisRunDetailPage analysisRunId={runId} />
      </Suspense>
    </WorkspaceShell>
  );
}
