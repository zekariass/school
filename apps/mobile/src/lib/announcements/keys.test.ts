import { describe, expect, it } from 'vitest';

import { isPersistableQuery } from '@/lib/query/persistence';

import {
  ANNOUNCEMENTS_ROOT,
  MAX_PAGES,
  anywhereKey,
  audienceChoicesKey,
  authoredKey,
  capabilitiesKey,
  channelsKey,
  detailKey,
  inboxKey,
  isPersistableAnnouncementQuery,
  nextPage,
  recipientsKey,
  reportKey,
  summaryKey,
} from './keys';

const ALL_KEYS = [
  summaryKey,
  inboxKey(null, 'ALL', 'NEWEST'),
  inboxKey('s', 'UNREAD', 'UNREAD_FIRST'),
  detailKey('s', 'a'),
  authoredKey('s', null, true),
  capabilitiesKey('s'),
  reportKey('s', 'a'),
  recipientsKey('s', 'a', 'UNREAD'),
  channelsKey,
  audienceChoicesKey('s', 'classes', 'q'),
  anywhereKey('a', 's1,s2'),
];

describe('persistence of announcement queries', () => {
  it('keeps every announcement query out of the persisted cache: texts, counts and recipient names are memory only', () => {
    for (const key of ALL_KEYS) {
      expect(key[0]).toBe(ANNOUNCEMENTS_ROOT);
      expect(isPersistableAnnouncementQuery(key)).toBe(false);
      // ... also through the one predicate the query provider really uses
      expect(isPersistableQuery(key)).toBe(false);
    }
  });
  it('does not touch other queries', () => {
    expect(isPersistableAnnouncementQuery(['classes', 'mine'])).toBe(true);
    expect(isPersistableQuery(['classes', 'mine'])).toBe(true);
  });
  it('still keeps the other private roots out', () => {
    for (const root of ['feed', 'notifications', 'media-url']) expect(isPersistableQuery([root, 'x'])).toBe(false);
  });
});

describe('nextPage', () => {
  it('follows the cursor', () => {
    expect(nextPage({ nextCursor: 'abc' }, 1)).toBe('abc');
    expect(nextPage({}, 1)).toBeUndefined();
    expect(nextPage({ nextCursor: '' }, 1)).toBeUndefined();
  });
  it('stops at the memory bound', () => {
    expect(nextPage({ nextCursor: 'abc' }, MAX_PAGES)).toBeUndefined();
    expect(nextPage({ nextCursor: 'abc' }, MAX_PAGES - 1)).toBe('abc');
  });
});
