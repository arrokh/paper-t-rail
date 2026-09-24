import assert from "node:assert/strict";
import test from "node:test";
import { GET, POST } from "../app/api/v1/[...path]/route.ts";

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
