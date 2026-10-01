"use client";

import { useCallback } from "react";
import { useSearchParams } from "next/navigation";
import type { PipelineStageId } from "@/features/analysis-runs/pipeline";

const FILTER_QUERY_PARAMS_BY_STAGE: Record<PipelineStageId, readonly string[]> = {
  source: ["sourceFilter"],
  references: ["referencesFilter"],
  access: ["accessFilter"],
  evidence: ["evidenceFilter"],
  verification: ["verificationFilter", "verificationSummaryFilter", "verificationResultsFilter"],
};

function replaceCurrentUrl(params: URLSearchParams) {
  const query = params.toString();
  const nextUrl = `${window.location.pathname}${query ? `?${query}` : ""}${window.location.hash}`;
  window.history.replaceState(null, "", nextUrl);
}

function parseSelectedValues(value: string | null, optionIds: readonly string[]): Set<string> {
  if (value === null) return new Set();

  const requestedValues = new Set(value.split(",").filter(Boolean));
  const selectedValues = new Set(optionIds.filter((optionId) => requestedValues.has(optionId)));
  return selectedValues.size === optionIds.length ? new Set() : selectedValues;
}

export function usePipelineResultFilter(
  stageId: PipelineStageId,
  optionIds: readonly string[],
  queryParamName = `${stageId}Filter`,
) {
  const searchParams = useSearchParams();
  const selectedValues = parseSelectedValues(searchParams.get(queryParamName), optionIds);

  const toggleValue = useCallback((value: string) => {
    if (!optionIds.includes(value)) return;

    const params = new URLSearchParams(window.location.search);
    const nextValues = parseSelectedValues(params.get(queryParamName), optionIds);
    if (nextValues.has(value)) nextValues.delete(value);
    else nextValues.add(value);

    if (nextValues.size === 0 || nextValues.size === optionIds.length) params.delete(queryParamName);
    else params.set(queryParamName, optionIds.filter((optionId) => nextValues.has(optionId)).join(","));

    replaceCurrentUrl(params);
  }, [optionIds, queryParamName]);

  const reset = useCallback(() => {
    const params = new URLSearchParams(window.location.search);
    params.delete(queryParamName);
    replaceCurrentUrl(params);
  }, [queryParamName]);

  return { selectedValues, toggleValue, reset };
}

export function usePipelineStageFilterReset(stageId: PipelineStageId) {
  const searchParams = useSearchParams();
  const queryParamNames = FILTER_QUERY_PARAMS_BY_STAGE[stageId];
  const hasActiveFilters = queryParamNames.some((queryParamName) => searchParams.has(queryParamName));

  const reset = useCallback(() => {
    const params = new URLSearchParams(window.location.search);
    queryParamNames.forEach((queryParamName) => params.delete(queryParamName));
    replaceCurrentUrl(params);
  }, [queryParamNames]);

  return { hasActiveFilters, reset };
}
