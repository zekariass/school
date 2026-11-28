/**
 * The tabs of the feed screens (Feed, Photos, Activities, Achievements) and the server-side filter each one sends.
 * The API filters (`type`, `photos`): the client never filters or groups posts itself.
 */

export const FEED_TABS = ["feed", "photos", "activities", "achievements"] as const;
export type FeedTab = (typeof FEED_TABS)[number];

export type PostTypeFilter = "POST" | "ACTIVITY" | "ACHIEVEMENT";

/** The optional query parameters of the three feed endpoints. */
export interface FeedFilter {
  type?: PostTypeFilter;
  photos?: boolean;
}

export const DEFAULT_TAB: FeedTab = "feed";

export function isFeedTab(value: unknown): value is FeedTab {
  return typeof value === "string" && (FEED_TABS as readonly string[]).includes(value);
}

/** Feed = no filter; Photos = `photos=true`; Activities = `type=ACTIVITY`; Achievements = `type=ACHIEVEMENT`. */
export function tabFilter(tab: FeedTab): FeedFilter {
  switch (tab) {
    case "photos":
      return { photos: true };
    case "activities":
      return { type: "ACTIVITY" };
    case "achievements":
      return { type: "ACHIEVEMENT" };
    default:
      return {};
  }
}

/** Only what was asked for goes in the query: an absent filter sends no parameter at all. */
export function filterQuery(filter: FeedFilter | undefined): { type?: PostTypeFilter; photos?: true } {
  const out: { type?: PostTypeFilter; photos?: true } = {};
  if (filter?.type) out.type = filter.type;
  if (filter?.photos) out.photos = true;
  return out;
}

/** The filter of the "recent achievements" card of the right rail. */
export const ACHIEVEMENTS_FILTER: FeedFilter = { type: "ACHIEVEMENT" };
/** How many achievements the rail shows. */
export const RECENT_ACHIEVEMENTS = 3;
