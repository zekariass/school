import type { NextRequest } from "next/server";
import { messages, resolveLocale } from "@school/i18n";
import {
  bffProblem,
  buildUpstreamHeaders,
  buildUpstreamUrl,
  filterResponseHeaders,
} from "@/lib/bff";
import { getApiOrigin } from "@/lib/server/env";
import { getAccessToken } from "@/lib/server/session";

const UPSTREAM_TIMEOUT_MS = 30_000;

function problem(request: NextRequest, code: string, status: number) {
  const locale = resolveLocale(
    (request.headers.get("accept-language") ?? "")
      .split(",")
      .map((part) => part.split(";")[0] ?? ""),
  );
  const { bffNotFound, bffUnavailable } = messages[locale].errors;
  const title = status === 404 ? bffNotFound : bffUnavailable;
  return Response.json(bffProblem(code, status, title), {
    status,
    headers: { "content-type": "application/problem+json", "cache-control": "no-store" },
  });
}

async function forward(request: NextRequest, ctx: RouteContext<"/api/bff/[...path]">) {
  const { path } = await ctx.params;
  const url = buildUpstreamUrl(getApiOrigin(), path, request.nextUrl.search);
  if (!url) return problem(request, "bff_path_not_allowed", 404);

  const hasBody = request.method !== "GET" && request.method !== "HEAD";
  let upstream: Response;
  try {
    upstream = await fetch(url, {
      method: request.method,
      headers: buildUpstreamHeaders(request.headers, await getAccessToken()),
      body: hasBody ? await request.arrayBuffer() : undefined,
      redirect: "error",
      cache: "no-store",
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });
  } catch {
    return problem(request, "bff_upstream_unavailable", 502);
  }

  return new Response(upstream.body, {
    status: upstream.status,
    headers: filterResponseHeaders(upstream.headers),
  });
}

export const GET = forward;
export const POST = forward;
export const PUT = forward;
export const PATCH = forward;
export const DELETE = forward;
