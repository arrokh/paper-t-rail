import type { AnalysisRunConfiguration } from "../analysis-runs/types.ts";

export const PROVIDER_ROLES = ["claimExtractor", "embedding", "systemOne", "scholarlyMetadata", "openAccess"] as const;
export type ProviderRole = (typeof PROVIDER_ROLES)[number];

export type ProviderSelections = {
  claimExtractorProvider: string;
  embeddingProvider: string;
  systemOneProvider: string;
  scholarlyMetadataProvider: string;
  openAccessProvider: string;
};

export const DEFAULT_PROVIDER_SELECTIONS: ProviderSelections = {
  claimExtractorProvider: "openai-compatible-chat",
  embeddingProvider: "ollama",
  systemOneProvider: "laya",
  scholarlyMetadataProvider: "crossref",
  openAccessProvider: "unpaywall",
};

const SAFE_FALLBACK_PROVIDER_SELECTIONS: ProviderSelections = {
  claimExtractorProvider: "heuristic",
  embeddingProvider: "local",
  systemOneProvider: "mock",
  scholarlyMetadataProvider: "recorded-fixtures",
  openAccessProvider: "recorded-fixtures",
};

export type ProviderOption = {
  role: ProviderRole;
  providerId: string;
  displayName: string;
  version: string;
  model: string | null;
  trustBoundary: "LOCAL" | "EXTERNAL" | "UNREVIEWED";
  dataCategories: string[];
  retentionDisclosure: string | null;
};

export type DataCategoryDisclosure = {
  id: string;
  label: string;
  description: string;
};

export type ProviderDirectory = {
  providers: Partial<Record<ProviderRole, ProviderOption[]>>;
  dataCategories: DataCategoryDisclosure[];
};

export type ProviderConsentRequirement = {
  providerId: string;
  displayName: string;
  dataCategories: string[];
  retentionDisclosure: string | null;
};

const SELECTION_FIELD_BY_ROLE: Record<ProviderRole, keyof ProviderSelections> = {
  claimExtractor: "claimExtractorProvider",
  embedding: "embeddingProvider",
  systemOne: "systemOneProvider",
  scholarlyMetadata: "scholarlyMetadataProvider",
  openAccess: "openAccessProvider",
};

function isSelectableProviderOption(option: unknown, role: ProviderRole): option is ProviderOption {
  if (typeof option !== "object" || option === null) return false;

  const candidate = option as Partial<ProviderOption>;
  if (
    candidate.role !== role
    || typeof candidate.providerId !== "string"
    || !candidate.providerId
    || typeof candidate.displayName !== "string"
    || typeof candidate.version !== "string"
    || !(candidate.model === null || typeof candidate.model === "string")
    || !(candidate.trustBoundary === "LOCAL" || candidate.trustBoundary === "EXTERNAL")
    || !Array.isArray(candidate.dataCategories)
    || candidate.dataCategories.some((category) => typeof category !== "string" || !category.trim())
    || !(candidate.retentionDisclosure === null || typeof candidate.retentionDisclosure === "string")
  ) {
    return false;
  }

  if (candidate.trustBoundary === "EXTERNAL") {
    return candidate.dataCategories.length > 0 && Boolean(candidate.retentionDisclosure?.trim());
  }

  return true;
}

/** The API directory contains enabled registrations; expose only classified, complete choices to the UI. */
export function selectableProviderOptions(directory: ProviderDirectory, role: ProviderRole): ProviderOption[] {
  const options: unknown = directory?.providers?.[role];
  if (!Array.isArray(options)) return [];
  return options.filter((option) => isSelectableProviderOption(option, role));
}

