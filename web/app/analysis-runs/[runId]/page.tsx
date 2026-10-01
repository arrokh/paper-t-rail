import { Suspense } from "react";
import { AnalysisRunDetailLoadingState } from "@/features/analysis-runs/components/analysis-run-loading";
import { AnalysisRunDetailPage } from "@/features/analysis-runs/components/analysis-run-detail-page";
import { WorkspaceShell } from "@/features/workspace/components/workspace-shell";

export default async function AnalysisRunRoute({ params }: PageProps<"/analysis-runs/[runId]">) {
  const { runId } = await params;
  return (
    <WorkspaceShell>
      <Suspense fallback={<AnalysisRunDetailLoadingState backHref={`/?focus=${encodeURIComponent(runId)}`} />}>
        <AnalysisRunDetailPage analysisRunId={runId} />
      </Suspense>
    </WorkspaceShell>
  );
}
