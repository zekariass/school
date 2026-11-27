import { describe, expect, it } from 'vitest';

import {
  asCountryCode,
  findCountry,
  needsCountryPicker,
  offeredCalendars,
  pickPhoneCountry,
  placeForCountry,
  placeForMembership,
  placeForSchool,
  placeForUser,
  resolveDisplayCalendar,
  userPlaces,
  toDisplayCalendar,
  type CountryConfigLike,
  type MeForPlaces,
} from './country';
import { phoneSchema } from './index';

// Invented configurations: the helpers must work for any country the API sends.
const ALPHA: CountryConfigLike = {
  code: 'ET',
  name: 'Alpha',
  phoneCountryCode: '+251',
  defaultTimezone: 'Africa/Addis_Ababa',
  defaultLanguageCode: 'am',
  defaultCalendarCode: 'ETHIOPIAN',
  currencyCode: 'ETB',
  languageCodes: ['am', 'en'],
  calendarCodes: ['ETHIOPIAN', 'GREGORIAN'],
};
const BETA: CountryConfigLike = {
  code: 'KE',
  name: 'Beta',
  phoneCountryCode: '+254',
  defaultTimezone: 'Africa/Nairobi',
  defaultLanguageCode: 'sw',
  defaultCalendarCode: 'GREGORIAN',
  currencyCode: 'KES',
  languageCodes: ['en', 'sw'],
  calendarCodes: ['GREGORIAN'],
};

describe('calendars', () => {
  it('maps API codes to display calendars and ignores what the apps cannot draw', () => {
    expect(toDisplayCalendar('ETHIOPIAN')).toBe('ethiopian');
    expect(toDisplayCalendar('gregorian')).toBe('gregorian');
    expect(toDisplayCalendar('HIJRI')).toBeNull();
    expect(toDisplayCalendar(undefined)).toBeNull();
  });

  it('always offers Gregorian and Ethiopian only where the country has it', () => {
    expect(offeredCalendars(ALPHA.calendarCodes)).toEqual(['gregorian', 'ethiopian']);
    expect(offeredCalendars(BETA.calendarCodes)).toEqual(['gregorian']);
    expect(offeredCalendars(['ETHIOPIAN'])).toEqual(['gregorian', 'ethiopian']);
    expect(offeredCalendars([])).toEqual(['gregorian']);
    expect(offeredCalendars(undefined)).toEqual(['gregorian']);
  });

  it('prefers the saved choice, then the account preference, then the default, within what is offered', () => {
    const both = offeredCalendars(ALPHA.calendarCodes);
    expect(resolveDisplayCalendar({ stored: 'gregorian', preference: 'ETHIOPIAN', defaultCode: 'ETHIOPIAN', offered: both })).toBe('gregorian');
    expect(resolveDisplayCalendar({ stored: null, preference: 'ETHIOPIAN', defaultCode: 'GREGORIAN', offered: both })).toBe('ethiopian');
    expect(resolveDisplayCalendar({ defaultCode: 'ETHIOPIAN', offered: both })).toBe('ethiopian');
    expect(resolveDisplayCalendar({ offered: both })).toBe('gregorian');
  });

  it('never shows a calendar the country does not have, whatever was saved', () => {
    const only = offeredCalendars(BETA.calendarCodes);
    expect(resolveDisplayCalendar({ stored: 'ethiopian', preference: 'ETHIOPIAN', defaultCode: 'ETHIOPIAN', offered: only })).toBe('gregorian');
    expect(resolveDisplayCalendar({ stored: 'ethiopian', preference: 'GREGORIAN', offered: only })).toBe('gregorian');
  });
});

