import { ETHIOPIAN_WEEKDAYS, ethiopianMonthName } from '@school/calendar';

import type { CalendarPreference } from './format-instant';

/**
 * Month and weekday names, from tables (not `Intl`): the same words on every phone, whatever its runtime knows about Amharic.
 * Ethiopian names come from `@school/calendar`. The Amharic Gregorian month spellings need a native speaker's review.
 */

const GREGORIAN_MONTHS = {
  en: ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'],
  am: ['ጃንዋሪ', 'ፌብሩዋሪ', 'ማርች', 'ኤፕሪል', 'ሜይ', 'ጁን', 'ጁላይ', 'ኦገስት', 'ሴፕቴምበር', 'ኦክቶበር', 'ኖቬምበር', 'ዲሴምበር'],
} as const;

function lang(locale: string): 'am' | 'en' {
  return locale.toLowerCase().startsWith('am') ? 'am' : 'en';
}

/** 1-based month name in the display calendar. */
export function monthName(calendar: CalendarPreference, month: number, locale: string): string {
  if (calendar === 'ethiopian') return ethiopianMonthName(month, lang(locale));
  return GREGORIAN_MONTHS[lang(locale)][month - 1] ?? '';
}

/** Index 0 = Sunday. */
export function weekdayName(weekday: number, locale: string): string {
  return ETHIOPIAN_WEEKDAYS[lang(locale)][((weekday % 7) + 7) % 7] ?? '';
}

/** A short column heading: three letters in English, two Ethiopic letters in Amharic (Ethiopic is one UTF-16 unit per letter). */
export function weekdayShort(weekday: number, locale: string): string {
  const name = weekdayName(weekday, locale);
  return lang(locale) === 'am' ? name.slice(0, 2) : name.slice(0, 3);
}

/** "November 2026" / "Hidar 2019" (the era is left out of a heading). */
export function monthTitle(calendar: CalendarPreference, view: { year: number; month: number }, locale: string): string {
  return `${monthName(calendar, view.month, locale)} ${view.year}`;
}
