import { useCallback } from 'react';

import { formatCivilDate, formatInstant } from '@/lib/calendar/format-instant';
import { usePlace } from '@/lib/country/place';
import { usePreferences } from '@/lib/preferences/preferences-provider';

/**
 * Formats instants using the UI language and the user's calendar, on the clock of the person's place (their zone,
 * else their country's, D42). `timeZone` overrides it for a screen that knows better (a child's school).
 */
export function useFormatInstant(timeZone?: string) {
  const { locale, calendar } = usePreferences();
  const placeZone = usePlace()?.timeZone;
  const zone = timeZone ?? placeZone;
  return useCallback((instant: string | Date) => formatInstant(instant, { locale, calendar, timeZone: zone }), [locale, calendar, zone]);
}

/** Formats plain dates (`YYYY-MM-DD`, such as a date of birth) in the user's calendar. */
export function useFormatCivilDate() {
  const { locale, calendar } = usePreferences();
  return useCallback((isoDate: string) => formatCivilDate(isoDate, { locale, calendar }), [locale, calendar]);
}
