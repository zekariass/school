/** Every notification query key, in one place. All of them live under the "notifications" prefix. */
export const DROPDOWN_LIMIT = 10;
export const PAGE_LIMIT = 20;

export const notificationKeys = {
  all: ["notifications"] as const,
  unreadCount: ["notifications", "unread-count"] as const,
  /** The latest few, shown in the bell's dropdown (a single page). */
  dropdown: ["notifications", "dropdown"] as const,
  /** The full list (infinite query); `unreadOnly` is part of the key. */
  inbox: (unreadOnly: boolean) => ["notifications", "inbox", { unreadOnly }] as const,
  inboxAll: ["notifications", "inbox"] as const,
  preferences: ["notifications", "preferences"] as const,
  settings: ["notifications", "settings"] as const,
};