describe('places', () => {
  it('a country gives its own zone, calendars and languages', () => {
    expect(placeForCountry(ALPHA)).toEqual({
      countryCode: 'ET',
      timeZone: 'Africa/Addis_Ababa',
      calendars: ['gregorian', 'ethiopian'],
      defaultCalendar: 'ethiopian',
      languageCodes: ['am', 'en'],
      defaultLanguageCode: 'am',
      phoneCountryCode: '+251',
    });
    expect(placeForCountry(BETA).defaultCalendar).toBe('gregorian');
  });

  it('a user uses their own zone, else the zone of their country', () => {
    expect(placeForUser({ timezone: 'Europe/Paris', country: BETA }).timeZone).toBe('Europe/Paris');
    expect(placeForUser({ country: BETA }).timeZone).toBe('Africa/Nairobi');
    expect(placeForUser({ timezone: '', country: BETA }).timeZone).toBe('Africa/Nairobi');
  });

  it('a school uses its own zone and default calendar, and the languages of its country', () => {
    const place = placeForSchool({ timezone: 'Africa/Mombasa', defaultCalendarCode: 'GREGORIAN', country: ALPHA });
    expect(place.timeZone).toBe('Africa/Mombasa');
    expect(place.defaultCalendar).toBe('gregorian');
    expect(place.calendars).toEqual(['gregorian', 'ethiopian']);
    expect(place.languageCodes).toEqual(['am', 'en']);
  });

  it('a school default the country does not offer falls back to Gregorian', () => {
    expect(placeForSchool({ timezone: 'UTC', defaultCalendarCode: 'ETHIOPIAN', country: BETA }).defaultCalendar).toBe('gregorian');
  });

  it('a membership is resolved through the countries the API sent with /me', () => {
    const me = { countries: [ALPHA, BETA] };
    expect(placeForMembership(me, { timezone: 'Africa/Nairobi', countryCode: 'KE' })?.calendars).toEqual(['gregorian']);
    expect(placeForMembership(me, { timezone: 'UTC', countryCode: 'ZZ' })).toBeNull();
  });
});

describe('phone country before sign-in', () => {
  const countries = [ALPHA, BETA];

  it('uses the selection when it is enabled, else the platform default, else the first', () => {
    expect(pickPhoneCountry(countries, 'KE', 'ET')?.code).toBe('ET');
    expect(pickPhoneCountry(countries, 'KE')?.code).toBe('KE');
    expect(pickPhoneCountry(countries, 'KE', 'ZZ')?.code).toBe('KE');
    expect(pickPhoneCountry(countries, 'ZZ')?.code).toBe('ET');
    expect(pickPhoneCountry([], 'KE')).toBeUndefined();
  });

  it('asks for a country only when there is a choice', () => {
    expect(needsCountryPicker([])).toBe(false);
    expect(needsCountryPicker([ALPHA])).toBe(false);
    expect(needsCountryPicker(countries)).toBe(true);
  });

  it('reads the national number in the chosen country, and a + number in its own', () => {
    const picked = pickPhoneCountry(countries, 'KE', 'KE');
    const code = asCountryCode(picked?.code);
    expect(code).toBe('KE');
    expect(phoneSchema(code!).parse('0712 345678')).toBe('+254712345678');
    expect(phoneSchema(code!).parse('+251911234567')).toBe('+251911234567');
  });

  it('accepts only two capital letters as a country code', () => {
    expect(asCountryCode('KE')).toBe('KE');
    expect(asCountryCode('ke')).toBeUndefined();
    expect(asCountryCode('KEN')).toBeUndefined();
    expect(asCountryCode(undefined)).toBeUndefined();
  });

  it('finds a country by code', () => {
    expect(findCountry([ALPHA, BETA], 'KE')?.name).toBe('Beta');
    expect(findCountry([ALPHA], null)).toBeUndefined();
  });
});

function me(overrides: Partial<MeForPlaces> = {}): MeForPlaces {
  return { country: BETA, countries: [BETA, ALPHA], memberships: [], ...overrides };
}

describe('userPlaces', () => {
  it('uses the person\'s zone, else the zone of their country, and their calendar preference', () => {
    expect(userPlaces(me()).place.timeZone).toBe('Africa/Nairobi');
    expect(userPlaces(me({ timezone: 'Europe/Paris' })).place.timeZone).toBe('Europe/Paris');
    expect(userPlaces(me({ calendarPreference: 'GREGORIAN' })).preference).toBe('GREGORIAN');
    expect(userPlaces(me()).preference).toBeNull();
  });

  it('gives every school its own zone and the calendars of its country', () => {
    const places = userPlaces(
      me({
        memberships: [
          { schoolId: 's1', timezone: 'Africa/Addis_Ababa', countryCode: 'ET' },
          { schoolId: 's2', timezone: 'Africa/Nairobi', countryCode: 'KE' },
        ],
      }),
    );
    expect(places.schools.s1?.calendars).toEqual(['gregorian', 'ethiopian']);
    expect(places.schools.s1?.timeZone).toBe('Africa/Addis_Ababa');
    expect(places.schools.s2?.calendars).toEqual(['gregorian']);
    expect(places.schools.s2?.languageCodes).toEqual(['en', 'sw']);
  });

  it('skips a school whose country the API did not send', () => {
    const places = userPlaces(me({ memberships: [{ schoolId: 's9', timezone: 'UTC', countryCode: 'ZZ' }] }));
    expect(places.schools.s9).toBeUndefined();
  });
});
