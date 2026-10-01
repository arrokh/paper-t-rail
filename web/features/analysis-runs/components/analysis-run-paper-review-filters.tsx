"use client";

import { PipelineResultMetricFilters, type PipelineResultFilterOption } from "@/features/analysis-runs/components/pipeline-result-metric-filters";

export const PAPER_REVIEW_FILTER_OPTIONS: readonly PipelineResultFilterOption[] = [
  { id: "SUPPORTED", label: "Supported", description: "The recorded aggregation found evidence that directly supports the claim within the assessed scope.", value: 0 },
  { id: "PARTIALLY_SUPPORTED", label: "Partially supported", description: "Some evidence supports the claim, but coverage or scope is incomplete.", value: 0 },
  { id: "CONTRADICTED", label: "Contradicted", description: "The recorded evidence weighs against the claim within the assessed scope.", value: 0 },
  { id: "INSUFFICIENT_EVIDENCE", label: "Insufficient evidence", description: "The saved evidence did not support a conclusive outcome, including comparable conflicts.", value: 0 },
  { id: "INACCESSIBLE", label: "Inaccessible", description: "The reference could not provide accessible evidence for this assessment.", value: 0 },
  { id: "UNRESOLVED", label: "Unresolved", description: "The bibliography entry could not be confidently matched to a canonical paper.", value: 0 },
  { id: "UNSUPPORTED_REFERENCE_TYPE", label: "Unsupported reference type", description: "This reference type is not supported for claim-level evidence assessment.", value: 0 },
  { id: "PENDING", label: "Pending", description: "The worker has recorded the pair but has not finished its assessment.", value: 0 },
  { id: "INCOMPLETE", label: "Incomplete", description: "The worker recorded an incomplete pair or assessment failure.", value: 0 },
  { id: "NO_FINAL_STATUS", label: "No final status", description: "Processing completed, but no final assessment status was persisted for this pair.", value: 0 },
];

export function AnalysisRunPaperReviewFilters({
  options,
  selectedValues,
  onToggle,
  onReset,
}: {
  options: readonly PipelineResultFilterOption[];
  selectedValues: ReadonlySet<string>;
  onToggle: (id: string) => void;
  onReset: () => void;
}) {
  return (
    <PipelineResultMetricFilters
      label="Filter AI result pairs by status"
      options={options}
      selectedValues={selectedValues}
      onToggle={onToggle}
      onReset={onReset}
      className="grid-cols-2 sm:grid-cols-2 xl:grid-cols-3"
    />
  );
}
