import { useQuery } from '@tanstack/react-query';
import { createContext, useContext, useMemo, type ReactNode } from 'react';

import {
  findCountry,
  placeForCountry,
  resolveDisplayCalendar,
  userPlaces,
  type DisplayPlace,
} from '@school/validation/country';

import { loadMe } from '@/lib/auth/auth-requests';
import { ME_KEY } from '@/lib/auth/use-me';
import { PreferencesContext, usePreferences } from '@/lib/preferences/preferences-provider';
import { useSession } from '@/lib/session/session-provider';

import { countriesQuery } from './queries';

/**
 * Where dates and languages come from (D42): the signed-in person's own place (their zone, their country's calendars
 * and languages), the zone and calendars of each school they belong to, and before sign-in the platform default
 * country from `GET /meta`. Nothing here is a constant: every value was sent by the API.
 */
interface PlaceValue {
  place: DisplayPlace | null;
  schools: Readonly<Record<string, DisplayPlace>>;
}

const PlaceContext = createContext<PlaceValue>({ place: null, schools: {} });

/**
 * Below the session and the query cache. It also replaces the preferences' `calendar` and `calendars`: the saved choice
 * of this device, else the account's `calendarPreference`, else the default of the place, limited to the calendars the
 * country offers (so Ethiopian is never offered where the country does not have it). Every screen keeps reading
 * `usePreferences()`.
 */
export function PlaceProvider({ children }: { children: ReactNode }) {
  const preferences = usePreferences();
  const { status } = useSession();
  const me = useQuery({ queryKey: ME_KEY, queryFn: loadMe, enabled: status === 'signedIn' });
  const countries = useQuery(countriesQuery);

  const value = useMemo(() => {
    const mine = me.data ? userPlaces(me.data) : null;
    const defaultCountry = countries.data ? findCountry(countries.data.countries, countries.data.defaultCountry) : undefined;
    const place = mine?.place ?? (defaultCountry ? placeForCountry(defaultCountry) : null);
    return { place, schools: mine?.schools ?? {}, preference: mine?.preference ?? null };
  }, [me.data, countries.data]);

  const placeValue = useMemo<PlaceValue>(() => ({ place: value.place, schools: value.schools }), [value]);
  const calendars = value.place?.calendars ?? preferences.calendars;
  const calendar = resolveDisplayCalendar({
    stored: preferences.savedCalendar,
    preference: value.preference,
    defaultCode: value.place?.defaultCalendar,
    offered: calendars,
  });
  const nested = useMemo(
    () => ({ ...preferences, calendar, calendars }),
    [preferences, calendar, calendars],
  );

  return (
    <PlaceContext.Provider value={placeValue}>
      <PreferencesContext.Provider value={nested}>{children}</PreferencesContext.Provider>
    </PlaceContext.Provider>
  );
}

/** The person's place (null until `/me` or `/meta` has answered). */
export function usePlace(): DisplayPlace | null {
  return useContext(PlaceContext).place;
}

/** The place of one of the person's schools, else the person's own. */
export function usePlaceOfSchool(schoolId: string | null | undefined): DisplayPlace | null {
  const { place, schools } = useContext(PlaceContext);
  return (schoolId ? schools[schoolId] : undefined) ?? place;
}

/**
 * The zone to show times in when no school is meant: the person's, else the platform default country's. Falls to UTC
 * only while nothing is known yet (a stated zone, not a guess about the country).
 */
export function useDisplayZone(): string {
  return usePlace()?.timeZone ?? 'UTC';
}
