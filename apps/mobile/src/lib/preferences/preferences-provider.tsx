import { getLocales } from 'expo-localization';
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';

import type { Locale } from '@school/i18n';

import { resolveDisplayCalendar } from '@school/validation/country';

import { CALENDAR_STORAGE_KEY, isCalendarPreference, type CalendarPreference } from '@/lib/calendar/format-instant';
import { LOCALE_STORAGE_KEY, pickInitialLocale, setCurrentLocale } from '@/lib/i18n/locale';
import { kv, saveQuietly } from '@/lib/storage';
import { THEME_STORAGE_KEY, isThemePreference, type ThemePreference } from '@/lib/theme/preference';

export interface StoredPreferences {
  locale: Locale;
  theme: ThemePreference;
  /** Null until the user picks one: the default then comes from the school's or country's configuration (D42). */
  calendar: CalendarPreference | null;
}

export const DEFAULT_PREFERENCES: StoredPreferences = { locale: 'en', theme: 'system', calendar: null };

/**
 * Reads the persisted preferences. Language: saved choice, else the device language,
 * else English.
 */
export async function loadPreferences(): Promise<StoredPreferences> {
  try {
    const [savedLocale, savedTheme, savedCalendar] = await Promise.all([
      kv.getItem(LOCALE_STORAGE_KEY),
      kv.getItem(THEME_STORAGE_KEY),
      kv.getItem(CALENDAR_STORAGE_KEY),
    ]);
    const locale = pickInitialLocale(
      savedLocale,
      getLocales().map((l) => l.languageTag),
    );
    setCurrentLocale(locale); // for the API client's Accept-Language header
    return {
      locale,
      theme: isThemePreference(savedTheme) ? savedTheme : 'system',
      calendar: isCalendarPreference(savedCalendar) ? savedCalendar : null,
    };
  } catch (error) {
    if (__DEV__) console.warn('[preferences] could not load, using defaults', error);
    return DEFAULT_PREFERENCES;
  }
}

interface PreferencesValue {
  locale: Locale;
  setLocale: (locale: Locale) => void;
  themePreference: ThemePreference;
  setThemePreference: (preference: ThemePreference) => void;
  /**
   * Effective calendar: this device's saved choice, else the account's preference, else the default of the school's or
   * country's configuration, always one of `calendars`. The provider below the session (`PlaceProvider`) fills in the
   * place; above it only Gregorian is offered.
   */
  calendar: CalendarPreference;
  /** The calendars to offer: Gregorian, and Ethiopian only where the country has it (`calendarCodes`). */
  calendars: readonly CalendarPreference[];
  /** The choice saved on this device, if any. */
  savedCalendar: CalendarPreference | null;
  setCalendar: (calendar: CalendarPreference) => void;
}

export const PreferencesContext = createContext<PreferencesValue | null>(null);

const GREGORIAN_ONLY: readonly CalendarPreference[] = ['gregorian'];

export function PreferencesProvider({ initial, children }: { initial: StoredPreferences; children: ReactNode }) {
  const [locale, setLocaleState] = useState(initial.locale);
  const [themePreference, setThemeState] = useState(initial.theme);
  const [savedCalendar, setSavedCalendar] = useState(initial.calendar);

  const setLocale = useCallback((next: Locale) => {
    setCurrentLocale(next);
    setLocaleState(next);
    saveQuietly(LOCALE_STORAGE_KEY, next);
  }, []);
  const setThemePreference = useCallback((next: ThemePreference) => {
    setThemeState(next);
    saveQuietly(THEME_STORAGE_KEY, next);
  }, []);
  const setCalendar = useCallback((next: CalendarPreference) => {
    setSavedCalendar(next);
    saveQuietly(CALENDAR_STORAGE_KEY, next);
  }, []);

  const value = useMemo<PreferencesValue>(
    () => ({
      locale,
      setLocale,
      themePreference,
      setThemePreference,
      calendar: resolveDisplayCalendar({ stored: savedCalendar, offered: GREGORIAN_ONLY }),
      calendars: GREGORIAN_ONLY,
      savedCalendar,
      setCalendar,
    }),
    [locale, setLocale, themePreference, setThemePreference, savedCalendar, setCalendar],
  );

  return <PreferencesContext.Provider value={value}>{children}</PreferencesContext.Provider>;
}

export function usePreferences(): PreferencesValue {
  const value = useContext(PreferencesContext);
  if (!value) throw new Error('usePreferences must be used inside <PreferencesProvider>');
  return value;
}
