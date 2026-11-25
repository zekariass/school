import { classifyInvitationFailure, type InvitationProblem } from "@school/validation/guardians";
import { readProblem, retryAfterSeconds } from "@/lib/problem";

export interface CallFailure {
  problem: InvitationProblem;
  /** The API's already localized detail, when it sent one. */
  message: string | null;
}

export type CallResult<T> = { ok: true; data: T } | { ok: false; failure: CallFailure };

/**
 * POSTs JSON to one of our own invitation Route Handlers and turns every failure (offline, 4xx,
 * 429 with Retry-After, 5xx) into a classified problem. Tokens never come back: the accept handler
 * sets cookies and answers `{ ok: true }`.
 */
export async function postJson<T>(url: string, body: unknown, locale: string): Promise<CallResult<T>> {
  let response: Response;
  try {
    response = await fetch(url, {
      method: "POST",
      headers: { "content-type": "application/json", "accept-language": locale },
      body: JSON.stringify(body),
      cache: "no-store",
    });
  } catch {
    return { ok: false, failure: { problem: { kind: "network" }, message: null } };
  }
  if (response.ok) {
    const data = (await response.json().catch(() => ({}))) as T;
    return { ok: true, data };
  }
  const problem = await readProblem(response);
  return {
    ok: false,
    failure: {
      problem: classifyInvitationFailure(
        response.status,
        problem?.code,
        retryAfterSeconds(response.headers.get("retry-after")),
      ),
      message: problem?.detail ?? null,
    },
  };
}
