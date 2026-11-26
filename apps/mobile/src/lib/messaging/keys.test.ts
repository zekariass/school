import { describe, expect, it } from 'vitest';

import { isPersistableQuery } from '@/lib/query/persistence';

import {
  MESSAGING_MAX_PAGES,
  MESSAGING_ROOT,
  afterHoursKey,
  blocksKey,
  capabilitiesKey,
  contactsKey,
  conversationKey,
  groupsKey,
  inboxKey,
  isPersistableMessagingQuery,
  membersKey,
  messagesKey,
  nextMessagingPage,
  scheduledKey,
  unreadCountKey,
} from './keys';

const ALL_KEYS = [
  unreadCountKey,
  inboxKey(null, null),
  inboxKey('s', 'kid'),
  conversationKey('c'),
  membersKey('c'),
  messagesKey('c'),
  capabilitiesKey('s'),
  contactsKey('s', 'kid', 'default'),
  scheduledKey,
  groupsKey('s', 'all'),
  blocksKey,
  afterHoursKey('c'),
];

describe('persistence of messaging queries', () => {
  it('keeps every messaging query out of the persisted cache: bodies, names, members and counts are memory only', () => {
    for (const key of ALL_KEYS) {
      expect(key[0]).toBe(MESSAGING_ROOT);
      expect(isPersistableMessagingQuery(key)).toBe(false);
      // ... also through the one predicate the query provider really uses
      expect(isPersistableQuery(key)).toBe(false);
    }
  });
  it('does not touch other queries', () => {
    expect(isPersistableMessagingQuery(['classes', 'mine'])).toBe(true);
    expect(isPersistableQuery(['classes', 'mine'])).toBe(true);
  });
  it('still keeps the other private roots out', () => {
    for (const root of ['feed', 'announcements', 'notifications', 'media-url']) expect(isPersistableQuery([root, 'x'])).toBe(false);
  });
});

describe('nextMessagingPage', () => {
  it('follows the cursor and stops at the memory bound', () => {
    expect(nextMessagingPage({ nextCursor: 'abc' }, 1)).toBe('abc');
    expect(nextMessagingPage({}, 1)).toBeUndefined();
    expect(nextMessagingPage({ nextCursor: 'abc' }, MESSAGING_MAX_PAGES)).toBeUndefined();
  });
});
