/**
 * A country code from the browser (D42): the sign-in and invitation routes forward the country the phone number
 * was entered in. Only an ISO 3166-1 alpha-2 shape passes; the API checks that it names an enabled country, and
 * an absent country means the platform default there.
 */
export function parseCountryCode(value: unknown): string | undefined {
  return typeof value === "string" && /^[A-Z]{2}$/.test(value) ? value : undefined;
}
