import type { components } from '@school/api-client';

type S = components['schemas'];

export type CalendarItem = S['CalendarItem'];
export type CalendarPage = S['CalendarPage'];
export type CalendarEventView = S['CalendarEventView'];
export type CalendarEventRequest = S['CalendarEventRequest'];
export type CalendarEventUpdateRequest = S['CalendarEventUpdateRequest'];
export type CalendarOccurrenceUpdateRequest = S['CalendarOccurrenceUpdateRequest'];
export type CalendarCapabilities = S['CalendarCapabilities'];
export type CalendarOverride = S['CalendarOccurrenceOverrideView'];
export type CalendarRsvpView = S['CalendarRsvpView'];
export type RsvpAnswer = 'YES' | 'NO' | 'MAYBE';

/** The sources of the union (`GET /calendar?sources=`). */
export const CALENDAR_SOURCES = ['EVENT', 'PERSONAL_EVENT', 'HOLIDAY', 'ACADEMIC_PERIOD'] as const;
export type CalendarSource = (typeof CALENDAR_SOURCES)[number];

export type EventScope = NonNullable<CalendarEventRequest['scope']>;
export type EventType = NonNullable<CalendarEventRequest['eventType']>;
