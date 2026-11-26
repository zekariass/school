"use client";

import { useLocale } from "next-intl";
import { useState } from "react";
import { useFormatInstant } from "@/components/calendar-preference";
import { formatRelativeTime } from "@/lib/notifications/relative-time";

/**
 * "2 hours ago" for recent instants with the full date as a tooltip. Rendered in the browser only (the text
 * depends on the clock), so the server render shows the full date.
 */
export function RelativeTime({ instant, className }: { instant: string; className?: string }) {
  const locale = useLocale();
  const format = useFormatInstant();
  const [now] = useState(() => Date.now());
  const full = format(instant);
  const ageMs = now - Date.parse(instant);
  const text = ageMs >= 0 && ageMs < 7 * 86_400_000 ? formatRelativeTime(instant, new Date(now), locale) : full;
  return (
    <time dateTime={instant} title={full} className={className} suppressHydrationWarning>
      {text}
    </time>
  );
}
