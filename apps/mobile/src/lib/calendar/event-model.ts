import { occurrenceTimes, type OccurrenceTimes } from './occurrence';
import type { CalendarEventView, RsvpAnswer } from './types';

/**
 * What the event screen shows and offers, derived from the API's own answer (`capabilities`, `rsvp`, `status`). The buttons only
 * follow what the API says the caller may do; it stays the authority and refuses the rest.
 */

export interface EventActions {
  /** The whole event is cancelled. */
  cancelled: boolean;
  /** This occurrence (or the one-off event) is cancelled. */
  occurrenceCancelled: boolean;
  recurring: boolean;
  edit: boolean;
  cancel: boolean;
  delete: boolean;
  /** Repeating events, when an occurrence is selected: change or cancel just this one. */
  editOccurrence: boolean;
  cancelOccurrence: boolean;
  /** The occurrence has an exception that can be taken away (it was cancelled or changed). */
  restoreOccurrence: boolean;
}

export function eventActions(view: CalendarEventView, occurrenceId: string | null): EventActions {
  const cancelled = view.status === 'CANCELLED';
  const times = occurrenceTimes(view, occurrenceId);
  const caps = view.capabilities;
  const canEdit = !!caps?.canEdit && !cancelled;
  const recurring = !!view.recurring;
  const series = recurring && !!occurrenceId;
  return {
    cancelled,
    occurrenceCancelled: times.cancelled,
    recurring,
    edit: canEdit,
    cancel: !!caps?.canCancel && !cancelled,
    delete: !!caps?.canDelete,
    editOccurrence: canEdit && series && !times.cancelled,
    cancelOccurrence: !!caps?.canCancel && !cancelled && series && !times.cancelled,
    restoreOccurrence: canEdit && series && (times.cancelled || times.modified),
  };
}

export interface RsvpModel {
  /** Whether the event asks for answers at all. */
  enabled: boolean;
  /** The answer buttons are active. */
  canAnswer: boolean;
  mine: RsvpAnswer | 'PENDING' | null;
  /** Repeating events cannot ask for answers: the explanation line is shown instead of buttons. */
  recurringNote: boolean;
  /** Totals, for managers and organisers only (the API sends them to nobody else). */
  counts: { yes: number; no: number; maybe: number; pending: number } | null;
  /** Who answered what: the organiser of a parent meeting only (the API sends it to nobody else). */
  participants: { userId: string; name: string; status: string; respondedAt: string | null }[] | null;
}

export function rsvpModel(view: CalendarEventView): RsvpModel {
  const rsvp = view.rsvp;
  const mine = rsvp?.mine === 'YES' || rsvp?.mine === 'NO' || rsvp?.mine === 'MAYBE' || rsvp?.mine === 'PENDING' ? rsvp.mine : null;
  return {
    enabled: !!rsvp?.enabled,
    canAnswer: !!rsvp?.enabled && !!rsvp.canAnswer && view.status !== 'CANCELLED',
    mine,
    recurringNote: !!view.recurring && view.scope !== 'PERSONAL',
    counts: rsvp?.counts ? { yes: rsvp.counts.yes ?? 0, no: rsvp.counts.no ?? 0, maybe: rsvp.counts.maybe ?? 0, pending: rsvp.counts.pending ?? 0 } : null,
    participants: rsvp?.participants
      ? rsvp.participants.map((p) => ({ userId: p.userId ?? '', name: p.displayName ?? '', status: p.status ?? 'PENDING', respondedAt: p.respondedAt ?? null }))
      : null,
  };
}

export const RSVP_ANSWERS: readonly RsvpAnswer[] = ['YES', 'MAYBE', 'NO'];

/** The catalog key of an answer's label (the status words of a participant too). */
export function rsvpLabelKey(status: string): string {
  switch (status) {
    case 'YES':
      return 'mobile.calendar.rsvp.yes';
    case 'NO':
      return 'mobile.calendar.rsvp.no';
    case 'MAYBE':
      return 'mobile.calendar.rsvp.maybe';
    default:
      return 'mobile.calendar.rsvp.pending';
  }
}

/** The times to show on the detail screen: the opened occurrence's, or the event's own. */
export function shownTimes(view: CalendarEventView, occurrenceId: string | null): OccurrenceTimes {
  return occurrenceTimes(view, occurrenceId);
}

/** Whether the event is a parent meeting (its title may name a child: shown only to the people it concerns). */
export function isParentMeeting(view: Pick<CalendarEventView, 'eventType' | 'scope'>): boolean {
  return view.eventType === 'PARENT_MEETING' || view.scope === 'INVITEES';
}
