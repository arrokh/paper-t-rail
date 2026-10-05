import assert from "node:assert/strict";
import test from "node:test";
import { QueryClient, QueryObserver } from "@tanstack/react-query";
import {
  RECENT_ANALYSIS_RUNS_QUERY_KEY,
  executionArtifactQueryOptions,
  recentAnalysisRunsQueryOptions,
  referenceResolutionReportQueryKey,
  referenceResolutionReportQueryOptions,
  sourceDocumentPdfAccessQueryKey,
  sourceDocumentPdfAccessQueryOptions,
  recordHumanReviewMutationOptions,
  uploadAnalysisRunMutationOptions,
} from "../features/analysis-runs/queries/analysis-run-queries.ts";
import {
  PROVIDER_DIRECTORY_QUERY_KEY,
  providerDirectoryQueryOptions,
} from "../features/providers/provider-directory-query.ts";

function jsonResponse(body, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
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
    retentionDisclosureFingerprint: null,
  });
  const directory = {
    providers: Object.fromEntries(roles.map((role) => [role, [optionFor(role), {
      ...optionFor(role),
      providerId: "unclassified",
      displayName: "Unclassified",
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
  assert.equal(client.getQueryData([...RECENT_ANALYSIS_RUNS_QUERY_KEY, null, "", ""]).items[0].id, "run-created");
});

test("Human Review mutation appends a separate result and refreshes the active run report", async (context) => {
  const client = new QueryClient();
  const originalFetch = globalThis.fetch;
  const createdReview = {
    id: "review-1",
    analysisRunId: "run-1",
    verificationId: "verification-1",
    action: "OVERRIDE",
    overrideStatus: "SUPPORTED",
    note: "Separate human assessment.",
    createdAt: "2026-01-01T00:00:00Z",
  };
  let report = { humanReviews: [] };
  let reportReads = 0;
  let submittedBody;
  globalThis.fetch = async (url, options = {}) => {
    if (url === "/api/v1/analysis-runs/run-1/report") {
      reportReads += 1;
      return jsonResponse(report);
    }
    if (url === "/api/v1/verifications/verification-1/reviews" && options.method === "POST") {
      submittedBody = JSON.parse(options.body);
      report = { humanReviews: [createdReview] };
      return jsonResponse(createdReview, 201);
    }
    throw new Error(`Unexpected browser request: ${url}`);
  };
  context.after(() => {
    globalThis.fetch = originalFetch;
    client.clear();
  });

  const reportOptions = referenceResolutionReportQueryOptions("run-1");
  const observer = new QueryObserver(client, reportOptions);
  const unsubscribe = observer.subscribe(() => {});
  context.after(unsubscribe);
  await client.fetchQuery(reportOptions);

  const mutation = client.getMutationCache().build(
    client,
    recordHumanReviewMutationOptions(client, "run-1"),
  );
  await mutation.execute({
    verificationId: "verification-1",
    action: "OVERRIDE",
    overrideStatus: "SUPPORTED",
    note: "Separate human assessment.",
  });

  assert.deepEqual(submittedBody, {
    action: "OVERRIDE",
    overrideStatus: "SUPPORTED",
    note: "Separate human assessment.",
  });
  assert.equal(reportReads, 2);
  assert.deepEqual(client.getQueryData(referenceResolutionReportQueryKey("run-1")).humanReviews, [createdReview]);
});

test("recent-run list polls only while a run is active and refreshes when the window regains focus", () => {
  const options = recentAnalysisRunsQueryOptions(null);

  assert.equal(options.refetchInterval({ state: { data: { items: [{ status: "PROCESSING" }] } } }), 2500);
  assert.equal(options.refetchInterval({ state: { data: { items: [{ status: "COMPLETED" }] } } }), false);
  assert.equal(options.refetchOnWindowFocus, true);
});

test("source PDF access query loads short-lived view and download URLs and reports API errors", async (context) => {
  const client = new QueryClient();
  const originalFetch = globalThis.fetch;
  const access = {
    filename: "paper.pdf",
    viewUrl: "http://127.0.0.1:9000/view-signed",
    downloadUrl: "http://127.0.0.1:9000/download-signed",
    expiresAt: "2026-10-01T00:00:00Z",
  };
  let request;
  globalThis.fetch = async (url, options) => {
    request = { url, options };
    return new Response(JSON.stringify(access), { status: 200, headers: { "content-type": "application/json" } });
  };
  context.after(() => {
    globalThis.fetch = originalFetch;
    client.clear();
  });

  const query = sourceDocumentPdfAccessQueryOptions("run-1");
  const urls = await client.fetchQuery(query);

  assert.equal(request.url, "/api/v1/analysis-runs/run-1/source-document");
  assert.deepEqual(urls, access);
  assert.deepEqual(client.getQueryData(sourceDocumentPdfAccessQueryKey("run-1")), access);

  globalThis.fetch = async () => new Response(
    JSON.stringify({ code: "SOURCE_UNAVAILABLE", message: "The stored Source Document is temporarily unavailable." }),
    { status: 503, headers: { "content-type": "application/json" } },
  );
  await assert.rejects(
    client.fetchQuery({ ...sourceDocumentPdfAccessQueryOptions("run-2"), retry: false }),
    { message: "The stored Source Document is temporarily unavailable." },
  );
});

test("artifact reads request the selected run-span-role association", async (context) => {
  const client = new QueryClient();
  const originalFetch = globalThis.fetch;
  let request;
  const artifact = {
    id: "shared-artifact",
    spanId: "another-linked-span",
    role: "RESPONSE",
    fidelity: "SANITIZED",
    reason: null,
    mediaType: null,
    content: "association fixture",
    schemaVersion: "v1",
    captureVersion: "v1",
    sanitizerVersion: "v1",
    sizeBytes: null,
  };
  globalThis.fetch = async (url, options) => {
    request = { url, options };
    return new Response(JSON.stringify(artifact), { status: 200, headers: { "content-type": "application/json" } });
  };
  context.after(() => {
    globalThis.fetch = originalFetch;
    client.clear();
  });

  const result = await client.fetchQuery(executionArtifactQueryOptions("run-1", artifact.id, "selected-span", "RESPONSE"));

  assert.equal(request.url, "/api/v1/analysis-runs/run-1/execution/artifacts/shared-artifact?spanId=selected-span&role=RESPONSE");
  assert.deepEqual(result, artifact);
  assert.equal(result.spanId, "another-linked-span");
});

test("source PDF access URLs stay stable until the user explicitly refreshes them", () => {
  const options = sourceDocumentPdfAccessQueryOptions("run-1");

  assert.equal(options.staleTime, Infinity);
  assert.equal(options.refetchInterval, false);
  assert.equal(options.refetchOnWindowFocus, false);
  assert.equal(options.retry, false);
});
