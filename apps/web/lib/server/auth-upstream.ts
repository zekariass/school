import { filterResponseHeaders } from "@/lib/bff";
import { getApiOrigin } from "./env";

const TIMEOUT_MS = 15_000;

/** POSTs JSON to a public `/api/v1/auth/*` endpoint; null when the API is unreachable. */
export async function postAuth(
  path: "otp/request" | "otp/verify" | "logout",
  body: unknown,
  locale: string,
): Promise<Response | null> {
  try {
    return await fetch(`${getApiOrigin()}/api/v1/auth/${path}`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        accept: "application/json, application/problem+json",
        "accept-language": locale,
      },
      body: JSON.stringify(body),
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch {
    return null;
  }
}

/**
 * POSTs JSON to a public `/api/v1/invitations/*` endpoint WITHOUT any session token (a signed-in
 * visitor of an invitation link is treated like anyone else); null when the API is unreachable.
 */
export async function postInvitation(
  path: "preview" | "otp" | "accept",
  body: unknown,
  locale: string,
): Promise<Response | null> {
  try {
    return await fetch(`${getApiOrigin()}/api/v1/invitations/${path}`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        accept: "application/json, application/problem+json",
        "accept-language": locale,
      },
      body: JSON.stringify(body),
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch {
    return null;
  }
}

/** The API's answer, unchanged apart from a header allowlist (status, problem body, Retry-After). */
export function relay(upstream: Response): Response {
  return new Response(upstream.body, {
    status: upstream.status,
    headers: filterResponseHeaders(upstream.headers),
  });
}

export { parseDestination, type Destination } from "./destination-input";
