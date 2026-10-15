import type { AnalysisRun } from "./types.ts";

type ReferenceResolutionConfiguration = AnalysisRun["configuration"]["referenceResolution"];

export function referenceResolutionProviderLabel(configuration: ReferenceResolutionConfiguration): string {
  const chain = configuration?.providerChain;
  if (chain) {
    const providers = chain.providers.map(({ provider }) => provider).join(" → ");
    return providers ? `${providers} (chain: ${chain.chainId})` : chain.chainId;
  }

  return configuration?.provider?.provider ?? "not configured";
}
