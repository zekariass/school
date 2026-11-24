import { isSupportedLocale, resolveLocale, type Locale } from '@school/i18n';

/** kv-store key of the saved UI language. */
export const LOCALE_STORAGE_KEY = 'school.locale';

/** Placeholder until the user's / school's time zone comes from settings. */
export const DISPLAY_TIME_ZONE = 'Africa/Addis_Ababa';

/**
 * Initial UI language: the saved preference if it is still a supported locale,
 * otherwise the best match for the device's language list, otherwise English.
 */
export function pickInitialLocale(saved: string | null | undefined, deviceLanguageTags: readonly string[]): Locale {
  if (saved && isSupportedLocale(saved)) return saved;
  return resolveLocale(deviceLanguageTags);
}

let currentLocale: Locale = 'en';

/** The active UI locale, readable outside React (API client `Accept-Language`). */
export function getCurrentLocale(): Locale {
  return currentLocale;
}

export function setCurrentLocale(locale: Locale): void {
  currentLocale = locale;
}
