import { describe, expect, it } from 'vitest';

import { DEFAULT_FEED_TAB, FEED_TABS, TAB_EMPTY_KEY, TAB_ICON, TAB_LABEL_KEY, filterOf, isFeedTab } from './tabs';

describe('filterOf', () => {
  it('sends no filter for the plain feed', () => {
    expect(filterOf('FEED')).toEqual({});
    expect(DEFAULT_FEED_TAB).toBe('FEED');
  });
  it('asks the server for photos, activities and achievements', () => {
    expect(filterOf('PHOTOS')).toEqual({ photos: true });
    expect(filterOf('ACTIVITIES')).toEqual({ type: 'ACTIVITY' });
    expect(filterOf('ACHIEVEMENTS')).toEqual({ type: 'ACHIEVEMENT' });
  });
  it('returns a copy: a caller cannot change the table', () => {
    const filter = filterOf('PHOTOS') as { photos?: boolean };
    filter.photos = false;
    expect(filterOf('PHOTOS')).toEqual({ photos: true });
  });
});

describe('the tab tables', () => {
  it('cover every tab, in the order of the design', () => {
    expect(FEED_TABS).toEqual(['FEED', 'PHOTOS', 'ACTIVITIES', 'ACHIEVEMENTS']);
    for (const tab of FEED_TABS) {
      expect(TAB_LABEL_KEY[tab]).toBe(`mobile.feed.tabs.${tab}`);
      expect(TAB_ICON[tab].ios).toBeTruthy();
      expect(TAB_ICON[tab].android).toBeTruthy();
    }
    expect(Object.keys(TAB_EMPTY_KEY)).toEqual(['PHOTOS', 'ACTIVITIES', 'ACHIEVEMENTS']);
  });
  it('recognises a tab', () => {
    expect(isFeedTab('PHOTOS')).toBe(true);
    expect(isFeedTab('photos')).toBe(false);
    expect(isFeedTab(undefined)).toBe(false);
  });
});
