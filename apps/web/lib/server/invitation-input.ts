import { isCompleteParentCode, looksLikeInviteToken, normalizeParentCode } from "@school/validation/guardians";

/** Exactly one of the token of an invitation link and the printed parent code. */
export type InvitationKey = { token: string; code?: undefined } | { code: string; token?: undefined };

/** What the invitation routes accept from the browser: the key and one phone or email. */
export interface InvitationInput {
  key: InvitationKey;
  phone?: string;
  email?: string;
  otpCode?: string;
  displayName?: string;
  preferredLanguage?: string;
}

function text(value: unknown, max: number): string | undefined {
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  return trimmed !== "" && trimmed.length <= max ? trimmed : undefined;
}

function readKey(raw: Record<string, unknown>): InvitationKey | null {
  const token = typeof raw.token === "string" ? raw.token.trim() : "";
  const code = typeof raw.code === "string" ? raw.code.trim() : "";
  if ((token !== "") === (code !== "")) return null;
  if (token) return looksLikeInviteToken(token) ? { token } : null;
  return isCompleteParentCode(code) ? { code: normalizeParentCode(code) } : null;
}

/**
 * Reads and bounds the browser's input. The API validates again; this keeps junk (and unexpected
 * fields) from being forwarded. Null when the key is missing or malformed or when not exactly one
 * of phone and email is given (the preview needs neither).
 */
export function parseInvitationInput(input: unknown, need: "key" | "destination" | "accept"): InvitationInput | null {
  if (typeof input !== "object" || input === null) return null;
  const raw = input as Record<string, unknown>;
  const key = readKey(raw);
  if (!key) return null;
  const out: InvitationInput = { key };
  if (need === "key") return out;

  const phone = text(raw.phone, 32);
  const email = text(raw.email, 254);
  if ((phone === undefined) === (email === undefined)) return null;
  if (phone !== undefined) out.phone = phone;
  if (email !== undefined) out.email = email;
  if (need === "destination") return out;

  const otpCode = text(raw.otpCode, 16);
  if (!otpCode || !/^\d{4,10}$/.test(otpCode)) return null;
  out.otpCode = otpCode;
  const displayName = text(raw.displayName, 100);
  if (displayName) out.displayName = displayName;
  const preferredLanguage = text(raw.preferredLanguage, 8);
  if (preferredLanguage && /^[a-z]{2,3}$/.test(preferredLanguage)) out.preferredLanguage = preferredLanguage;
  return out;
}
