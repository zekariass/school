import type { PostType } from './types';

/**
 * The sections of a feed screen. Each one is a server-side filter (`type`, `photos`) of the same endpoint: the app never
 * filters or groups posts itself, it shows exactly what the API answers for the chosen tab.
 */
export const FEED_TABS = ['FEED', 'PHOTOS', 'ACTIVITIES', 'ACHIEVEMENTS'] as const;
export type FeedTab = (typeof FEED_TABS)[number];

export const DEFAULT_FEED_TAB: FeedTab = 'FEED';

/** The optional query parameters of the feed endpoints (`GET /me/feed`, the class feed and the school feed). */
export interface FeedFilter {
  type?: PostType;
  photos?: true;
}

const FILTERS: Record<FeedTab, FeedFilter> = {
  FEED: {},
  PHOTOS: { photos: true },
  ACTIVITIES: { type: 'ACTIVITY' },
  ACHIEVEMENTS: { type: 'ACHIEVEMENT' },
};

/** The query parameters of a tab: none for the plain feed, `photos=true`, or one `type`. */
export function filterOf(tab: FeedTab): FeedFilter {
  return { ...FILTERS[tab] };
}

export function isFeedTab(value: unknown): value is FeedTab {
  return typeof value === 'string' && (FEED_TABS as readonly string[]).includes(value);
}

/** Message keys of a tab: its label and the empty state of a filtered tab (the plain feed has its own empty states). */
export const TAB_LABEL_KEY = {
  FEED: 'mobile.feed.tabs.FEED',
  PHOTOS: 'mobile.feed.tabs.PHOTOS',
  ACTIVITIES: 'mobile.feed.tabs.ACTIVITIES',
  ACHIEVEMENTS: 'mobile.feed.tabs.ACHIEVEMENTS',
} as const satisfies Record<FeedTab, string>;

export const TAB_EMPTY_KEY = {
  PHOTOS: { title: 'mobile.feed.empty.photosTitle', body: 'mobile.feed.empty.photosBody' },
  ACTIVITIES: { title: 'mobile.feed.empty.activitiesTitle', body: 'mobile.feed.empty.activitiesBody' },
  ACHIEVEMENTS: { title: 'mobile.feed.empty.achievementsTitle', body: 'mobile.feed.empty.achievementsBody' },
} as const satisfies Record<Exclude<FeedTab, 'FEED'>, { title: string; body: string }>;

/** SF Symbol and Material icon names of each tab (the same icon set as the rest of the app, `expo-symbols`). */
export const TAB_ICON = {
  FEED: { ios: 'house.fill', android: 'home' },
  PHOTOS: { ios: 'photo.on.rectangle', android: 'photo_library' },
  ACTIVITIES: { ios: 'calendar', android: 'event' },
  ACHIEVEMENTS: { ios: 'trophy.fill', android: 'emoji_events' },
} as const satisfies Record<FeedTab, { ios: string; android: string }>;
