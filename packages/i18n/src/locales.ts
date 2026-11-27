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

export type LocaleScript = (typeof SUPPORTED_LOCALES)[number]['script'];

/** The script a UI language is written in ('Latn' for any language this list does not know), for choosing fonts and sizes. */
export function scriptOfLocale(code: string): LocaleScript {
  const base = code.trim().toLowerCase().split(/[-_]/)[0] ?? '';
  return SUPPORTED_LOCALES.find((l) => l.code === base)?.script ?? 'Latn';
}

/**
 * A language's name for a person reading `displayLocale`: `Intl.DisplayNames` when the runtime has it (Hermes may
 * not), else the native name of a shipped UI language, else the code itself. A language is data (the country's
 * `languageCodes`); this only names it.
 */
export function languageLabel(code: string, displayLocale: string): string {
  try {
    if (typeof Intl !== 'undefined' && typeof Intl.DisplayNames === 'function') {
      const name = new Intl.DisplayNames([displayLocale], { type: 'language' }).of(code);
      if (name && name !== code) return name;
    }
  } catch {
    // an unknown tag or a runtime without the data: fall through
  }
  return SUPPORTED_LOCALES.find((l) => l.code === code)?.nativeName ?? code;
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
