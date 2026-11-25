import { emailSchema, phoneSchema, type CountryCode } from './index';

/**
 * Pure helpers of the guardian flows (M5b), shared by web and mobile: printed parent codes,
 * the destination an invited adult types, and the shape of an invitation token.
 */

/** Relationship types of a guardian link (backend `RelationshipType`). */
export const RELATIONSHIP_TYPES = [
  'MOTHER',
  'FATHER',
  'GRANDPARENT',
  'SIBLING',
  'AUNT_UNCLE',
  'LEGAL_GUARDIAN',
  'OTHER',
] as const;
export type RelationshipType = (typeof RELATIONSHIP_TYPES)[number];

export function isRelationshipType(value: unknown): value is RelationshipType {
  return typeof value === 'string' && (RELATIONSHIP_TYPES as readonly string[]).includes(value);
}

/** Printed parent codes use the login-code format: 10 characters from this alphabet, shown as `XXXXX-XXXXX`. */
export const PARENT_CODE_LENGTH = 10;
export const PARENT_CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
const GROUP = 5;

/** Letters and digits only, upper-cased, at most 10 characters ("ahr4p a4ws9" -> "AHR4PA4WS9"). */
export function normalizeParentCode(input: string): string {
  return input
    .replace(/[^A-Za-z0-9]/g, '')
    .toUpperCase()
    .slice(0, PARENT_CODE_LENGTH);
}

/** As the parent types: "ahr4pa4ws9" -> "AHR4P-A4WS9" (the hyphen appears with the second group). */
export function formatParentCode(input: string): string {
  const code = normalizeParentCode(input);
  return code.length <= GROUP ? code : `${code.slice(0, GROUP)}-${code.slice(GROUP)}`;
}

/** True for a whole code made of valid characters. */
export function isCompleteParentCode(input: string): boolean {
  const code = normalizeParentCode(input);
  return code.length === PARENT_CODE_LENGTH && [...code].every((char) => PARENT_CODE_ALPHABET.includes(char));
}

/** Invitation tokens are 256-bit base64url strings; anything else cannot be a live invitation. */
export function looksLikeInviteToken(token: string | null | undefined): token is string {
  return typeof token === 'string' && /^[A-Za-z0-9_-]{16,128}$/.test(token);
}

/** Where the one-time code goes: exactly one of phone (E.164) or email. */
export type ContactDestination = { phone: string; email?: undefined } | { email: string; phone?: undefined };

export type ParsedDestination =
  | { ok: true; destination: ContactDestination }
  | { ok: false; key: 'validation.phone' | 'validation.email' | 'validation.required' };

/**
 * Reads what an adult typed in a single "phone or email" field: anything with an `@` is an email,
 * everything else a phone number in `country`. The server validates again.
 */
export function parseDestinationInput(input: string, country: CountryCode = 'ET'): ParsedDestination {
  const value = input.trim();
  if (!value) return { ok: false, key: 'validation.required' };
  if (value.includes('@')) {
    const parsed = emailSchema.safeParse(value);
    return parsed.success ? { ok: true, destination: { email: parsed.data } } : { ok: false, key: 'validation.email' };
  }
  const parsed = phoneSchema(country).safeParse(value);
  return parsed.success ? { ok: true, destination: { phone: parsed.data } } : { ok: false, key: 'validation.phone' };
}

/** What went wrong with an invitation call, for choosing the message and the next step. */
export type InvitationProblem =
  | { kind: 'notFound' }
  | { kind: 'contactUnavailable' }
  | { kind: 'rateLimited'; retryAfterSeconds: number | null }
  | { kind: 'invalidCode' }
  | { kind: 'invalidContact' }
  | { kind: 'network' }
  | { kind: 'generic' };

/**
 * Maps a failed invitation call (preview, OTP, accept, redeem) to what the screen should do.
 * An unknown, expired, revoked or used-up invitation is always `notFound`: the screen never says which.
 * `status` is undefined when the server could not be reached.
 */
export function classifyInvitationFailure(
  status: number | undefined,
  code: string | undefined,
  retryAfterSeconds: number | null = null,
): InvitationProblem {
  if (status === undefined || status === 502 || status === 503 || status === 504) return { kind: 'network' };
  if (status === 429 || code === 'auth.rate_limited') return { kind: 'rateLimited', retryAfterSeconds };
  if (status === 404 && (code === undefined || code === 'invitation.not_found')) return { kind: 'notFound' };
  if (code === 'invitation.contact_unavailable') return { kind: 'contactUnavailable' };
  if (code === 'auth.invalid_code') return { kind: 'invalidCode' };
  if (code === 'auth.invalid_destination') return { kind: 'invalidContact' };
  return { kind: 'generic' };
}

/** The dead-invitation screen (not a form error) is the answer to a `notFound`. */
export function isDeadInvitation(problem: InvitationProblem): boolean {
  return problem.kind === 'notFound';
}
