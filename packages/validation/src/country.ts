import type { CountryCode } from 'libphonenumber-js';

/**
 * Where a screen takes its country, time zone, calendars and languages from (D42). Nothing here names a
 * country: every value comes from the API (`GET /meta`, `GET /me`, `GET /schools/by-slug`) and these pure
 * helpers only choose between what the API sent. What stays code is the calendar conversion
 * (`@school/calendar`); whether a country offers a calendar is data (`calendarCodes`).
 *
 * The types are structural, so the generated API types satisfy them without importing them here.
 */

export interface CountryConfigLike {
  code: string;
  name: string;
  phoneCountryCode: string;
  defaultTimezone: string;
  defaultLanguageCode: string;
  defaultCalendarCode: string;
  currencyCode: string;
  languageCodes: readonly string[];
  calendarCodes: readonly string[];
}

/** The calendars the apps can draw. A country may list others (data); those are simply not offered here. */
export type DisplayCalendar = 'gregorian' | 'ethiopian';

/** Canonical order of the display calendars (the order a toggle shows them in). */
export const DISPLAY_CALENDARS: readonly DisplayCalendar[] = ['gregorian', 'ethiopian'];

export function isDisplayCalendar(value: unknown): value is DisplayCalendar {
  return value === 'gregorian' || value === 'ethiopian';
}

/** `GREGORIAN` to `gregorian`; null for a code this app cannot draw (or an absent one). */
export function toDisplayCalendar(code: string | null | undefined): DisplayCalendar | null {
  const lower = code?.trim().toLowerCase();
  return isDisplayCalendar(lower) ? lower : null;
}

/**
 * The calendars to offer where a country's `calendarCodes` apply. The Gregorian calendar is always offered
 * (it is the civil basis of every stored date), the others only when the country has them.
 */
export function offeredCalendars(calendarCodes: readonly string[] | null | undefined): DisplayCalendar[] {
  const wanted = new Set((calendarCodes ?? []).map(toDisplayCalendar));
  return DISPLAY_CALENDARS.filter((c) => c === 'gregorian' || wanted.has(c));
}

/**
 * The calendar to show. In order: the choice this device saved, the account's `calendarPreference`, the
 * school's or country's default calendar; the first that is one of `offered`, else Gregorian. Never
 * Ethiopian where the country does not have it, whatever was saved before.
 */
export function resolveDisplayCalendar(input: {
  stored?: string | null;
  preference?: string | null;
  defaultCode?: string | null;
  offered: readonly DisplayCalendar[];
}): DisplayCalendar {
  for (const candidate of [input.stored, input.preference, input.defaultCode]) {
    const calendar = toDisplayCalendar(candidate);
    if (calendar && input.offered.includes(calendar)) return calendar;
  }
  return 'gregorian';
}

/** What a screen needs to show dates and offer languages: the zone, the calendars and the country's languages. */
export interface DisplayPlace {
  countryCode: string;
  /** IANA zone dates and times are shown in. */
  timeZone: string;
  /** The calendar shown until the person chooses (school/country default, limited to `calendars`). */
  defaultCalendar: DisplayCalendar;
  /** The calendars to offer; never empty. */
  calendars: readonly DisplayCalendar[];
  languageCodes: readonly string[];
  /** The language a new text starts in (the country's default). */
  defaultLanguageCode: string;
  /** The calling code with its plus sign, for a phone field's hint. */
  phoneCountryCode: string;
}

function placeOf(country: CountryConfigLike, timeZone: string, defaultCalendarCode: string): DisplayPlace {
  const calendars = offeredCalendars(country.calendarCodes);
  return {
    countryCode: country.code,
    timeZone,
    calendars,
    defaultCalendar: resolveDisplayCalendar({ defaultCode: defaultCalendarCode, offered: calendars }),
    languageCodes: country.languageCodes,
    defaultLanguageCode: country.defaultLanguageCode,
    phoneCountryCode: country.phoneCountryCode,
  };
}

/** A country's own defaults: before sign-in (the platform default country from `/meta`), or where no school is meant. */
export function placeForCountry(country: CountryConfigLike): DisplayPlace {
  return placeOf(country, country.defaultTimezone, country.defaultCalendarCode);
}

/** The signed-in person's place outside a school: their zone if they set one, else their country's. */
export function placeForUser(me: { timezone?: string | null; country: CountryConfigLike }): DisplayPlace {
  return placeOf(me.country, me.timezone || me.country.defaultTimezone, me.country.defaultCalendarCode);
}

/** A school's place: the school's own zone and calendar default, the languages of its country. */
export function placeForSchool(school: {
  timezone: string;
  defaultCalendarCode: string;
  country: CountryConfigLike;
}): DisplayPlace {
  return placeOf(school.country, school.timezone, school.defaultCalendarCode);
}

/** The country with this code among those the API sent. */
export function findCountry<T extends { code: string }>(countries: readonly T[], code: string | null | undefined): T | undefined {
  return code ? countries.find((c) => c.code === code) : undefined;
}

/**
 * A school the person belongs to, for places that only have `/me`: the membership has the school's zone and
 * country code, `me.countries` has that country's configuration. Null when `me.countries` lacks it.
 */
export function placeForMembership(
  me: { countries: readonly CountryConfigLike[] },
  membership: { timezone: string; countryCode: string },
): DisplayPlace | null {
  const country = findCountry(me.countries, membership.countryCode);
  return country ? placeOf(country, membership.timezone, country.defaultCalendarCode) : null;
}

// --- phone country (sign-in, invitations) ---------------------------------------------------------------------------

/**
 * The country a phone number is read in before sign-in: the person's choice when it is an enabled country,
 * else the platform default, else the first enabled one. Undefined when `/meta` has no countries at all.
 */
export function pickPhoneCountry<T extends { code: string }>(
  countries: readonly T[],
  defaultCode: string | null | undefined,
  selected?: string | null,
): T | undefined {
  return findCountry(countries, selected) ?? findCountry(countries, defaultCode) ?? countries[0];
}

/** A country picker is shown only when there is something to pick (a number with a "+" needs none). */
export function needsCountryPicker(countries: readonly unknown[]): boolean {
  return countries.length > 1;
}

const COUNTRY_CODE = /^[A-Z]{2}$/;

/** An ISO 3166-1 alpha-2 code as the library's type, or undefined for anything else. */
export function asCountryCode(code: string | null | undefined): CountryCode | undefined {
  return code && COUNTRY_CODE.test(code) ? (code as CountryCode) : undefined;
}

// --- everything a signed-in person needs, from `GET /me` -------------------------------------------------------------

/** The part of `GET /me` that says where a person's dates are shown. */
export interface MeForPlaces {
  timezone?: string | null | undefined;
  calendarPreference?: string | null | undefined;
  country: CountryConfigLike;
  countries: readonly CountryConfigLike[];
  memberships: readonly { schoolId: string; timezone: string; countryCode: string }[];
}

export interface UserPlaces {
  /** The person's own place: their zone if they set one, else their country's. */
  place: DisplayPlace;
  /** The place of each school the person belongs to, by school id. */
  schools: Record<string, DisplayPlace>;
  /** The account's calendar preference (an API code), if set. */
  preference: string | null;
}

/** Everything the display layers need, from `/me` alone: no constant, nothing guessed. */
export function userPlaces(me: MeForPlaces): UserPlaces {
  const schools: Record<string, DisplayPlace> = {};
  for (const membership of me.memberships) {
    const place = placeForMembership(me, membership);
    if (place) schools[membership.schoolId] = place;
  }
  return { place: placeForUser(me), schools, preference: me.calendarPreference || null };
}
