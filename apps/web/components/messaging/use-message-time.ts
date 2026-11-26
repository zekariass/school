"use client";

import { useLocale } from "next-intl";
import { useMemo } from "react";
import { useCalendarPreference } from "@/components/calendar-preference";
import { formatClock, formatDay, formatFull } from "@/lib/messaging/time";

/** Clock, day and full-date formatters on the school's time zone (not the browser's), in the viewer's calendar. */
export function useMessageTime(timeZone: string | undefined) {
  const locale = useLocale();
  const { calendar } = useCalendarPreference();
  return useMemo(() => {
    const options = { locale, calendar, timeZone };
    return {
      clock: (instant: string | Date) => formatClock(instant, options),
      day: (instant: string | Date) => formatDay(instant, options),
      full: (instant: string | Date) => formatFull(instant, options),
    };
  }, [locale, calendar, timeZone]);
}
