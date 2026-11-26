import type { SemanticColors } from '@school/design-tokens';

import type { CalendarItem, CalendarSource } from './types';

/**
 * How an item looks: an icon AND a label (never colour alone), plus a design-token tone for the accent. Labels are catalog keys
 * (`mobile.calendar.kind.*`); icons are an SF Symbol and a Material Symbol name.
 */
export interface ItemStyle {
  /** Catalog key of the kind's name ("School event", "Holiday"...). */
  labelKey: string;
  icon: { ios: string; android: string };
  tone: keyof SemanticColors;
}

const EVENT_KINDS: Record<string, ItemStyle> = {
  SCHOOL_EVENT: { labelKey: 'mobile.calendar.kind.schoolEvent', icon: { ios: 'calendar', android: 'event' }, tone: 'primary' },
  CLASS_EVENT: { labelKey: 'mobile.calendar.kind.classEvent', icon: { ios: 'person.3', android: 'groups' }, tone: 'primary' },
  EXAM: { labelKey: 'mobile.calendar.kind.exam', icon: { ios: 'pencil.and.list.clipboard', android: 'edit_note' }, tone: 'pointsNeedsWork' },
  PARENT_MEETING: { labelKey: 'mobile.calendar.kind.parentMeeting', icon: { ios: 'person.2', android: 'handshake' }, tone: 'info' },
  OTHER: { labelKey: 'mobile.calendar.kind.other', icon: { ios: 'calendar.badge.clock', android: 'event' }, tone: 'primary' },
};

const ACADEMIC_KINDS: Record<string, string> = {
  YEAR_START: 'mobile.calendar.kind.yearStart',
  YEAR_END: 'mobile.calendar.kind.yearEnd',
  PERIOD_START: 'mobile.calendar.kind.periodStart',
  PERIOD_END: 'mobile.calendar.kind.periodEnd',
};

export function itemStyle(item: Pick<CalendarItem, 'source' | 'kind'>): ItemStyle {
  switch (item.source) {
    case 'HOLIDAY':
      return { labelKey: 'mobile.calendar.kind.holiday', icon: { ios: 'sun.max', android: 'beach_access' }, tone: 'warning' };
    case 'ACADEMIC_PERIOD':
      return { labelKey: ACADEMIC_KINDS[item.kind] ?? 'mobile.calendar.kind.academic', icon: { ios: 'graduationcap', android: 'school' }, tone: 'info' };
    case 'PERSONAL_EVENT':
      return { labelKey: 'mobile.calendar.kind.personal', icon: { ios: 'person.crop.circle', android: 'person' }, tone: 'accentForeground' };
    case 'EVENT':
      return EVENT_KINDS[item.kind] ?? EVENT_KINDS.OTHER!;
    default:
      return EVENT_KINDS.OTHER!;
  }
}

export const CANCELLED_ICON = { ios: 'xmark.circle', android: 'cancel' } as const;

export function isCancelled(item: Pick<CalendarItem, 'status'>): boolean {
  return item.status === 'CANCELLED';
}

/** Only native events (not holidays or academic dates) have a detail screen. */
export function opensDetail(item: Pick<CalendarItem, 'source' | 'eventId'>): boolean {
  return (item.source === 'EVENT' || item.source === 'PERSONAL_EVENT') && !!item.eventId;
}

/** The source chips of the filter row, in order, each with its catalog label. */
export const SOURCE_CHIPS: readonly { source: CalendarSource; labelKey: string; icon: { ios: string; android: string } }[] = [
  { source: 'EVENT', labelKey: 'mobile.calendar.source.events', icon: { ios: 'calendar', android: 'event' } },
  { source: 'HOLIDAY', labelKey: 'mobile.calendar.source.holidays', icon: { ios: 'sun.max', android: 'beach_access' } },
  { source: 'ACADEMIC_PERIOD', labelKey: 'mobile.calendar.source.academic', icon: { ios: 'graduationcap', android: 'school' } },
  { source: 'PERSONAL_EVENT', labelKey: 'mobile.calendar.source.personal', icon: { ios: 'person.crop.circle', android: 'person' } },
];
