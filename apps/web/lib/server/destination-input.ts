import { parseCountryCode } from "./country-input";

/**
 * What the sign-in routes accept: one phone or email and, optionally, the country the national number is read
 * in (D42: absent means the platform default on the API side). The rest is ignored.
 */
export interface Destination {
  phone?: string;
  email?: string;
  country?: string;
}

export function parseDestination(input: unknown): Destination | null {
  if (typeof input !== "object" || input === null) return null;
  const { phone, email, country } = input as Record<string, unknown>;
  const hasPhone = typeof phone === "string" && phone.trim() !== "";
  const hasEmail = typeof email === "string" && email.trim() !== "";
  if (hasPhone === hasEmail) return null;
  const base: Destination = hasPhone ? { phone: (phone as string).trim() } : { email: (email as string).trim() };
  const code = parseCountryCode(country);
  return code ? { ...base, country: code } : base;
}
