import assert from "node:assert/strict";
import test from "node:test";
import { DELETE, GET, POST } from "../app/api/v1/[...path]/route.ts";

test("DELETE is proxied only for a Source Document UUID, not other API operations", async (context) => {
  const originalFetch = globalThis.fetch;
  let upstreamRequest;
  globalThis.fetch = async (url, options) => {
    upstreamRequest = { url, options };
    return new Response(null, { status: 204 });
  };
  context.after(() => { globalThis.fetch = originalFetch; });

  const request = (path) => ({
    method: "DELETE",
    headers: new Headers(),
    nextUrl: new URL(`http://localhost/api/v1/${path.join("/")}`),
    arrayBuffer: async () => new ArrayBuffer(0),
  });
  const invalid = await DELETE(request(["operator", "caches", "crossref"]), {
    params: Promise.resolve({ path: ["operator", "caches", "crossref"] }),
  });
  assert.equal(invalid.status, 404);
  const invalidDocumentId = await DELETE(request(["documents", "not-a-uuid"]), {
    params: Promise.resolve({ path: ["documents", "not-a-uuid"] }),
  });
  assert.equal(invalidDocumentId.status, 404);
  assert.equal(upstreamRequest, undefined);

  const documentId = "32a8f3c1-3f82-4c6c-aab6-8bb8a8e8d52f";
  const response = await DELETE(request(["documents", documentId]), {
    params: Promise.resolve({ path: ["documents", documentId] }),
  });
  assert.equal(response.status, 204);
  assert.equal(upstreamRequest.options.method, "DELETE");
  assert.equal(new URL(upstreamRequest.url).pathname, `/api/v1/documents/${documentId}`);
});

test("API proxy forwards the request ID, returns it to the caller, and emits structured request logs", async (context) => {
  const originalFetch = globalThis.fetch;
  const originalInfo = console.info;
  const logs = [];
  let upstreamRequest;
  globalThis.fetch = async (url, options) => {
    upstreamRequest = { url, options };
    return new Response("[]", { status: 200, headers: { "content-type": "application/json" } });
  };
  console.info = (line) => logs.push(line);
  context.after(() => {
    globalThis.fetch = originalFetch;
    console.info = originalInfo;
  });

  const response = await GET(
    {
      method: "GET",
      headers: new Headers({ "x-request-id": "trace-known-42" }),
      nextUrl: new URL("http://localhost/api/v1/analysis-runs?limit=25"),
      arrayBuffer: async () => new ArrayBuffer(0),
    },
    { params: Promise.resolve({ path: ["analysis-runs"] }) },
  );

  assert.equal(response.status, 200);
  assert.equal(response.headers.get("x-request-id"), "trace-known-42");
  assert.equal(upstreamRequest.options.headers.get("x-request-id"), "trace-known-42");
  assert.equal(new URL(upstreamRequest.url).search, "?limit=25");
  assert.equal(logs.length, 1);
  const record = JSON.parse(logs[0]);
  assert.equal(record.service.name, "paper-t-rail-web");
  assert.equal(record.log.level, "INFO");
  assert.equal(record.eventName, "api_proxy_request_completed");
  assert.equal(record.requestId, "trace-known-42");
  assert.equal(record.httpMethod, "GET");
  assert.equal(record.httpStatus, 200);
  assert.equal("query" in record, false);
  assert.equal("body" in record, false);
});

test("API proxy preserves no-store short-lived PDF links without logging bearer tokens", async (context) => {
  const originalFetch = globalThis.fetch;
  const originalInfo = console.info;
  const logs = [];
  console.info = (message) => logs.push(String(message));
  const sourceDocumentAccess = {
    filename: "source paper.pdf",
    viewUrl: "http://127.0.0.1:9000/source-documents/paper.pdf?X-Amz-Signature=view-secret",
    downloadUrl: "http://127.0.0.1:9000/source-documents/paper.pdf?X-Amz-Signature=download-secret",
    expiresAt: "2026-10-01T00:00:00Z",
  };
  globalThis.fetch = async () => {
    return new Response(JSON.stringify(sourceDocumentAccess), {
      status: 200,
      headers: { "content-type": "application/json", "cache-control": "no-store" },
    });
  };
  context.after(() => { globalThis.fetch = originalFetch; console.info = originalInfo; });

  const response = await GET(
    {
      method: "GET",
      headers: new Headers(),
      nextUrl: new URL("http://localhost/api/v1/analysis-runs/run-1/source-document"),
      arrayBuffer: async () => new ArrayBuffer(0),
    },
    { params: Promise.resolve({ path: ["analysis-runs", "run-1", "source-document"] }) },
  );

  assert.equal(response.headers.get("content-type"), "application/json");
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.deepEqual(await response.json(), sourceDocumentAccess);
  assert.equal(logs.some((message) => message.includes("view-secret") || message.includes("download-secret")), false);
});

test("API proxy replaces an invalid request ID with a generated UUID", async (context) => {
  const originalFetch = globalThis.fetch;
  const originalInfo = console.info;
  let upstreamRequestId;
  globalThis.fetch = async (_url, options) => {
    upstreamRequestId = options.headers.get("x-request-id");
    return new Response("[]", { status: 200, headers: { "content-type": "application/json" } });
  };
  console.info = () => {};
  context.after(() => {
    globalThis.fetch = originalFetch;
    console.info = originalInfo;
  });

  const response = await GET(
    {
      method: "GET",
      headers: new Headers({ "x-request-id": "invalid id with spaces" }),
      nextUrl: new URL("http://localhost/api/v1/analysis-runs"),
      arrayBuffer: async () => new ArrayBuffer(0),
    },
    { params: Promise.resolve({ path: ["analysis-runs"] }) },
  );

  const requestId = response.headers.get("x-request-id");
  assert.match(requestId, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i);
  assert.equal(upstreamRequestId, requestId);
});

test("API proxy failure preserves request correlation without logging upstream exception content", async (context) => {
  const originalFetch = globalThis.fetch;
  const originalError = console.error;
  const logs = [];
  globalThis.fetch = async () => {
    throw new Error("request failed while processing sensitive document text");
  };
  console.error = (line) => logs.push(line);
  context.after(() => {
    globalThis.fetch = originalFetch;
    console.error = originalError;
  });

  const response = await POST(
    {
      method: "POST",
      headers: new Headers({ "x-request-id": "trace-failure-43", "content-type": "application/json" }),
      nextUrl: new URL("http://localhost/api/v1/documents/id/analysis-runs"),
      arrayBuffer: async () => new TextEncoder().encode("sensitive request body").buffer,
    },
    { params: Promise.resolve({ path: ["documents", "id", "analysis-runs"] }) },
  );

  assert.equal(response.status, 502);
  assert.equal(response.headers.get("x-request-id"), "trace-failure-43");
  assert.equal(logs.length, 1);
  const record = JSON.parse(logs[0]);
  assert.equal(record.eventName, "api_proxy_request_failed");
  assert.equal(record.requestId, "trace-failure-43");
  assert.equal(record.httpStatus, 502);
  assert.equal(record.errorType, "Error");
  assert.equal(logs[0].includes("sensitive"), false);
});
