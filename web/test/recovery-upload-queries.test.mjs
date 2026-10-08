import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import { QueryClient } from "@tanstack/react-query";
import {
  createRecoveryBatchMutationOptions,
  recoveryBatchQueryKey,
  recoveryBatchQueryOptions,
  recoveryRightsDeclarationQueryOptions,
  uploadRecoveryPdfMutationOptions,
} from "../features/recovery-uploads/queries/recovery-upload-queries.ts";

function jsonResponse(body, status = 200) {
  return new Response(body === null ? null : JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function stagedUpload(overrides = {}) {
  return {
    id: "upload-1",
    localReferenceKey: "b0",
    idempotencyKey: "upload-key-1",
    filename: "paper.pdf",
    expectedSize: 9,
    expectedSha256: "a".repeat(64),
    status: "STAGED",
    failureCode: null,
    actualSize: 9,
    actualSha256: "a".repeat(64),
    createdAt: "2026-10-01T00:00:00Z",
    finalizedAt: "2026-10-01T00:01:00Z",
    ...overrides,
  };
}

test("recovery rights and active-batch queries read only the same-origin API", async (context) => {
  const client = new QueryClient();
  const originalFetch = globalThis.fetch;
  const requests = [];
  globalThis.fetch = async (url, options = {}) => {
    requests.push({ url, options });
    if (url === "/api/v1/recovery-rights-declaration") {
      return jsonResponse({
        version: "recovery-rights-v1",
        text: "User declaration.",
        maxFileBytes: 50_000_000,
        maxFilesPerBatch: 10,
        maxBatchBytes: 250_000_000,
        uploadUrlTtlSeconds: 300,
        inactivityTtlSeconds: 604_800,
      });
    }
    if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return jsonResponse([]);
    throw new Error(`Unexpected request: ${url}`);
  };
  context.after(() => {
    globalThis.fetch = originalFetch;
    client.clear();
  });

  const rights = await client.fetchQuery(recoveryRightsDeclarationQueryOptions());
  const batch = await client.fetchQuery(recoveryBatchQueryOptions("run-1"));

  assert.equal(rights.version, "recovery-rights-v1");
  assert.equal(rights.maxFileBytes, 50_000_000);
  assert.equal(batch, null);
  assert.deepEqual(requests.map(({ url }) => url), [
    "/api/v1/recovery-rights-declaration",
    "/api/v1/analysis-runs/run-1/recovery-batches",
  ]);
  assert.deepEqual(client.getQueryData(recoveryBatchQueryKey("run-1")), null);
});

test("recovery batch creation records the exact displayed declaration version and acceptance", async (context) => {
  const client = new QueryClient();
  const originalFetch = globalThis.fetch;
  let request;
  globalThis.fetch = async (url, options = {}) => {
    request = { url, options };
    return jsonResponse({ id: "batch-1", analysisRunId: "run-1", uploads: [] });
  };
  context.after(() => {
    globalThis.fetch = originalFetch;
    client.clear();
  });

  const mutation = client.getMutationCache().build(client, createRecoveryBatchMutationOptions(client));
  await mutation.execute({
    analysisRunId: "run-1",
    idempotencyKey: "batch-key-1",
    rightsDeclarationVersion: "recovery-rights-v1",
    rightsDeclarationAccepted: true,
  });

  assert.equal(request.url, "/api/v1/analysis-runs/run-1/recovery-batches");
  assert.equal(request.options.method, "POST");
  assert.deepEqual(JSON.parse(request.options.body), {
    idempotencyKey: "batch-key-1",
    rightsDeclarationVersion: "recovery-rights-v1",
    rightsDeclarationAccepted: true,
  });
});

test("recovery PDF mutation signs, directly uploads, and finalizes without retaining the bearer URL", async (context) => {
  const client = new QueryClient();
  const originalFetch = globalThis.fetch;
  const bytes = new Uint8Array([37, 80, 68, 70, 45, 49, 46, 55, 10]);
  const file = new File([bytes], "paper.pdf", { type: "application/pdf" });
  const signedUrl = "https://private-object-store.example/recovery/staging/signed-token";
  const created = stagedUpload({ status: "PENDING_UPLOAD", actualSize: null, actualSha256: null, finalizedAt: null });
  const finalized = stagedUpload({ expectedSha256: createHash("sha256").update(bytes).digest("hex"), actualSha256: createHash("sha256").update(bytes).digest("hex") });
  let intentRequest;
  let storageRequest;
  let finalizationRequest;
  globalThis.fetch = async (url, options = {}) => {
    if (String(url).endsWith("/entries/b0/uploads")) {
      intentRequest = { url, options };
      return jsonResponse({
        upload: { ...created, expectedSize: file.size, expectedSha256: finalized.actualSha256, idempotencyKey: "resume-key" },
        uploadUrl: signedUrl,
        requiredHeaders: { "Content-Type": "application/pdf", "x-amz-checksum-sha256": "checksum-base64" },
        uploadUrlExpiresAt: "2026-10-08T10:05:00Z",
      });
    }
    if (url === signedUrl) {
      storageRequest = { url, options };
      return new Response(null, { status: 200 });
    }
    if (String(url).endsWith("/uploads/upload-1/finalize")) {
      finalizationRequest = { url, options };
      return jsonResponse(finalized);
    }
    throw new Error(`Unexpected request: ${url}`);
  };
  context.after(() => {
    globalThis.fetch = originalFetch;
    client.clear();
  });

  const mutation = client.getMutationCache().build(client, uploadRecoveryPdfMutationOptions(client));
  const result = await mutation.execute({
    analysisRunId: "run-1",
    batchId: "batch-1",
    localReferenceKey: "b0",
    file,
    idempotencyKey: "resume-key",
  });

  assert.deepEqual(JSON.parse(intentRequest.options.body), {
    idempotencyKey: "resume-key",
    filename: "paper.pdf",
    expectedSize: file.size,
    expectedSha256: createHash("sha256").update(bytes).digest("hex"),
  });
  assert.equal(storageRequest.url, signedUrl);
  assert.equal(storageRequest.options.method, "PUT");
  assert.equal(storageRequest.options.mode, "cors");
  assert.equal(storageRequest.options.credentials, "omit");
  assert.deepEqual(storageRequest.options.headers, {
    "Content-Type": "application/pdf",
    "x-amz-checksum-sha256": "checksum-base64",
  });
  assert.equal(finalizationRequest.options.method, "POST");
  assert.equal(result.status, "STAGED");
  assert.equal(JSON.stringify(mutation.state.data).includes(signedUrl), false);
});

test("failed direct upload returns a safe message and never attempts finalization", async (context) => {
  const client = new QueryClient();
  const originalFetch = globalThis.fetch;
  const signedUrl = "https://private-object-store.example/recovery/staging/secret";
  let finalized = false;
  globalThis.fetch = async (url) => {
    if (String(url).endsWith("/entries/b0/uploads")) {
      return jsonResponse({
        upload: stagedUpload({ status: "PENDING_UPLOAD" }),
        uploadUrl: signedUrl,
        requiredHeaders: { "Content-Type": "application/pdf", "x-amz-checksum-sha256": "checksum" },
        uploadUrlExpiresAt: "2026-10-08T10:05:00Z",
      });
    }
    if (url === signedUrl) throw new TypeError(`Failed to fetch ${signedUrl}`);
    if (String(url).endsWith("/finalize")) finalized = true;
    throw new Error(`Unexpected request: ${url}`);
  };
  context.after(() => {
    globalThis.fetch = originalFetch;
    client.clear();
  });

  const mutation = client.getMutationCache().build(client, uploadRecoveryPdfMutationOptions(client));
  await assert.rejects(
    mutation.execute({
      analysisRunId: "run-1",
      batchId: "batch-1",
      localReferenceKey: "b0",
      file: new File(["pdf"], "paper.pdf", { type: "application/pdf" }),
      idempotencyKey: "resume-key",
    }),
    (error) => error.message === "The private storage upload did not complete. Select the same file to resume this upload." && !error.message.includes(signedUrl),
  );
  assert.equal(finalized, false);
});
