import type { NextRequest } from "next/server";
import { getUploadProxyOrigins } from "@/lib/server/env";
import { guardOrigin, problemResponse } from "@/lib/server/responses";
import { getAccessToken, getRefreshToken } from "@/lib/server/session";
import { checkUploadTarget, parseContentLength } from "@/lib/upload-proxy";

const UPSTREAM_TIMEOUT_MS = 10 * 60_000;

/**
 * Upload proxy for deployments where the browser cannot PUT to the object store itself
 * (NEXT_PUBLIC_UPLOAD_MODE=proxy; the default is a direct upload with CORS on the bucket).
 * The body is streamed, never buffered, to the presigned URL the browser names in
 * `x-upload-target`. That URL must be on an allowed storage origin and belong to this media;
 * the signature in it, not this route, is what authorizes the write.
 */
export async function PUT(request: NextRequest, ctx: RouteContext<"/api/bff/uploads/[mediaId]">) {
  const denied = guardOrigin(request);
  if (denied) return denied;
  if (!(await getAccessToken()) && !(await getRefreshToken())) {
    return problemResponse(request, "bff_unauthenticated", 401);
  }

  const { mediaId } = await ctx.params;
  const target = checkUploadTarget(request.headers.get("x-upload-target"), mediaId, getUploadProxyOrigins());
  const length = parseContentLength(request.headers.get("content-length"));
  const contentType = request.headers.get("content-type");
  if (!target || length === null || !contentType || !request.body) {
    return problemResponse(request, "bff_bad_request", 400);
  }

  try {
    const upstream = await fetch(target, {
      method: "PUT",
      headers: { "content-type": contentType, "content-length": String(length) },
      body: request.body,
      // Required by Node's fetch to send a streamed body.
      duplex: "half",
      redirect: "error",
      cache: "no-store",
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    } as RequestInit & { duplex: "half" });
    await upstream.body?.cancel();
    return new Response(null, { status: upstream.ok ? 200 : upstream.status });
  } catch {
    return problemResponse(request, "bff_upstream_unavailable", 502);
  }
}
