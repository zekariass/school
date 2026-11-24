/**
 * Gregorian ↔ Ethiopian civil-date conversion via Julian Day Numbers.
 *
 * Display-only (spec §64): the platform stores instants as TIMESTAMPTZ and dates as
 * Gregorian DATE. Never persist Ethiopian dates.
 *
 * Ethiopian calendar: 12 months of 30 days + Pagume (5 days, 6 in a leap year).
 * A year Y is a leap year when Y % 4 === 3 (its Pagume has 6 days).
 * Epoch (Amete Mihret): 1 Meskerem 1 = JDN 1724221.
 */

/** A calendar date with no time or timezone. `month` is 1-based. */
export interface CivilDate {
  year: number;
  month: number;
  day: number;
}

export type GregorianDate = CivilDate;
export type EthiopianDate = CivilDate;

const ETHIOPIAN_EPOCH_JDN = 1724221;

export const PAGUME = 13;

export function isGregorianLeapYear(year: number): boolean {
  return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
}

export function daysInGregorianMonth(year: number, month: number): number {
  if (month === 2) return isGregorianLeapYear(year) ? 29 : 28;
  return [4, 6, 9, 11].includes(month) ? 30 : 31;
}

export function isEthiopianLeapYear(year: number): boolean {
  return mod(year, 4) === 3;
}

export function daysInEthiopianMonth(year: number, month: number): number {
  if (month === PAGUME) return isEthiopianLeapYear(year) ? 6 : 5;
  return 30;
}

export function isValidGregorianDate({ year, month, day }: GregorianDate): boolean {
  return (
    Number.isInteger(year) &&
    Number.isInteger(month) &&
    Number.isInteger(day) &&
    month >= 1 &&
    month <= 12 &&
    day >= 1 &&
    day <= daysInGregorianMonth(year, month)
  );
}

export function isValidEthiopianDate({ year, month, day }: EthiopianDate): boolean {
  return (
    Number.isInteger(year) &&
    Number.isInteger(month) &&
    Number.isInteger(day) &&
    year >= 1 &&
    month >= 1 &&
    month <= 13 &&
    day >= 1 &&
    day <= daysInEthiopianMonth(year, month)
  );
}

/** Proleptic Gregorian date → Julian Day Number (Fliegel & Van Flandern). */
export function gregorianToJdn({ year, month, day }: GregorianDate): number {
  const a = Math.floor((14 - month) / 12);
  const y = year + 4800 - a;
  const m = month + 12 * a - 3;
  return (
    day +
    Math.floor((153 * m + 2) / 5) +
    365 * y +
    Math.floor(y / 4) -
    Math.floor(y / 100) +
    Math.floor(y / 400) -
    32045
  );
}

/** Julian Day Number → proleptic Gregorian date. */
export function jdnToGregorian(jdn: number): GregorianDate {
  const a = jdn + 32044;
  const b = Math.floor((4 * a + 3) / 146097);
  const c = a - Math.floor((146097 * b) / 4);
  const d = Math.floor((4 * c + 3) / 1461);
  const e = c - Math.floor((1461 * d) / 4);
  const m = Math.floor((5 * e + 2) / 153);
  return {
    day: e - Math.floor((153 * m + 2) / 5) + 1,
    month: m + 3 - 12 * Math.floor(m / 10),
    year: 100 * b + d - 4800 + Math.floor(m / 10),
  };
}

export function ethiopianToJdn({ year, month, day }: EthiopianDate): number {
  return (
    ETHIOPIAN_EPOCH_JDN + 365 * (year - 1) + Math.floor(year / 4) + 30 * (month - 1) + day - 1
  );
}

export function jdnToEthiopian(jdn: number): EthiopianDate {
  // Count 4-year cycles from (non-leap) year 0, so each cycle is years 0,1,2,3 and its
  // leap day (Pagume 6 of year Y % 4 === 3) is the cycle's last day, which is what the
  // r / 1460 correction below assumes. Counting from year 1 would put the leap year
  // third in the cycle and shift dates around Pagume by one day.
  const offset = jdn - (ETHIOPIAN_EPOCH_JDN - 365);
  const r = mod(offset, 1461);
  const n = mod(r, 365) + 365 * Math.floor(r / 1460);
  return {
    year: 4 * Math.floor(offset / 1461) + Math.floor(r / 365) - Math.floor(r / 1460),
    month: Math.floor(n / 30) + 1,
    day: mod(n, 30) + 1,
  };
}

export function gregorianToEthiopian(date: GregorianDate): EthiopianDate {
  assertValid(isValidGregorianDate(date), 'Gregorian', date);
  return jdnToEthiopian(gregorianToJdn(date));
}

export function ethiopianToGregorian(date: EthiopianDate): GregorianDate {
  assertValid(isValidEthiopianDate(date), 'Ethiopian', date);
  return jdnToGregorian(ethiopianToJdn(date));
}

/**
 * The civil (Gregorian) date of an instant as seen in an IANA timezone,
 * e.g. civilDateInTimeZone(new Date(), 'Africa/Addis_Ababa').
 * Converting an instant always needs a timezone: 23:30 UTC is already
 * the next day in Addis Ababa (UTC+3).
 */
export function civilDateInTimeZone(instant: Date, timeZone: string): GregorianDate {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
  }).formatToParts(instant);
  const get = (type: Intl.DateTimeFormatPartTypes) =>
    Number(parts.find((p) => p.type === type)?.value);
  return { year: get('year'), month: get('month'), day: get('day') };
}

export function instantToEthiopian(instant: Date, timeZone: string): EthiopianDate {
  return gregorianToEthiopian(civilDateInTimeZone(instant, timeZone));
}

function mod(a: number, n: number): number {
  return ((a % n) + n) % n;
}

function assertValid(ok: boolean, calendar: string, date: CivilDate): void {
  if (!ok) {
    throw new RangeError(`Invalid ${calendar} date: ${date.year}-${date.month}-${date.day}`);
  }
}
