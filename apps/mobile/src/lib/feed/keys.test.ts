import { describe, expect, it } from 'vitest';

import {
  FEED_MAX_PAGES,
  classFeedKey,
  classStoriesKey,
  commentsKey,
  homeFeedKey,
  isPersistableFeedQuery,
  myPostsKey,
  nextFeedPage,
  pendingKey,
  postKey,
  schoolFeedKey,
} from './keys';

describe('isPersistableFeedQuery', () => {
  it('keeps every feed query out of the persisted cache: posts, photos and tags describe children', () => {
    for (const key of [
      classFeedKey('s', 'c'),
      schoolFeedKey('s'),
      homeFeedKey(null),
      homeFeedKey('st'),
      homeFeedKey('st', 'PHOTOS'),
      classFeedKey('s', 'c', 'ACHIEVEMENTS'),
      schoolFeedKey('s', 'ACTIVITIES'),
      myPostsKey('s', null, null),
      pendingKey('s', null),
      postKey('s', 'p'),
      commentsKey('s', 'p'),
      classStoriesKey('s', 'c'),
    ]) {
      expect(isPersistableFeedQuery(key)).toBe(false);
    }
  });
  it('does not touch other queries', () => {
    expect(isPersistableFeedQuery(['classes', 'mine'])).toBe(true);
  });
});

describe('feed keys per tab', () => {
  it('give every tab its own query, under the feed root', () => {
    const keys = (['FEED', 'PHOTOS', 'ACTIVITIES', 'ACHIEVEMENTS'] as const).map((tab) => JSON.stringify(classFeedKey('s', 'c', tab)));
    expect(new Set(keys).size).toBe(4);
    expect(homeFeedKey(null, 'PHOTOS')).toEqual(['feed', 'home', 'all', 'PHOTOS']);
    expect(schoolFeedKey('s')).toEqual(['feed', 'school', 's', 'FEED']);
  });
});

describe('nextFeedPage', () => {
  it('follows the cursor', () => {
    expect(nextFeedPage({ nextCursor: 'abc' }, 1)).toBe('abc');
    expect(nextFeedPage({}, 1)).toBeUndefined();
    expect(nextFeedPage({ nextCursor: '' }, 1)).toBeUndefined();
  });
  it('stops at the memory bound', () => {
    expect(nextFeedPage({ nextCursor: 'abc' }, FEED_MAX_PAGES)).toBeUndefined();
    expect(nextFeedPage({ nextCursor: 'abc' }, FEED_MAX_PAGES - 1)).toBe('abc');
  });
});
