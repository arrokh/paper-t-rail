import { NextRequest, NextResponse } from "next/server";

export const dynamic = "force-dynamic";

const apiOrigin = process.env.PAPER_T_RAIL_API_ORIGIN ?? "http://127.0.0.1:8080";

type RouteContext = { params: Promise<{ path: string[] }> };

async function proxy(request: NextRequest, context: RouteContext): Promise<Response> {
  const { path } = await context.params;
  const safePath = path.map((part) => encodeURIComponent(part)).join("/");
  const target = new URL(`/api/v1/${safePath}`, apiOrigin);
  target.search = request.nextUrl.search;

  const headers = new Headers();
  const contentType = request.headers.get("content-type");
  if (contentType) headers.set("content-type", contentType);
  headers.set("accept", "application/json");

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
    return new Response(upstream.body, { status: upstream.status, headers: responseHeaders });
  } catch {
    return NextResponse.json(
      { code: "API_UNAVAILABLE", message: "The local Paper T-Rail API is unavailable. Start the application stack and try again." },
      { status: 502, headers: { "cache-control": "no-store" } },
    );
  }
}

export const GET = proxy;
export const POST = proxy;
