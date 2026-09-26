import assert from "node:assert/strict";
import test from "node:test";
import { QueryClient, QueryObserver } from "@tanstack/react-query";
import {
  RECENT_ANALYSIS_RUNS_QUERY_KEY,
  recentAnalysisRunsQueryOptions,
  uploadAnalysisRunMutationOptions,
} from "../features/analysis-runs/queries/analysis-run-queries.ts";
import {
  PROVIDER_DIRECTORY_QUERY_KEY,
  providerDirectoryQueryOptions,
} from "../features/providers/provider-directory-query.ts";

function jsonResponse(body, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}

function queryWithData(data) {
  return { state: { data } };
}

test("provider-directory query exposes loading, successful data, and the safe API error message", async (context) => {
  const client = new QueryClient();
  const originalFetch = globalThis.fetch;
  context.after(() => {
    globalThis.fetch = originalFetch;
    client.clear();
  });

  let releaseResponse;
  globalThis.fetch = () => new Promise((resolve) => { releaseResponse = resolve; });
  const pending = client.fetchQuery(providerDirectoryQueryOptions());
  assert.equal(client.getQueryState(PROVIDER_DIRECTORY_QUERY_KEY)?.status, "pending");

  const roles = ["claimExtractor", "embedding", "systemOne", "scholarlyMetadata", "openAccess"];
  const optionFor = (role) => ({
    role,
    providerId: "local",
    displayName: "Local",
    version: "v1",
    model: null,
    trustBoundary: "LOCAL",
    dataCategories: [],
    retentionDisclosure: null,
  });
  const directory = {
    providers: Object.fromEntries(roles.map((role) => [role, [optionFor(role), {
      ...optionFor(role),
      providerId: "unreviewed",
      displayName: "Unreviewed",
      trustBoundary: "UNREVIEWED",
    }]])),
    dataCategories: [],
  };
  const expectedDirectory = {
    providers: Object.fromEntries(roles.map((role) => [role, [optionFor(role)]])),
    dataCategories: [],
  };
  releaseResponse(jsonResponse(directory));
  assert.deepEqual(await pending, expectedDirectory);
  assert.equal(client.getQueryState(PROVIDER_DIRECTORY_QUERY_KEY)?.status, "success");

  client.clear();
  globalThis.fetch = async () => jsonResponse({ code: "UNAVAILABLE", message: "Provider catalog is temporarily unavailable." }, 503);
  await assert.rejects(
    client.fetchQuery(providerDirectoryQueryOptions()),
    { message: "Provider catalog is temporarily unavailable." },
  );
  assert.equal(client.getQueryState(PROVIDER_DIRECTORY_QUERY_KEY)?.error.message, "Provider catalog is temporarily unavailable.");
});

test("upload mutation refreshes the active recent-run query without refetching inactive pages", async (context) => {
  const client = new QueryClient();
  const originalFetch = globalThis.fetch;
  let unsubscribe = () => {};
  context.after(() => {
    unsubscribe();
    globalThis.fetch = originalFetch;
    client.clear();
  });

  const createdRun = {
    id: "run-created",
    documentId: "document-created",
    filename: "source.pdf",
    sourceContentSha256: "a".repeat(64),
    status: "QUEUED",
    progress: { message: "Upload run is visible." },
    configuration: {
      claimExtractor: { provider: "heuristic", version: "v1" },
      embedding: { provider: "local", version: "v1" },
      systemOne: { provider: "mock", version: "v1" },
      openAccess: { provider: "recorded-fixtures", version: "v1" },
      sourceParser: { provider: "grobid", version: "v1" },
      languageDetector: { provider: "local", version: "v1" },
    },
    createdAt: "2025-01-01T00:00:00Z",
    startedAt: null,
    failureReason: null,
  };
  let runs = [];
  let listRequests = 0;
  let uploadRequest;
  globalThis.fetch = async (url, options = {}) => {
    if (String(url).startsWith("/api/v1/analysis-runs?")) {
      listRequests += 1;
      return jsonResponse({ items: runs, nextCursor: null });
    }
    if (url === "/api/v1/analysis-runs" && options.method === "POST") {
      uploadRequest = options;
      runs = [createdRun];
      return jsonResponse({
        documentId: createdRun.documentId,
        analysisRunId: createdRun.id,
        filename: createdRun.filename,
        sourceContentSha256: createdRun.sourceContentSha256,
        status: "QUEUED",
        createdAt: createdRun.createdAt,
      });
    }
    throw new Error(`Unexpected browser request: ${url}`);
  };

  const activeRunsObserver = new QueryObserver(client, recentAnalysisRunsQueryOptions(null));
  unsubscribe = activeRunsObserver.subscribe(() => {});
  await client.fetchQuery(recentAnalysisRunsQueryOptions(null));
  await client.fetchQuery(recentAnalysisRunsQueryOptions("older-page"));

  const mutation = client.getMutationCache().build(client, uploadAnalysisRunMutationOptions(client));
  await mutation.execute({
    file: new File(["pdf"], "source.pdf", { type: "application/pdf" }),
    configuration: {
      claimExtractorProvider: "heuristic",
      embeddingProvider: "local",
      systemOneProvider: "mock",
      scholarlyMetadataProvider: "recorded-fixtures",
      openAccessProvider: "recorded-fixtures",
      externalProviderConsents: [],
    },
  });

  assert.equal(uploadRequest.body instanceof FormData, true);
  assert.equal(JSON.parse(uploadRequest.body.get("configuration")).claimExtractorProvider, "heuristic");
  assert.equal(listRequests, 3);
  assert.equal(client.getQueryData([...RECENT_ANALYSIS_RUNS_QUERY_KEY, null]).items[0].id, "run-created");
});

test("recent-run polling stays active while any displayed run is nonterminal and stops after terminal statuses", () => {
  const options = recentAnalysisRunsQueryOptions(null);
  const interval = options.refetchInterval;

  assert.equal(interval(queryWithData({ items: [{ status: "PROCESSING" }, { status: "FAILED" }] })), 2500);
  assert.equal(interval(queryWithData({ items: [{ status: "QUEUED" }, { status: "PARSED" }] })), 2500);
  assert.equal(interval(queryWithData({ items: [{ status: "COMPLETED" }, { status: "COMPLETED_WITH_WARNINGS" }] })), false);
  assert.equal(interval(queryWithData({ items: [{ status: "FAILED" }] })), false);
  assert.equal(interval(queryWithData({ items: [] })), false);
});
