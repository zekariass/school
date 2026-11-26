/**
 * Times printed in a conversation: the time of day for today's messages, the short date and the time for older ones. Gregorian
 * `Intl` in an explicit time zone (the app's display zone, never the phone's by accident).
 */

import { DISPLAY_TIME_ZONE } from '@/lib/i18n/locale';

function dayKey(ms: number, timeZone: string): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(ms));
}

export function messageTimeText(instant: string | undefined, nowMs: number, locale: string, timeZone: string = DISPLAY_TIME_ZONE): string {
  if (!instant) return '';
  const ms = Date.parse(instant);
  if (Number.isNaN(ms)) return '';
  const time = new Intl.DateTimeFormat(locale, { timeStyle: 'short', timeZone }).format(new Date(ms));
  if (dayKey(ms, timeZone) === dayKey(nowMs, timeZone)) return time;
  const date = new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeZone }).format(new Date(ms));
  return `${date}, ${time}`;
}
