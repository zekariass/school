import type { components } from "@school/api-client";

type S = components["schemas"];
export type EventView = S["CalendarEventView"];
export type RsvpAnswer = "YES" | "NO" | "MAYBE";
export const RSVP_ANSWERS: readonly RsvpAnswer[] = ["YES", "NO", "MAYBE"];
/** The caller's answer as the API reports it: an answer or PENDING (asked, not answered yet). */
export type RsvpMine = RsvpAnswer | "PENDING";

export function isRsvpAnswer(value: unknown): value is RsvpAnswer {
  return value === "YES" || value === "NO" || value === "MAYBE";
}

/**
 * How the answer section of an event looks to the caller, derived from the event's `rsvp` block:
 * - `none`: the event does not ask for answers (nothing is shown);
 * - `recurring`: repeating events never ask for answers (a short explanation is shown);
 * - `answerable`: the buttons are active (`rsvp.canAnswer`);
 * - `closed`: answers are asked but closed (the event started or was cancelled): the caller's answer is shown, buttons are not;
 * - `observer`: the caller was not asked (a manager or organiser): only totals (and for a meeting's organiser, answers) are shown.
 * The API stays the authority: it answers 409 `event.rsvp_closed` and 404 to anybody who may not answer.
 */
export type RsvpMode = "none" | "recurring" | "answerable" | "closed" | "observer";

export function rsvpMode(event: Pick<EventView, "recurring" | "rsvpEnabled" | "rsvp" | "status">): RsvpMode {
  if (event.recurring) return "recurring";
  const enabled = event.rsvp?.enabled ?? event.rsvpEnabled ?? false;
  if (!enabled) return "none";
  if (event.rsvp?.mine === undefined) return "observer";
  if (event.status === "CANCELLED") return "closed";
  return event.rsvp.canAnswer ? "answerable" : "closed";
}

/** The caller's own answer when it is a real one (PENDING and "never asked" are null). */
export function myAnswer(event: Pick<EventView, "rsvp">): RsvpAnswer | null {
  const mine = event.rsvp?.mine;
  return isRsvpAnswer(mine) ? mine : null;
}

export interface Totals {
  yes: number;
  no: number;
  maybe: number;
  pending: number;
  total: number;
}

/** The totals of the answers (managers and organisers only: the API leaves them out for everybody else), or null. */
export function totalsOf(event: Pick<EventView, "rsvp">): Totals | null {
  const c = event.rsvp?.counts;
  if (!c) return null;
  const yes = c.yes ?? 0;
  const no = c.no ?? 0;
  const maybe = c.maybe ?? 0;
  const pending = c.pending ?? 0;
  return { yes, no, maybe, pending, total: yes + no + maybe + pending };
}

export interface Participant {
  /** The guardian's own display name: shown to the organiser of a parent meeting only. */
  name: string;
  status: RsvpMine | "UNKNOWN";
  respondedAt: string | null;
}

/** The organiser's list of who answered what: present only when the API sent it (never derived, never filtered here). */
export function participantsOf(event: Pick<EventView, "rsvp">): Participant[] | null {
  const list = event.rsvp?.participants;
  if (!list) return null;
  return list.map((p) => ({
    name: p.displayName ?? "",
    status: p.status === "YES" || p.status === "NO" || p.status === "MAYBE" || p.status === "PENDING" ? p.status : "UNKNOWN",
    respondedAt: p.respondedAt ?? null,
  }));
}

/** A parent meeting: its title and place may name a child, so it is never put in a document title, URL or notice. */
export function isParentMeeting(event: Pick<EventView, "eventType">): boolean {
  return event.eventType === "PARENT_MEETING";
}

/** What the detail offers, from `capabilities` (the API still answers 403 `event.access_denied`). */
export interface Actions {
  edit: boolean;
  cancel: boolean;
  delete: boolean;
}

export function actionsOf(event: Pick<EventView, "capabilities" | "status">): Actions {
  const caps = event.capabilities;
  const cancelled = event.status === "CANCELLED";
  return {
    edit: !!caps?.canEdit && !cancelled,
    cancel: !!caps?.canCancel && !cancelled,
    delete: !!caps?.canDelete,
  };
}

/** Occurrence actions exist for series: "only this event" (occurrence endpoints) or "all events" (the series). */
export type OccurrenceChoice = "occurrence" | "series";

export function choicesFor(event: Pick<EventView, "recurring">, hasOccurrence: boolean): OccurrenceChoice[] {
  return event.recurring && hasOccurrence ? ["occurrence", "series"] : ["series"];
}
