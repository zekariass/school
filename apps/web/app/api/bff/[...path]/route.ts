import type { NextRequest } from "next/server";
import {
  buildUpstreamHeaders,
  buildUpstreamUrl,
  filterResponseHeaders,
} from "@/lib/bff";
import { getApiOrigin } from "@/lib/server/env";
import { refreshSession } from "@/lib/server/refresh";
import { guardOrigin, localeFromRequest, problemResponse } from "@/lib/server/responses";
import { decideRefresh } from "@/lib/server/session-core";
import {
  clearSessionCookies,
  getAccessToken,
  getRefreshToken,
  setSessionCookies,
} from "@/lib/server/session";

const UPSTREAM_TIMEOUT_MS = 30_000;

async function forward(request: NextRequest, ctx: RouteContext<"/api/bff/[...path]">) {
  const denied = guardOrigin(request);
  if (denied) return denied;

  const { path } = await ctx.params;
  const url = buildUpstreamUrl(getApiOrigin(), path, request.nextUrl.search);
  if (!url) return problemResponse(request, "bff_path_not_allowed", 404);

  const hasBody = request.method !== "GET" && request.method !== "HEAD";
  const body = hasBody ? await request.arrayBuffer() : undefined;

  const send = (accessToken: string | null) =>
    fetch(url, {
      method: request.method,
      headers: buildUpstreamHeaders(request.headers, accessToken),
      body,
      redirect: "error",
      cache: "no-store",
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });

  let accessToken = await getAccessToken();
  const refreshToken = await getRefreshToken();
  let refreshedAlready = false;

  /** One refresh for this request: new cookies on success, none left on rejection. */
  async function renew(): Promise<boolean> {
    if (!refreshToken || refreshedAlready) return false;
    refreshedAlready = true;
    const outcome = await refreshSession(getApiOrigin(), refreshToken, {
      locale: localeFromRequest(request),
    });
    if (outcome.status === "refreshed") {
      await setSessionCookies(outcome.tokens);
      accessToken = outcome.tokens.accessToken;
      return true;
    }
    if (outcome.status === "rejected") {
      await clearSessionCookies();
      accessToken = null;
    }
    return false;
  }

  // The access cookie expires with the token, so a long-idle tab has none left:
  // renew first instead of sending a request that is bound to fail.
  if (decideRefresh({ accessToken, refreshToken, now: Date.now() }) === "refresh") {
    await renew();
  }

  let upstream: Response;
  try {
    upstream = await send(accessToken);
    if (upstream.status === 401 && (await renew())) {
      // The token was rejected (e.g. clock skew or early rotation): retry once.
      await upstream.body?.cancel();
      upstream = await send(accessToken);
    }
  } catch {
    return problemResponse(request, "bff_upstream_unavailable", 502);
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
