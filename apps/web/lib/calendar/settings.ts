/** The calendar settings a school may change, mapped to the id of their localized title and description. */
const CALENDAR_SETTING_IDS = {
  "calendar.teachers_can_create_class_events": "teachersCanCreateClassEvents",
  "calendar.notify.new_events": "notifyNewEvents",
  "calendar.notify.changes": "notifyChanges",
  "calendar.reminder.event_minutes": "reminderEventMinutes",
  "calendar.reminder.parent_meeting_minutes": "reminderParentMeetingMinutes",
  "calendar.reminder.audience": "reminderAudience",
} as const;

export type CalendarSettingId = (typeof CALENDAR_SETTING_IDS)[keyof typeof CALENDAR_SETTING_IDS];

/** The localization id of a calendar setting, or null for every other key. */
export function calendarSettingId(key: string): CalendarSettingId | null {
  return Object.hasOwn(CALENDAR_SETTING_IDS, key) ? (CALENDAR_SETTING_IDS as Record<string, CalendarSettingId>)[key]! : null;
}

export const CALENDAR_MODULE = "calendar";

export const CALENDAR_SETTING_IDS_LIST: readonly CalendarSettingId[] = Object.values(CALENDAR_SETTING_IDS);
export const CALENDAR_SETTING_KEYS: readonly string[] = Object.keys(CALENDAR_SETTING_IDS);
