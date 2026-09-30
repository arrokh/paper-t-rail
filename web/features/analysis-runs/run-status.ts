import type { AnalysisRun } from "@/features/analysis-runs/types";

export const ANALYSIS_RUN_STATUS_CLASS_NAMES: Record<AnalysisRun["status"], string> = {
  QUEUED: "border-border bg-muted text-muted-foreground",
  PROCESSING: "border-info-foreground/30 bg-info text-info-foreground",
  PARSED: "border-info-foreground/25 bg-info/55 text-info-foreground",
  COMPLETED: "border-success-foreground/25 bg-success text-success-foreground",
  COMPLETED_WITH_WARNINGS: "border-warning-foreground/30 bg-warning text-warning-foreground",
  FAILED: "border-destructive/25 bg-destructive/10 text-destructive",
};

export function analysisRunStatusLabel(status: AnalysisRun["status"]): string {
  return status.replaceAll("_", " ").toLowerCase();
}
