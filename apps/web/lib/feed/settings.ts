/** The feed settings a school may change, mapped to the id of their localized title and description. */
const FEED_SETTING_IDS = {
  "feed.parent_contributions.enabled": "parentContributions",
  "feed.parent_contributions.approval_required": "parentApproval",
  "feed.parent_contributions.allowed_media": "parentMedia",
  "feed.student_contributions.enabled": "studentContributions",
  "feed.comments.default_enabled": "commentsDefault",
  "feed.comments.guardians_can_comment": "guardiansComment",
  "feed.comments.students_can_comment": "studentsComment",
  "feed.pin_limit": "pinLimit",
  "feed.assistants_can_post": "assistantsPost",
  "feed.notify_class_members": "notifyMembers",
  "feed.translation.enabled": "translation",
} as const;

export type FeedSettingId = (typeof FEED_SETTING_IDS)[keyof typeof FEED_SETTING_IDS];

/** The localization id of a feed setting, or null for every other key. */
export function feedSettingId(key: string): FeedSettingId | null {
  return Object.hasOwn(FEED_SETTING_IDS, key) ? (FEED_SETTING_IDS as Record<string, FeedSettingId>)[key]! : null;
}

export const FEED_MODULE = "feed";

export const FEED_SETTING_IDS_LIST: readonly FeedSettingId[] = Object.values(FEED_SETTING_IDS);
