import { Suspense } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { UploadDashboard } from "@/features/workspace/components/upload-dashboard";
import { WorkspaceBreadcrumb } from "@/features/workspace/components/workspace-breadcrumb";
import { WorkspaceShell } from "@/features/workspace/components/workspace-shell";

function AnalysisRunsFallback() {
  return (
    <div className="space-y-6">
      <WorkspaceBreadcrumb items={[{ label: "Workspace", href: "/" }, { label: "Analysis Runs" }]} />
      <div role="status" aria-label="Loading Analysis Runs">
        <span className="sr-only">Loading Analysis Runs</span>
        <Skeleton className="h-28 w-full" />
        <Skeleton className="mt-5 h-72 w-full" />
      </div>
    </div>
  );
}

export default function Home() {
  return (
    <WorkspaceShell>
      <Suspense fallback={<AnalysisRunsFallback />}>
        <UploadDashboard />
      </Suspense>
    </WorkspaceShell>
  );
}
