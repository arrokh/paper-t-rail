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
  explicitSelections: Partial<Record<ProviderRole, string>>;
  approvedCategories: Record<string, string[]>;
  approvedDisclosureFingerprints: Record<string, string>;
};

type ProviderConfigurationContextValue = {
  directory: ProviderDirectory | null;
  directoryLoading: boolean;
  directoryError: string | null;
  selections: ProviderSelections;
  consentRequirements: ReturnType<typeof consentRequirements>;
  approvedCategories: Record<string, string[]>;
  approvedDisclosureFingerprints: Record<string, string>;
  configurationReady: boolean;
  selectProvider: (role: ProviderRole, providerId: string) => void;
  approveCategory: (providerId: string, category: string, approved: boolean) => void;
  resetApprovals: () => void;
  createConfiguration: () => AnalysisRunConfiguration;
};

const ProviderConfigurationContext = createContext<ProviderConfigurationContextValue | null>(null);

export function ProviderConfigurationProvider({ children }: { children: ReactNode }) {
  const providerQuery = useProviderDirectory();
  const directory = providerQuery.data ?? null;
  const [draft, setDraft] = useState<ProviderConfigurationDraft>(() => ({
    selections: { ...DEFAULT_PROVIDER_SELECTIONS },
    explicitSelections: {},
    approvedCategories: {},
    approvedDisclosureFingerprints: {},
  }));
  const selections = useMemo(
    () => directory ? availableProviderSelections(directory, draft.selections, draft.explicitSelections) : draft.selections,
    [directory, draft.selections, draft.explicitSelections],
  );
  const requirements = useMemo(
    () => directory ? consentRequirements(directory, selections) : [],
    [directory, selections],
  );
  const configurationReady = isRunConfigurationReady(
    directory,
    selections,
    draft.approvedCategories,
    draft.approvedDisclosureFingerprints,
  );

  const selectProvider = useCallback((role: ProviderRole, providerId: string) => {
    if (!directory || !selectableProviderOptions(directory, role).some((provider) => provider.providerId === providerId)) return;

    setDraft((current) => {
      const currentSelections = availableProviderSelections(directory, current.selections, current.explicitSelections);
      const nextSelections = {
        ...currentSelections,
        claimExtractorProvider: role === "claimExtractor" ? providerId : currentSelections.claimExtractorProvider,
        embeddingProvider: role === "embedding" ? providerId : currentSelections.embeddingProvider,
        systemOneProvider: role === "systemOne" ? providerId : currentSelections.systemOneProvider,
        scholarlyMetadataProvider: role === "scholarlyMetadata" ? providerId : currentSelections.scholarlyMetadataProvider,
        openAccessProvider: role === "openAccess" ? providerId : currentSelections.openAccessProvider,
      };
      const nextExplicitSelections = { ...current.explicitSelections, [role]: providerId };
      const nextRequirements = consentRequirements(directory, nextSelections);
      const retainedApprovals = retainRequiredApprovals(
        nextRequirements,
        current.approvedCategories,
        current.approvedDisclosureFingerprints,
      );
      const retainedProviderIds = new Set(Object.keys(retainedApprovals));
      return {
        selections: nextSelections,
        explicitSelections: nextExplicitSelections,
        approvedCategories: retainedApprovals,
        approvedDisclosureFingerprints: Object.fromEntries(
          Object.entries(current.approvedDisclosureFingerprints).filter(([providerId]) => retainedProviderIds.has(providerId)),
        ),
      };
    });
  }, [directory]);

  const approveCategory = useCallback((providerId: string, category: string, approved: boolean) => {
    const requirement = requirements.find((provider) =>
      provider.providerId === providerId && provider.dataCategories.includes(category),
    );
    if (!requirement) return;

    setDraft((current) => {
      const sameDisclosure = current.approvedDisclosureFingerprints[providerId] === requirement.retentionDisclosureFingerprint;
      const existing = new Set(sameDisclosure ? current.approvedCategories[providerId] ?? [] : []);
      if (approved) existing.add(category);
      else existing.delete(category);
      const nextApprovals = { ...current.approvedCategories };
      const nextFingerprints = { ...current.approvedDisclosureFingerprints };
      if (existing.size > 0) {
        nextApprovals[providerId] = [...existing];
        nextFingerprints[providerId] = requirement.retentionDisclosureFingerprint;
      } else {
        delete nextApprovals[providerId];
        delete nextFingerprints[providerId];
      }
      return {
        ...current,
        approvedCategories: nextApprovals,
        approvedDisclosureFingerprints: nextFingerprints,
      };
    });
  }, [requirements]);

  const resetApprovals = useCallback(() => {
    setDraft((current) => ({ ...current, approvedCategories: {}, approvedDisclosureFingerprints: {} }));
  }, []);

  const createConfiguration = useCallback((): AnalysisRunConfiguration => {
    if (!directory) throw new Error("Available providers have not loaded yet.");

    const configuration = createRunConfiguration(
      directory,
      selections,
      draft.approvedCategories,
      draft.approvedDisclosureFingerprints,
    );
    setDraft((current) => ({ ...current, approvedCategories: {}, approvedDisclosureFingerprints: {} }));
    return configuration;
  }, [directory, selections, draft.approvedCategories, draft.approvedDisclosureFingerprints]);

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
    approvedDisclosureFingerprints: draft.approvedDisclosureFingerprints,
    configurationReady,
    selectProvider,
    approveCategory,
    resetApprovals,
    createConfiguration,
  }), [directory, providerQuery.isPending, providerQuery.error, selections, requirements, draft.approvedCategories, draft.approvedDisclosureFingerprints, configurationReady, selectProvider, approveCategory, resetApprovals, createConfiguration]);

  return <ProviderConfigurationContext.Provider value={value}>{children}</ProviderConfigurationContext.Provider>;
}

export function useProviderConfiguration() {
  const value = useContext(ProviderConfigurationContext);
  if (!value) throw new Error("ProviderConfigurationProvider is missing.");
  return value;
}
