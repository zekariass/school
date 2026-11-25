import { useCallback } from 'react';

import { formatCivilDate, formatInstant } from '@/lib/calendar/format-instant';
import { usePreferences } from '@/lib/preferences/preferences-provider';

/** Formats instants using the UI language and the user's calendar preference. */
export function useFormatInstant() {
  const { locale, calendar } = usePreferences();
  return useCallback((instant: string | Date) => formatInstant(instant, { locale, calendar }), [locale, calendar]);
}

/** Formats plain dates (`YYYY-MM-DD`, such as a date of birth) in the user's calendar. */
export function useFormatCivilDate() {
  const { locale, calendar } = usePreferences();
  return useCallback((isoDate: string) => formatCivilDate(isoDate, { locale, calendar }), [locale, calendar]);
}
