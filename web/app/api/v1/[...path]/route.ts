import type { NextRequest } from "next/server";
import { logStructured } from "../../../../lib/structured-logger.ts";

export const dynamic = "force-dynamic";

const apiOrigin = process.env.PAPER_T_RAIL_API_ORIGIN ?? "http://127.0.0.1:8080";
const requestIdPattern = /^[A-Za-z0-9._:-]{1,128}$/;

type RouteContext = { params: Promise<{ path: string[] }> };

async function proxy(request: NextRequest, context: RouteContext): Promise<Response> {
  const { path } = await context.params;
  const safePath = path.map((part) => encodeURIComponent(part)).join("/");
  const target = new URL(`/api/v1/${safePath}`, apiOrigin);
  target.search = request.nextUrl.search;
  const startedAt = performance.now();
  const suppliedRequestId = request.headers.get("x-request-id");
  const requestId = suppliedRequestId && requestIdPattern.test(suppliedRequestId)
    ? suppliedRequestId
    : crypto.randomUUID();

  const headers = new Headers();
  const contentType = request.headers.get("content-type");
  if (contentType) headers.set("content-type", contentType);
  headers.set("accept", "application/json");
  headers.set("x-request-id", requestId);

  try {
    const upstream = await fetch(target, {
      method: request.method,
      headers,
      body: request.method === "GET" || request.method === "HEAD" ? undefined : await request.arrayBuffer(),
      cache: "no-store",
    });
    const responseHeaders = new Headers();
    const upstreamType = upstream.headers.get("content-type");
    if (upstreamType) responseHeaders.set("content-type", upstreamType);
    responseHeaders.set("cache-control", "no-store");
    responseHeaders.set("x-request-id", requestId);
    logStructured({
      level: upstream.ok ? "INFO" : "WARN",
      event: "api_proxy_request_completed",
      requestId,
      httpMethod: request.method,
      httpPath: "/api/v1/*",
      httpStatus: upstream.status,
      durationMs: Math.round(performance.now() - startedAt),
    });
    return new Response(upstream.body, { status: upstream.status, headers: responseHeaders });
  } catch (cause) {
    logStructured({
      level: "ERROR",
      event: "api_proxy_request_failed",
      requestId,
      httpMethod: request.method,
      httpPath: "/api/v1/*",
      httpStatus: 502,
      durationMs: Math.round(performance.now() - startedAt),
      errorType: cause instanceof Error ? cause.name : "UnknownError",
    });
    return Response.json(
      { code: "API_UNAVAILABLE", message: "The local Paper T-Rail API is unavailable. Start the application stack and try again." },
      { status: 502, headers: { "cache-control": "no-store", "x-request-id": requestId } },
    );
  }
}

export const GET = proxy;
export const POST = proxy;
