"use client";

import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import {
  availableProviderSelections,
  consentRequirements,
  createRunConfiguration,
  missingConsents,
  type ProviderDirectory,
  type ProviderRole,
  type ProviderSelections,
} from "./provider-configuration.ts";
import { useProviderDirectory } from "./provider-directory-query.ts";
import type { AnalysisRunConfiguration } from "../analysis-runs/types.ts";

const DEFAULT_SELECTIONS: ProviderSelections = {
  claimExtractorProvider: "heuristic",
  embeddingProvider: "local",
  systemOneProvider: "mock",
  scholarlyMetadataProvider: "recorded-fixtures",
  openAccessProvider: "recorded-fixtures",
};

type ProviderConfigurationContextValue = {
  directory: ProviderDirectory | null;
  directoryLoading: boolean;
  directoryError: string | null;
  selections: ProviderSelections;
  consentRequirements: ReturnType<typeof consentRequirements>;
  approvedCategories: Record<string, string[]>;
  selectProvider: (role: ProviderRole, providerId: string) => void;
  approveCategory: (providerId: string, category: string, approved: boolean) => void;
  resetApprovedCategories: () => void;
  createConfiguration: () => AnalysisRunConfiguration;
};

const ProviderConfigurationContext = createContext<ProviderConfigurationContextValue | null>(null);

export function ProviderConfigurationProvider({ children }: { children: ReactNode }) {
  const providerQuery = useProviderDirectory();
  const directory = providerQuery.data ?? null;
  const [selectionsDraft, setSelectionsDraft] = useState(DEFAULT_SELECTIONS);
  const [approvedCategories, setApprovedCategories] = useState<Record<string, string[]>>({});
  const selections = useMemo(
    () => directory ? availableProviderSelections(directory, selectionsDraft) : selectionsDraft,
    [directory, selectionsDraft],
  );
  const requirements = useMemo(
    () => directory ? consentRequirements(directory, selections) : [],
    [directory, selections],
  );

  const selectProvider = useCallback((role: ProviderRole, providerId: string) => {
    setSelectionsDraft((current) => ({
      ...current,
      claimExtractorProvider: role === "claimExtractor" ? providerId : current.claimExtractorProvider,
      embeddingProvider: role === "embedding" ? providerId : current.embeddingProvider,
      systemOneProvider: role === "systemOne" ? providerId : current.systemOneProvider,
      scholarlyMetadataProvider: role === "scholarlyMetadata" ? providerId : current.scholarlyMetadataProvider,
      openAccessProvider: role === "openAccess" ? providerId : current.openAccessProvider,
    }));
  }, []);

  const approveCategory = useCallback((providerId: string, category: string, approved: boolean) => {
    setApprovedCategories((current) => {
      const existing = new Set(current[providerId] ?? []);
      if (approved) existing.add(category);
      else existing.delete(category);
      return { ...current, [providerId]: [...existing] };
    });
  }, []);
  const resetApprovedCategories = useCallback(() => setApprovedCategories({}), []);

  const createConfiguration = useCallback((): AnalysisRunConfiguration => {
    if (!directory) throw new Error("Available providers have not loaded yet.");
    if (missingConsents(requirements, approvedCategories).length > 0) {
      throw new Error("Approve every disclosed data category for each selected external provider, or choose a local provider.");
    }
    return createRunConfiguration(selections, requirements, approvedCategories);
  }, [directory, requirements, approvedCategories, selections]);

  const value = useMemo<ProviderConfigurationContextValue>(() => ({
    directory,
    directoryLoading: providerQuery.isPending,
    directoryError: providerQuery.error instanceof Error
      ? providerQuery.error.message
      : providerQuery.error
        ? "Could not load available providers."
        : null,
    selections,
    consentRequirements: requirements,
    approvedCategories,
    selectProvider,
    approveCategory,
    resetApprovedCategories,
    createConfiguration,
  }), [directory, providerQuery.isPending, providerQuery.error, selections, requirements, approvedCategories, selectProvider, approveCategory, resetApprovedCategories, createConfiguration]);

  return <ProviderConfigurationContext.Provider value={value}>{children}</ProviderConfigurationContext.Provider>;
}

export function useProviderConfiguration() {
  const value = useContext(ProviderConfigurationContext);
  if (!value) throw new Error("ProviderConfigurationProvider is missing.");
  return value;
}
