import type { EthiopianDate } from './convert';

/**
 * Month and weekday names for Ethiopian-calendar display.
 * Amharic spellings follow common traditional usage and need native-speaker
 * review before launch (variants exist, e.g. ኅዳር / ሕዳር, ታኅሣሥ / ታህሳስ).
 */
export const ETHIOPIAN_MONTHS = {
  am: ['መስከረም', 'ጥቅምት', 'ኅዳር', 'ታኅሣሥ', 'ጥር', 'የካቲት', 'መጋቢት', 'ሚያዝያ', 'ግንቦት', 'ሰኔ', 'ሐምሌ', 'ነሐሴ', 'ጳጉሜን'],
  en: ['Meskerem', 'Tikimt', 'Hidar', 'Tahsas', 'Tir', 'Yekatit', 'Megabit', 'Miyazya', 'Ginbot', 'Sene', 'Hamle', 'Nehase', 'Pagume'],
} as const;

/** Index 0 = Sunday, matching Date.prototype.getUTCDay(). */
export const ETHIOPIAN_WEEKDAYS = {
  am: ['እሑድ', 'ሰኞ', 'ማክሰኞ', 'ረቡዕ', 'ሐሙስ', 'ዓርብ', 'ቅዳሜ'],
  en: ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'],
} as const;

export type CalendarDisplayLanguage = keyof typeof ETHIOPIAN_MONTHS;

export type EthiopianDateStyle = 'long' | 'medium' | 'numeric';

export interface FormatEthiopianDateOptions {
  /** Name language. Other UI locales currently fall back to the English transliteration. */
  language?: string;
  style?: EthiopianDateStyle;
}

/** Era suffix: ዓ.ም. (Amete Mihret) / E.C. (Ethiopian Calendar). */
const ERA = { am: 'ዓ.ም.', en: 'E.C.' } as const;

export function ethiopianMonthName(month: number, language = 'en'): string {
  const names = ETHIOPIAN_MONTHS[displayLanguage(language)];
  const name = names[month - 1];
  if (!name) throw new RangeError(`Invalid Ethiopian month: ${month}`);
  return name;
}

/**
 * long:    "መስከረም 1 ቀን 2018 ዓ.ም." / "Meskerem 1, 2018 E.C."
 * medium:  "መስከረም 1, 2018" / "Meskerem 1, 2018"
 * numeric: "1/1/2018" (day/month/year, the common Ethiopian order)
 */
export function formatEthiopianDate(
  date: EthiopianDate,
  { language = 'en', style = 'medium' }: FormatEthiopianDateOptions = {},
): string {
  const lang = displayLanguage(language);
  if (style === 'numeric') return `${date.day}/${date.month}/${date.year}`;
  const month = ethiopianMonthName(date.month, lang);
  if (style === 'long') {
    return lang === 'am'
      ? `${month} ${date.day} ቀን ${date.year} ${ERA.am}`
      : `${month} ${date.day}, ${date.year} ${ERA.en}`;
  }
  return `${month} ${date.day}, ${date.year}`;
}

function displayLanguage(language: string): CalendarDisplayLanguage {
  return language.toLowerCase().startsWith('am') ? 'am' : 'en';
}
