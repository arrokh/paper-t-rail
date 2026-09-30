"use client";

import { useCallback } from "react";
import { useSearchParams } from "next/navigation";
import type { PipelineStageId } from "@/features/analysis-runs/pipeline";

function parseSelectedValues(value: string | null, optionIds: readonly string[]): Set<string> {
  if (value === null) return new Set();

  const requestedValues = new Set(value.split(",").filter(Boolean));
  const selectedValues = new Set(optionIds.filter((optionId) => requestedValues.has(optionId)));
  return selectedValues.size === optionIds.length ? new Set() : selectedValues;
}

export function usePipelineResultFilter(stageId: PipelineStageId, optionIds: readonly string[]) {
  const searchParams = useSearchParams();
  const queryParamName = `${stageId}Filter`;
  const selectedValues = parseSelectedValues(searchParams.get(queryParamName), optionIds);

  const replaceCurrentUrl = useCallback((params: URLSearchParams) => {
    const query = params.toString();
    const nextUrl = `${window.location.pathname}${query ? `?${query}` : ""}${window.location.hash}`;
    window.history.replaceState(null, "", nextUrl);
  }, []);

  const toggleValue = useCallback((value: string) => {
    if (!optionIds.includes(value)) return;

    const params = new URLSearchParams(window.location.search);
    const nextValues = parseSelectedValues(params.get(queryParamName), optionIds);
    if (nextValues.has(value)) nextValues.delete(value);
    else nextValues.add(value);

    if (nextValues.size === 0 || nextValues.size === optionIds.length) params.delete(queryParamName);
    else params.set(queryParamName, optionIds.filter((optionId) => nextValues.has(optionId)).join(","));

    replaceCurrentUrl(params);
  }, [optionIds, queryParamName, replaceCurrentUrl]);

  const reset = useCallback(() => {
    const params = new URLSearchParams(window.location.search);
    params.delete(queryParamName);
    replaceCurrentUrl(params);
  }, [queryParamName, replaceCurrentUrl]);

  return { selectedValues, toggleValue, reset };
}
