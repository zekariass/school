import { charCount } from "./text";

/** The reason of a safeguarding read: 10 to 300 characters, stored in the audit log (`messaging.review_reason_required`). */
export const REASON_MIN = 10;
export const REASON_MAX = 300;

export type ReasonCheck = { ok: true; reason: string } | { ok: false; problem: "short" | "long" };

/** The API checks the same; this only keeps the confirm button off until the reason can pass. */
export function checkReason(input: string): ReasonCheck {
  const reason = input.trim();
  const length = charCount(reason);
  if (length < REASON_MIN) return { ok: false, problem: "short" };
  if (length > REASON_MAX) return { ok: false, problem: "long" };
  return { ok: true, reason };
}

/** Characters left for the counter under the reason box (negative when over). */
export function reasonRemaining(input: string): number {
  return REASON_MAX - charCount(input.trim());
}
