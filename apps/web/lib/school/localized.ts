/**
 * Texts the API keeps as a map from language code to text (names and descriptions of skills and badges). The
 * languages to offer are those of the school's country (D42), plus any language the saved text already has, so
 * editing never silently drops a translation.
 */

/** The languages to show inputs for: the country's, then the saved text's other languages. */
export function inputLanguages(countryLanguages: readonly string[], saved: Readonly<Record<string, string>> | undefined): string[] {
  const out = [...countryLanguages];
  for (const code of Object.keys(saved ?? {})) if (!out.includes(code)) out.push(code);
  return out;
}

/** What the API gets: trimmed, empty languages left out. */
export function compactTexts(values: Readonly<Record<string, string>>): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [code, text] of Object.entries(values)) {
    const trimmed = text.trim();
    if (trimmed) out[code] = trimmed;
  }
  return out;
}

/** True when at least one language has text (the API wants at least one name). */
export function hasText(values: Readonly<Record<string, string>>): boolean {
  return Object.keys(compactTexts(values)).length > 0;
}
