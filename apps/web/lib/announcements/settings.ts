/** The announcement settings a school may change, mapped to the id of their localized title and description. */
const ANNOUNCEMENT_SETTING_IDS = {
  "announcements.teachers_can_announce": "teachersCanAnnounce",
  "announcements.students_enabled": "studentsEnabled",
  "announcements.channel.email.enabled": "emailEnabled",
  "announcements.channel.sms.enabled": "smsEnabled",
  "announcements.sms.types": "smsTypes",
  "announcements.sms.max_per_announcement": "smsMaxPerAnnouncement",
  "announcements.sms.max_per_day": "smsMaxPerDay",
  "announcements.email.include_body": "emailIncludeBody",
  "announcements.reminder.min_hours": "reminderMinHours",
} as const;

export type AnnouncementSettingId = (typeof ANNOUNCEMENT_SETTING_IDS)[keyof typeof ANNOUNCEMENT_SETTING_IDS];

/** The localization id of an announcement setting, or null for every other key. */
export function announcementSettingId(key: string): AnnouncementSettingId | null {
  return Object.hasOwn(ANNOUNCEMENT_SETTING_IDS, key) ? (ANNOUNCEMENT_SETTING_IDS as Record<string, AnnouncementSettingId>)[key]! : null;
}

export const ANNOUNCEMENTS_MODULE = "announcements";

export const ANNOUNCEMENT_SETTING_IDS_LIST: readonly AnnouncementSettingId[] = Object.values(ANNOUNCEMENT_SETTING_IDS);
export const ANNOUNCEMENT_SETTING_KEYS: readonly string[] = Object.keys(ANNOUNCEMENT_SETTING_IDS);
