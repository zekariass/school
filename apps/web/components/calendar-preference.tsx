"use client";

import { createContext, useCallback, useContext, useMemo } from "react";
import { useLocale } from "next-intl";
import { resolveDisplayCalendar } from "@school/validation/country";
import { useCalendarAccountPreference, useDisplayPlace } from "@/components/display-context";
import { useLocalStorageValue } from "@/lib/use-local-storage";
import { formatCivilDate } from "@/lib/school/dates";
import { formatInstant, type CalendarPreference } from "@/lib/format-instant";

export const CALENDAR_STORAGE_KEY = "school.calendar";

interface CalendarContextValue {
  /** The calendar shown: this device's saved choice, else the account's preference, else the school's or country's default (D42). */
  calendar: CalendarPreference;
  /** The calendars to offer: Gregorian, and the others only where the school's or country's `calendarCodes` have them. */
  calendars: readonly CalendarPreference[];
  setCalendar: (calendar: CalendarPreference) => void;
}

const CalendarContext = createContext<CalendarContextValue | null>(null);

/** Needs the display place (see `display-place.tsx`) above it. The only thing stored in the browser is the display choice. */
export function CalendarPreferenceProvider({ children }: { children: React.ReactNode }) {
  const place = useDisplayPlace();
  const accountPreference = useCalendarAccountPreference();
  const [stored, store] = useLocalStorageValue(CALENDAR_STORAGE_KEY);
  const calendars = useMemo<readonly CalendarPreference[]>(() => place?.calendars ?? ["gregorian"], [place]);
  const calendar = resolveDisplayCalendar({
    stored,
    preference: accountPreference,
    defaultCode: place?.defaultCalendar,
    offered: calendars,
  });
  const value = useMemo(() => ({ calendar, calendars, setCalendar: store }), [calendar, calendars, store]);
  return <CalendarContext.Provider value={value}>{children}</CalendarContext.Provider>;
}

export function useCalendarPreference(): CalendarContextValue {
  const ctx = useContext(CalendarContext);
  if (!ctx) throw new Error("useCalendarPreference must be used within CalendarPreferenceProvider");
  return ctx;
}

/**
 * Formats an ISO instant per the viewer's calendar and UI locale, on the clock of the school (in a workspace)
 * or of the person. `timeZone` overrides it for a screen that knows better (a child's school).
 */
export function useFormatInstant(timeZone?: string): (instant: string | Date) => string {
  const locale = useLocale();
  const { calendar } = useCalendarPreference();
  const placeZone = useDisplayPlace()?.timeZone;
  const zone = timeZone ?? placeZone;
  return useCallback((instant) => formatInstant(instant, { locale, calendar, timeZone: zone }), [locale, calendar, zone]);
}

/** Formats a calendar date ("2026-09-01", no time zone) per the viewer's calendar preference and UI locale. */
export function useFormatDate(): (isoDate: string | null | undefined) => string {
  const locale = useLocale();
  const { calendar } = useCalendarPreference();
  return useCallback((isoDate) => formatCivilDate(isoDate, { locale, calendar }), [locale, calendar]);
}
