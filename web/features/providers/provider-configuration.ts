import type { AnalysisRunConfiguration } from "../analysis-runs/types.ts";

export type ProviderRole = "claimExtractor" | "embedding" | "systemOne" | "scholarlyMetadata";

export type ProviderSelections = {
  claimExtractorProvider: string;
  embeddingProvider: string;
  systemOneProvider: string;
  scholarlyMetadataProvider: string;
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

export function availableProviderSelections(
  directory: ProviderDirectory,
  selections: ProviderSelections,
): ProviderSelections {
  const selectAvailable = (role: ProviderRole, current: string) =>
    directory.providers[role]?.find((provider) => provider.providerId === current)?.providerId
      ?? directory.providers[role]?.[0]?.providerId
      ?? current;

  return {
    claimExtractorProvider: selectAvailable("claimExtractor", selections.claimExtractorProvider),
    embeddingProvider: selectAvailable("embedding", selections.embeddingProvider),
    systemOneProvider: selectAvailable("systemOne", selections.systemOneProvider),
    scholarlyMetadataProvider: selectAvailable("scholarlyMetadata", selections.scholarlyMetadataProvider),
  };
}

export function consentRequirements(
  directory: ProviderDirectory,
  selections: ProviderSelections,
): ProviderConsentRequirement[] {
  const selectionByRole: Record<ProviderRole, string> = {
    claimExtractor: selections.claimExtractorProvider,
    embedding: selections.embeddingProvider,
    systemOne: selections.systemOneProvider,
    scholarlyMetadata: selections.scholarlyMetadataProvider,
  };
  const grouped = new Map<string, ProviderConsentRequirement>();

  for (const role of ["claimExtractor", "embedding", "systemOne", "scholarlyMetadata"] as const) {
    for (const option of directory.providers[role] ?? []) {
      if (option.providerId !== selectionByRole[role] || option.trustBoundary !== "EXTERNAL") continue;
      const existing = grouped.get(option.providerId);
      if (existing) {
        existing.dataCategories = [...new Set([...existing.dataCategories, ...option.dataCategories])].sort();
      } else {
        grouped.set(option.providerId, {
          providerId: option.providerId,
          displayName: option.displayName,
          dataCategories: [...new Set(option.dataCategories)].sort(),
          retentionDisclosure: option.retentionDisclosure,
        });
      }
    }
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

export function createRunConfiguration(
  selections: ProviderSelections,
  requirements: ProviderConsentRequirement[],
  approvedCategories: Record<string, string[]>,
): AnalysisRunConfiguration {
  return {
    ...selections,
    externalProviderConsents: requirements.map((provider) => ({
      providerId: provider.providerId,
      dataCategories: provider.dataCategories.filter((category) => approvedCategories[provider.providerId]?.includes(category)),
    })),
  };
}
