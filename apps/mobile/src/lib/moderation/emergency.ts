/**
 * Which country's safety text to ask for (`GET /safety/emergency-info?country=`). Never a literal (D42): the country of the school
 * the reported thing belongs to, taken from the person's own memberships; else the country that applies to the person
 * (`/me.country`: their home country, else the platform default). The API answers 404 for a country without a text and the screen
 * then shows nothing: a helpline of another country is never offered instead.
 */
export interface MeCountries {
  country?: { code: string } | undefined;
  memberships?: readonly { schoolId: string; countryCode: string }[] | undefined;
}

export function emergencyCountry(me: MeCountries | null | undefined, schoolId: string | null | undefined): string | null {
  if (!me) return null;
  const school = schoolId ? me.memberships?.find((membership) => membership.schoolId === schoolId)?.countryCode : undefined;
  return school || me.country?.code || null;
}

/**
 * The text to show, or null: only what the API sent, trimmed. Nothing is composed or translated here; a missing or blank text
 * (404, an error, an empty row) shows no safety line at all.
 */
export function emergencyText(info: { text?: string | undefined } | null | undefined): string | null {
  const text = info?.text?.trim();
  return text ? text : null;
}
