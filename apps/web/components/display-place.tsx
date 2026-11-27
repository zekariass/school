"use client";

import { useQuery } from "@tanstack/react-query";
import { useContext, useMemo } from "react";
import { findCountry, placeForCountry, type DisplayPlace } from "@school/validation/country";
import { CalendarPreferenceProvider } from "@/components/calendar-preference";
import { DisplayContext, type DisplayValue } from "@/components/display-context";
import { metaQuery } from "@/lib/meta-query";

/** Each provider also sets up the calendar choice for the place it provides (the calendars offered depend on it). */
function Provide({ value, children }: { value: DisplayValue; children: React.ReactNode }) {
  return (
    <DisplayContext.Provider value={value}>
      <CalendarPreferenceProvider>{children}</CalendarPreferenceProvider>
    </DisplayContext.Provider>
  );
}

/** Public pages: the platform default country, read from `GET /meta` (public, no session, nothing personal). Needs a QueryClientProvider. */
export function PlatformPlaceProvider({ children }: { children: React.ReactNode }) {
  const meta = useQuery(metaQuery);
  const place = useMemo(() => {
    const data = meta.data;
    const country = data ? findCountry(data.countries, data.defaultCountry) : undefined;
    return country ? placeForCountry(country) : null;
  }, [meta.data]);
  const value = useMemo<DisplayValue>(() => ({ place, schools: {}, preference: null }), [place]);
  return <Provide value={value}>{children}</Provide>;
}

/** Signed-in pages: the person's place, their schools' places and calendar preference (computed on the server from `/me`). */
export function UserPlaceProvider({
  place,
  schools,
  preference,
  children,
}: {
  place: DisplayPlace;
  schools: Record<string, DisplayPlace>;
  preference: string | null;
  children: React.ReactNode;
}) {
  const value = useMemo<DisplayValue>(() => ({ place, schools, preference }), [place, schools, preference]);
  return <Provide value={value}>{children}</Provide>;
}

/** A school workspace: the school's own zone and calendar default replace the person's. */
export function SchoolPlaceProvider({ place, children }: { place: DisplayPlace; children: React.ReactNode }) {
  const parent = useContext(DisplayContext);
  const value = useMemo<DisplayValue>(() => ({ ...parent, place }), [parent, place]);
  return <Provide value={value}>{children}</Provide>;
}
