import { useCallback } from 'react';

import { formatInstant } from '@/lib/calendar/format-instant';
import { usePreferences } from '@/lib/preferences/preferences-provider';

/** Formats instants using the UI language and the user's calendar preference. */
export function useFormatInstant() {
  const { locale, calendar } = usePreferences();
  return useCallback((instant: string | Date) => formatInstant(instant, { locale, calendar }), [locale, calendar]);
}
