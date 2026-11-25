"use client";

import { createContext, useCallback, useContext, useMemo } from "react";
import { useLocale } from "next-intl";
import { useLocalStorageValue } from "@/lib/use-local-storage";
import { formatCivilDate } from "@/lib/school/dates";
import {
  defaultCalendarFor,
  formatInstant,
  isCalendarPreference,
  type CalendarPreference,
} from "@/lib/format-instant";

export const CALENDAR_STORAGE_KEY = "school.calendar";

interface CalendarContextValue {
  calendar: CalendarPreference;
  setCalendar: (calendar: CalendarPreference) => void;
}

const CalendarContext = createContext<CalendarContextValue | null>(null);

export function CalendarPreferenceProvider({ children }: { children: React.ReactNode }) {
  const locale = useLocale();
  const [stored, store] = useLocalStorageValue(CALENDAR_STORAGE_KEY);
  const calendar = isCalendarPreference(stored) ? stored : defaultCalendarFor(locale);
  const value = useMemo(() => ({ calendar, setCalendar: store }), [calendar, store]);
  return <CalendarContext.Provider value={value}>{children}</CalendarContext.Provider>;
}

export function useCalendarPreference(): CalendarContextValue {
  const ctx = useContext(CalendarContext);
  if (!ctx) throw new Error("useCalendarPreference must be used within CalendarPreferenceProvider");
  return ctx;
}

/** Formats an ISO instant per the viewer's calendar preference and UI locale. */
export function useFormatInstant(): (instant: string | Date) => string {
  const locale = useLocale();
  const { calendar } = useCalendarPreference();
  return useCallback(
    (instant) => formatInstant(instant, { locale, calendar }),
    [locale, calendar],
  );
}

/** Formats a calendar date ("2026-09-01", no time zone) per the viewer's calendar preference and UI locale. */
export function useFormatDate(): (isoDate: string | null | undefined) => string {
  const locale = useLocale();
  const { calendar } = useCalendarPreference();
  return useCallback((isoDate) => formatCivilDate(isoDate, { locale, calendar }), [locale, calendar]);
}
