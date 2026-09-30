import { Skeleton } from "@/components/ui/skeleton";
import { WorkspaceBreadcrumb } from "@/features/workspace/components/workspace-breadcrumb";

const SKELETON_ROW_COUNT = 5;

export function AnalysisRunTableRowsSkeleton() {
  return Array.from({ length: SKELETON_ROW_COUNT }, (_, index) => (
    <tr key={index} aria-hidden="true">
      <td className="px-4 py-4"><Skeleton className="h-4 w-52" /></td>
      <td className="px-4 py-4"><Skeleton className="h-4 w-32" /></td>
      <td className="px-4 py-4"><Skeleton className="h-6 w-28 rounded-full" /></td>
      <td className="px-3 py-3">
        <div className="flex justify-end gap-1">
          <Skeleton className="size-9 rounded-md" />
          <Skeleton className="size-9 rounded-md" />
        </div>
      </td>
    </tr>
  ));
}

function AnalysisRunPipelineSkeleton() {
  return (
    <nav className="overflow-x-auto overflow-y-hidden p-2" aria-hidden="true">
      <ol className="flex min-w-[920px] items-stretch gap-2">
        {Array.from({ length: 5 }, (_, index) => (
          <li key={index} className="flex min-w-0 flex-1 items-stretch gap-2">
            <div className="flex min-h-[14rem] min-w-0 flex-1 flex-col gap-3 rounded-xl border border-border bg-muted/20 p-3">
              <Skeleton className="h-3 w-8" />
              <Skeleton className="h-5 w-3/4" />
              <Skeleton className="h-3 w-full" />
              <Skeleton className="h-3 w-5/6" />
              <Skeleton className="mt-auto h-4 w-24" />
            </div>
            {index < 4 && <Skeleton className="h-4 w-4 shrink-0 self-center" />}
          </li>
        ))}
      </ol>
    </nav>
  );
}

export function AnalysisRunDetailLoadingState({
  backHref,
  showStageResults = false,
}: {
  backHref: string;
  showStageResults?: boolean;
}) {
  return (
    <div className="analysis-run-detail space-y-6" aria-busy="true">
      <WorkspaceBreadcrumb items={[
        { label: "Back to Analysis Runs", href: backHref, backButton: true },
        { label: "Workspace", href: "/" },
        { label: "Analysis Runs", href: "/" },
        { label: "Analysis Run details" },
      ]} />
      <div role="status" aria-label="Loading Analysis Run details" className="space-y-6">
        <span className="sr-only">Loading Analysis Run details</span>
        <header aria-hidden="true" className="space-y-5">
          <div className="space-y-3 border-b border-border/70 pb-5">
            <Skeleton className="h-3 w-36" />
            <Skeleton className="h-10 w-2/3 max-w-2xl" />
            <Skeleton className="h-4 w-52" />
          </div>
        </header>
        <Skeleton aria-hidden="true" className="h-14 w-full rounded-xl" />
        <section className="space-y-4" aria-hidden="true">
          <div className="space-y-2">
            <Skeleton className="h-6 w-40" />
            <Skeleton className="h-4 w-96 max-w-full" />
          </div>
          <AnalysisRunPipelineSkeleton />
        </section>
        {showStageResults && (
          <section aria-hidden="true" className="space-y-4 rounded-xl border border-border bg-card p-4 shadow-sm sm:p-6">
            <Skeleton className="h-28 w-full rounded-lg" />
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              {Array.from({ length: 4 }, (_, index) => <Skeleton key={index} className="h-16 w-full rounded-lg" />)}
            </div>
            <div className="space-y-3">
              {Array.from({ length: 3 }, (_, index) => <Skeleton key={index} className="h-24 w-full rounded-lg" />)}
            </div>
          </section>
        )}
      </div>
    </div>
  );
}
