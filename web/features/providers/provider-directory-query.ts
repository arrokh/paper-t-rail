import { queryOptions, useQuery } from "@tanstack/react-query";
import { readApiError } from "../../lib/read-api-error.ts";
import { PROVIDER_ROLES, selectableProviderOptions, type DataCategoryDisclosure, type ProviderDirectory } from "./provider-configuration.ts";

export const PROVIDER_DIRECTORY_QUERY_KEY = ["providers", "directory"] as const;

function isDataCategoryDisclosure(value: unknown): value is DataCategoryDisclosure {
  if (typeof value !== "object" || value === null) return false;
  const disclosure = value as Partial<DataCategoryDisclosure>;
  return typeof disclosure.id === "string"
    && typeof disclosure.label === "string"
    && typeof disclosure.description === "string";
}

async function fetchProviderDirectory(signal: AbortSignal): Promise<ProviderDirectory> {
  const response = await fetch("/api/v1/providers", { cache: "no-store", signal });
  if (!response.ok) throw new Error(await readApiError(response));

  const body: unknown = await response.json();
  if (
    typeof body !== "object"
    || body === null
    || typeof (body as { providers?: unknown }).providers !== "object"
    || (body as { providers?: unknown }).providers === null
    || !Array.isArray((body as { dataCategories?: unknown }).dataCategories)
  ) {
    throw new Error("The API returned an invalid provider directory.");
  }

  const remoteDirectory = body as ProviderDirectory;
  const providers = Object.fromEntries(
    PROVIDER_ROLES.map((role) => [role, selectableProviderOptions(remoteDirectory, role)]),
  ) as ProviderDirectory["providers"];
  if (PROVIDER_ROLES.some((role) => !providers[role]?.length)) {
    throw new Error("The API has no enabled, classified provider for one or more Analysis Run stages.");
  }

  return {
    providers,
    dataCategories: remoteDirectory.dataCategories.filter(isDataCategoryDisclosure),
  };
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
