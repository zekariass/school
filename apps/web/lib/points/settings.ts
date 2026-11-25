/** The engagement settings a school may change, mapped to the id of their localized title and description. */
const ENGAGEMENT_SETTING_IDS = {
  "engagement.needs_work.enabled": "needsWork",
  "engagement.badges.enabled": "badges",
  "engagement.leaderboard.enabled": "leaderboard",
  "engagement.points.undo_window_seconds": "undoWindow",
} as const;

export type EngagementSettingId = (typeof ENGAGEMENT_SETTING_IDS)[keyof typeof ENGAGEMENT_SETTING_IDS];

/** The localization id of an engagement setting, or null for every other key. */
export function engagementSettingId(key: string): EngagementSettingId | null {
  return Object.hasOwn(ENGAGEMENT_SETTING_IDS, key) ? (ENGAGEMENT_SETTING_IDS as Record<string, EngagementSettingId>)[key]! : null;
}

export const ENGAGEMENT_MODULE = "engagement";