export function availableProviderSelections(
  directory: ProviderDirectory,
  selections: ProviderSelections,
): ProviderSelections {
  const selectAvailable = (role: ProviderRole, field: keyof ProviderSelections) => {
    const options = selectableProviderOptions(directory, role);
    // The API directory gates availability; Laya is retained only as a local selectable provider.
    const preferred = options.find((provider) =>
      provider.providerId === selections[field]
      && (role !== "systemOne" || provider.providerId !== "laya" || provider.trustBoundary === "LOCAL"),
    );
    const safeFallback = options.find((provider) =>
      provider.providerId === SAFE_FALLBACK_PROVIDER_SELECTIONS[field],
    );
    const firstSelectableFallback = role === "systemOne" ? undefined : options[0]?.providerId;
    return preferred?.providerId
      ?? safeFallback?.providerId
      ?? firstSelectableFallback
      ?? selections[field];
  };

  return {
    claimExtractorProvider: selectAvailable("claimExtractor", "claimExtractorProvider"),
    embeddingProvider: selectAvailable("embedding", "embeddingProvider"),
    systemOneProvider: selectAvailable("systemOne", "systemOneProvider"),
    scholarlyMetadataProvider: selectAvailable("scholarlyMetadata", "scholarlyMetadataProvider"),
    openAccessProvider: selectAvailable("openAccess", "openAccessProvider"),
  };
}

function hasSelectableProviderForRole(
  directory: ProviderDirectory,
  role: ProviderRole,
  selections: ProviderSelections,
): boolean {
  const selectedId = selections[SELECTION_FIELD_BY_ROLE[role]];
  return selectableProviderOptions(directory, role).some((provider) => provider.providerId === selectedId);
}

export function providerSelectionsAreAvailable(
  directory: ProviderDirectory,
  selections: ProviderSelections,
): boolean {
  return PROVIDER_ROLES.every((role) => hasSelectableProviderForRole(directory, role, selections));
}

export function consentRequirements(
  directory: ProviderDirectory,
  selections: ProviderSelections,
): ProviderConsentRequirement[] {
  const grouped = new Map<string, ProviderConsentRequirement>();

  for (const role of PROVIDER_ROLES) {
    const selectedId = selections[SELECTION_FIELD_BY_ROLE[role]];
    const selectedProvider = selectableProviderOptions(directory, role)
      .find((provider) => provider.providerId === selectedId);
    if (selectedProvider?.trustBoundary !== "EXTERNAL") continue;

    const existing = grouped.get(selectedProvider.providerId);
    if (existing) {
      existing.dataCategories = [...new Set([...existing.dataCategories, ...selectedProvider.dataCategories])].sort();
      continue;
    }

    grouped.set(selectedProvider.providerId, {
      providerId: selectedProvider.providerId,
      displayName: selectedProvider.displayName,
      dataCategories: [...new Set(selectedProvider.dataCategories)].sort(),
      retentionDisclosure: selectedProvider.retentionDisclosure,
    });
  }

  return [...grouped.values()].sort((left, right) => left.providerId.localeCompare(right.providerId));
}

export function missingConsents(
  requirements: ProviderConsentRequirement[],
  approvedCategories: Record<string, string[]>,
): ProviderConsentRequirement[] {
  return requirements.filter((provider) =>
    provider.dataCategories.some((category) => !approvedCategories[provider.providerId]?.includes(category)),
  );
}

export function retainRequiredApprovals(
  requirements: ProviderConsentRequirement[],
  approvedCategories: Record<string, string[]>,
): Record<string, string[]> {
  return Object.fromEntries(
    requirements.flatMap(({ providerId, dataCategories }) => {
      const retained = (approvedCategories[providerId] ?? []).filter((category) => dataCategories.includes(category));
      return retained.length > 0 ? [[providerId, [...new Set(retained)]]] : [];
    }),
  );
}

export function isRunConfigurationReady(
  directory: ProviderDirectory | null,
  selections: ProviderSelections,
  approvedCategories: Record<string, string[]>,
): boolean {
  if (!directory || !providerSelectionsAreAvailable(directory, selections)) return false;
  return missingConsents(consentRequirements(directory, selections), approvedCategories).length === 0;
}

export function createRunConfiguration(
  directory: ProviderDirectory,
  selections: ProviderSelections,
  approvedCategories: Record<string, string[]>,
): AnalysisRunConfiguration {
  if (!providerSelectionsAreAvailable(directory, selections)) {
    throw new Error("Choose an enabled, classified provider for every Analysis Run stage.");
  }

  const requirements = consentRequirements(directory, selections);
  if (missingConsents(requirements, approvedCategories).length > 0) {
    throw new Error("Approve every disclosed data category for each selected external provider, or choose a local provider.");
  }

  return {
    ...selections,
    externalProviderConsents: requirements.map((provider) => ({
      providerId: provider.providerId,
      dataCategories: [...provider.dataCategories],
    })),
  };
}
