import { Suspense } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { AnalysisRunComparisonPage } from "@/features/analysis-runs/components/analysis-run-comparison-page";
import { WorkspaceShell } from "@/features/workspace/components/workspace-shell";

function ComparisonLoadingState() {
  return (
    <section className="space-y-5" aria-busy="true" aria-label="Loading Analysis Run comparison">
      <Skeleton className="h-10 w-2/3" />
      <Skeleton className="h-24 w-full" />
      <Skeleton className="h-40 w-full" />
    </section>
  );
}

export default function AnalysisRunComparisonRoute() {
  return (
    <WorkspaceShell>
      <Suspense fallback={<ComparisonLoadingState />}>
        <AnalysisRunComparisonPage />
      </Suspense>
    </WorkspaceShell>
  );
}
