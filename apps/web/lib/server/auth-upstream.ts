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

/** The API's answer, unchanged apart from a header allowlist (status, problem body, Retry-After). */
export function relay(upstream: Response): Response {
  return new Response(upstream.body, {
    status: upstream.status,
    headers: filterResponseHeaders(upstream.headers),
  });
}

/** What the sign-in routes accept: one phone or email, the rest is ignored. */
export interface Destination {
  phone?: string;
  email?: string;
}

export function parseDestination(input: unknown): Destination | null {
  if (typeof input !== "object" || input === null) return null;
  const { phone, email } = input as Record<string, unknown>;
  const hasPhone = typeof phone === "string" && phone.trim() !== "";
  const hasEmail = typeof email === "string" && email.trim() !== "";
  if (hasPhone === hasEmail) return null;
  return hasPhone ? { phone: (phone as string).trim() } : { email: (email as string).trim() };
}
