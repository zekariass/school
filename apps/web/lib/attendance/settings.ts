/** The attendance settings a school may change, mapped to the id of their localized title and description. */
const ATTENDANCE_SETTING_IDS = {
  "attendance.slots": "slots",
  "attendance.assistants_can_take": "assistants",
  "attendance.lock_after_hours": "lockAfter",
  "attendance.notify_guardians.statuses": "notify",
} as const;

export type AttendanceSettingId = (typeof ATTENDANCE_SETTING_IDS)[keyof typeof ATTENDANCE_SETTING_IDS];

/** The localization id of an attendance setting, or null for every other key. */
export function attendanceSettingId(key: string): AttendanceSettingId | null {
  return Object.hasOwn(ATTENDANCE_SETTING_IDS, key) ? (ATTENDANCE_SETTING_IDS as Record<string, AttendanceSettingId>)[key]! : null;
}

export const ATTENDANCE_MODULE = "attendance";
