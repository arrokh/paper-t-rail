import { queryOptions, useQuery } from "@tanstack/react-query";
import { readApiError } from "../../lib/read-api-error.ts";
import type { ProviderDirectory, ProviderRole } from "./provider-configuration.ts";

export const PROVIDER_DIRECTORY_QUERY_KEY = ["providers", "directory"] as const;

const PROVIDER_ROLES: ProviderRole[] = ["claimExtractor", "embedding", "systemOne", "scholarlyMetadata"];

async function fetchProviderDirectory(signal: AbortSignal): Promise<ProviderDirectory> {
  const response = await fetch("/api/v1/providers", { cache: "no-store", signal });
  if (!response.ok) throw new Error(await readApiError(response));

  const directory = (await response.json()) as ProviderDirectory;
  if (PROVIDER_ROLES.some((role) => !directory.providers[role]?.length)) {
    throw new Error("The API has no enabled provider for one or more Analysis Run stages.");
  }

  return directory;
}

export function providerDirectoryQueryOptions() {
  return queryOptions({
    queryKey: PROVIDER_DIRECTORY_QUERY_KEY,
    queryFn: ({ signal }) => fetchProviderDirectory(signal),
    retry: false,
  });
}

export function useProviderDirectory() {
  return useQuery(providerDirectoryQueryOptions());
}
