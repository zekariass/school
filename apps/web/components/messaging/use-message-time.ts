"use client";

import { useLocale } from "next-intl";
import { useMemo } from "react";
import { useCalendarPreference } from "@/components/calendar-preference";
import { useDisplayPlace } from "@/components/display-context";
import { formatClock, formatDay, formatFull } from "@/lib/messaging/time";

/**
 * Clock, day and full-date formatters on the school's time zone (the messaging capabilities', else the workspace's),
 * in the viewer's calendar.
 */
export function useMessageTime(capabilitiesZone: string | undefined) {
  const locale = useLocale();
  const { calendar } = useCalendarPreference();
  const placeZone = useDisplayPlace()?.timeZone;
  const timeZone = capabilitiesZone ?? placeZone;
  return useMemo(() => {
    const options = { locale, calendar, timeZone };
    return {
      clock: (instant: string | Date) => formatClock(instant, options),
      day: (instant: string | Date) => formatDay(instant, options),
      full: (instant: string | Date) => formatFull(instant, options),
    };
  }, [locale, calendar, timeZone]);
}
