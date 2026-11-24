import { getLocales } from 'expo-localization';
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';

import type { Locale } from '@school/i18n';

import {
  CALENDAR_STORAGE_KEY,
  defaultCalendarFor,
  isCalendarPreference,
  type CalendarPreference,
} from '@/lib/calendar/format-instant';
import { LOCALE_STORAGE_KEY, pickInitialLocale, setCurrentLocale } from '@/lib/i18n/locale';
import { kv, saveQuietly } from '@/lib/storage';
import { THEME_STORAGE_KEY, isThemePreference, type ThemePreference } from '@/lib/theme/preference';

export interface StoredPreferences {
  locale: Locale;
  theme: ThemePreference;
  /** Null until the user picks one: the default then follows the language. */
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
  /** Effective calendar: the saved choice, else the default for the language. */
  calendar: CalendarPreference;
  setCalendar: (calendar: CalendarPreference) => void;
}

const PreferencesContext = createContext<PreferencesValue | null>(null);

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
      calendar: savedCalendar ?? defaultCalendarFor(locale),
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
