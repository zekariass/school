import type { components } from "@school/api-client";

type S = components["schemas"];

export type EventCapabilities = S["CalendarCapabilities"];
export type EventScope = "PERSONAL" | "SCHOOL" | "CAMPUS" | "GRADE_LEVEL" | "CLASS" | "PARENT_MEETING";
export type EventType = NonNullable<S["CalendarEventRequest"]["eventType"]>;

/**
 * What the composer offers, from `GET /schools/{id}/events/capabilities` (for menus only: the API checks every request).
 * A person's own (personal) events need no school and no permission.
 */

export const SCHOOL_WIDE_SCOPES: readonly EventScope[] = ["SCHOOL", "CAMPUS", "GRADE_LEVEL"];

/** The scopes a caller may pick in a school, in display order. Personal is not a school scope. */
export function schoolScopes(caps: EventCapabilities | null | undefined): EventScope[] {
  if (!caps || caps.calendarEnabled === false) return [];
  const out: EventScope[] = [];
  if (caps.canCreateSchoolEvents) out.push(...SCHOOL_WIDE_SCOPES);
  if (caps.canCreateAnyClassEvent || (caps.classes?.length ?? 0) > 0) out.push("CLASS");
  if (caps.canCreateParentMeetings) out.push("PARENT_MEETING");
  return out;
}

/** True when the caller may create anything at all in the school (the "New event" button). */
export function canCreateInSchool(caps: EventCapabilities | null | undefined): boolean {
  return schoolScopes(caps).length > 0;
}

/** The event types that go with a scope (the API refuses others with `event.type_invalid`). */
export function typesForScope(scope: EventScope): EventType[] {
  switch (scope) {
    case "SCHOOL":
    case "CAMPUS":
    case "GRADE_LEVEL":
      return ["SCHOOL_EVENT", "EXAM", "OTHER"];
    case "CLASS":
      return ["CLASS_EVENT", "EXAM", "OTHER"];
    case "PARENT_MEETING":
      return ["PARENT_MEETING"];
    case "PERSONAL":
      return ["OTHER"];
  }
}

export function defaultTypeForScope(scope: EventScope): EventType {
  return typesForScope(scope)[0]!;
}

/** Classes the caller may pick: their own list, or "every class of the school" (managers, whose list is omitted). */
export function classChoice(caps: EventCapabilities): { all: true } | { all: false; classes: { id: string; name: string }[] } {
  if (caps.canCreateAnyClassEvent && !caps.classes?.length) return { all: true };
  return { all: false, classes: (caps.classes ?? []).filter((c): c is { id: string; name: string } => !!c.id && !!c.name) };
}

/** Parent meetings carry no room for a series, all-day times or an RSVP switch: they are single, timed, with answers on. */
export function scopeAllowsRecurrence(scope: EventScope): boolean {
  return scope !== "PARENT_MEETING";
}

export function scopeAllowsAllDay(scope: EventScope): boolean {
  return scope !== "PARENT_MEETING";
}

/** Whether the "ask for answers" switch exists: not for personal events (no audience) nor meetings (always on). */
export function scopeOffersRsvp(scope: EventScope): boolean {
  return scope !== "PERSONAL" && scope !== "PARENT_MEETING";
}

/** The "event type" a view's scope shows; PERSONAL events appear under their own source. */
export function isMeetingType(type: string | undefined): boolean {
  return type === "PARENT_MEETING";
}
