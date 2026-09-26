"use client";

import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import {
  availableProviderSelections,
  consentRequirements,
  createRunConfiguration,
  DEFAULT_PROVIDER_SELECTIONS,
  isRunConfigurationReady,
  retainRequiredApprovals,
  selectableProviderOptions,
  type ProviderDirectory,
  type ProviderRole,
  type ProviderSelections,
} from "./provider-configuration.ts";
import { useProviderDirectory } from "./provider-directory-query.ts";
import type { AnalysisRunConfiguration } from "../analysis-runs/types.ts";

type ProviderConfigurationDraft = {
  selections: ProviderSelections;
  approvedCategories: Record<string, string[]>;
};

type ProviderConfigurationContextValue = {
  directory: ProviderDirectory | null;
  directoryLoading: boolean;
  directoryError: string | null;
  selections: ProviderSelections;
  consentRequirements: ReturnType<typeof consentRequirements>;
  approvedCategories: Record<string, string[]>;
  configurationReady: boolean;
  selectProvider: (role: ProviderRole, providerId: string) => void;
  approveCategory: (providerId: string, category: string, approved: boolean) => void;
  createConfiguration: () => AnalysisRunConfiguration;
};

const ProviderConfigurationContext = createContext<ProviderConfigurationContextValue | null>(null);

export function ProviderConfigurationProvider({ children }: { children: ReactNode }) {
  const providerQuery = useProviderDirectory();
  const directory = providerQuery.data ?? null;
  const [draft, setDraft] = useState<ProviderConfigurationDraft>(() => ({
    selections: { ...DEFAULT_PROVIDER_SELECTIONS },
    approvedCategories: {},
  }));
  const selections = useMemo(
    () => directory ? availableProviderSelections(directory, draft.selections) : draft.selections,
    [directory, draft.selections],
  );
  const requirements = useMemo(
    () => directory ? consentRequirements(directory, selections) : [],
    [directory, selections],
  );
  const configurationReady = isRunConfigurationReady(directory, selections, draft.approvedCategories);

  const selectProvider = useCallback((role: ProviderRole, providerId: string) => {
    if (!directory || !selectableProviderOptions(directory, role).some((provider) => provider.providerId === providerId)) return;

    setDraft((current) => {
      const currentSelections = availableProviderSelections(directory, current.selections);
      const nextSelections = {
        ...currentSelections,
        claimExtractorProvider: role === "claimExtractor" ? providerId : currentSelections.claimExtractorProvider,
        embeddingProvider: role === "embedding" ? providerId : currentSelections.embeddingProvider,
        systemOneProvider: role === "systemOne" ? providerId : currentSelections.systemOneProvider,
        scholarlyMetadataProvider: role === "scholarlyMetadata" ? providerId : currentSelections.scholarlyMetadataProvider,
      };
      const nextRequirements = consentRequirements(directory, nextSelections);
      return {
        selections: nextSelections,
        approvedCategories: retainRequiredApprovals(nextRequirements, current.approvedCategories),
      };
    });
  }, [directory]);

  const approveCategory = useCallback((providerId: string, category: string, approved: boolean) => {
    if (!requirements.some((provider) => provider.providerId === providerId && provider.dataCategories.includes(category))) return;

    setDraft((current) => {
      const existing = new Set(current.approvedCategories[providerId] ?? []);
      if (approved) existing.add(category);
      else existing.delete(category);
      const nextApprovals = { ...current.approvedCategories };
      if (existing.size > 0) nextApprovals[providerId] = [...existing];
      else delete nextApprovals[providerId];
      return { ...current, approvedCategories: nextApprovals };
    });
  }, [requirements]);

  const createConfiguration = useCallback((): AnalysisRunConfiguration => {
    if (!directory) throw new Error("Available providers have not loaded yet.");

    const configuration = createRunConfiguration(directory, selections, draft.approvedCategories);
    setDraft((current) => ({ ...current, approvedCategories: {} }));
    return configuration;
  }, [directory, selections, draft.approvedCategories]);

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
    approvedCategories: draft.approvedCategories,
    configurationReady,
    selectProvider,
    approveCategory,
    createConfiguration,
  }), [directory, providerQuery.isPending, providerQuery.error, selections, requirements, draft.approvedCategories, configurationReady, selectProvider, approveCategory, createConfiguration]);

  return <ProviderConfigurationContext.Provider value={value}>{children}</ProviderConfigurationContext.Provider>;
}

export function useProviderConfiguration() {
  const value = useContext(ProviderConfigurationContext);
  if (!value) throw new Error("ProviderConfigurationProvider is missing.");
  return value;
}
