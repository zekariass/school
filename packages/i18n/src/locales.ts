/**
 * UI locales the apps ship catalogs for. Languages available to a school or user
 * are data (languages / school_languages tables, spec §70); this list only says
 * which of them the UI is translated into. Afaan Oromo (om), Tigrinya (ti) and
 * Somali (so) are added once reviewed translations exist (Implementation Plan M14).
 */
export const SUPPORTED_LOCALES = [
  { code: 'en', name: 'English', nativeName: 'English', script: 'Latn', dir: 'ltr' },
  { code: 'am', name: 'Amharic', nativeName: 'አማርኛ', script: 'Ethi', dir: 'ltr' },
] as const;

export type Locale = (typeof SUPPORTED_LOCALES)[number]['code'];

export const DEFAULT_LOCALE: Locale = 'en';

export const LOCALE_CODES: readonly Locale[] = SUPPORTED_LOCALES.map((l) => l.code);

export function isSupportedLocale(value: string): value is Locale {
  return (LOCALE_CODES as readonly string[]).includes(value);
}

/**
 * Best supported locale for a list of BCP 47 tags in preference order
 * (e.g. from Accept-Language or expo-localization): exact match, then base language.
 */
export function resolveLocale(preferred: readonly string[], fallback: Locale = DEFAULT_LOCALE): Locale {
  for (const tag of preferred) {
    const normalized = tag.trim().toLowerCase();
    if (isSupportedLocale(normalized)) return normalized;
    const base = normalized.split(/[-_]/)[0] ?? '';
    if (isSupportedLocale(base)) return base;
  }
  return fallback;
}
