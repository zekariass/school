"use client";

import { createContext, useContext } from "react";
import type { DisplayPlace } from "@school/validation/country";

/**
 * Where dates and languages come from (D42): the school's zone and calendars in a workspace, the person's own
 * place elsewhere when signed in, the platform default country's on public pages. Nothing here is a constant:
 * every value was sent by the API (`GET /me`, the school summary, `GET /meta`). The providers are in `display-place.tsx`.
 */
export interface DisplayValue {
  /** The place that applies to this page; null on a public page until `/meta` has answered. */
  place: DisplayPlace | null;
  /** The places of the schools the person belongs to, by school id (a guardian's children's schools). */
  schools: Readonly<Record<string, DisplayPlace>>;
  /** The account's `calendarPreference` (an API calendar code), if set. */
  preference: string | null;
}

export const EMPTY_DISPLAY: DisplayValue = { place: null, schools: {}, preference: null };

export const DisplayContext = createContext<DisplayValue>(EMPTY_DISPLAY);

export function useDisplayPlace(): DisplayPlace | null {
  return useContext(DisplayContext).place;
}

export function useCalendarAccountPreference(): string | null {
  return useContext(DisplayContext).preference;
}

/** The place of one of the person's schools (null when unknown), for screens that show a child's school's dates. */
export function useSchoolPlace(schoolId: string | null | undefined): DisplayPlace | null {
  const { schools } = useContext(DisplayContext);
  return (schoolId ? schools[schoolId] : undefined) ?? null;
}
