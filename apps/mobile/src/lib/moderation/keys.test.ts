import { describe, expect, it } from 'vitest';

import { isPersistableQuery } from '@/lib/query/persistence';

import { MODERATION_GC_MS, MODERATION_ROOT, emergencyInfoKey, isPersistableModerationQuery } from './keys';

describe('persistence of moderation queries', () => {
  const keys = [emergencyInfoKey('XX'), [MODERATION_ROOT], [MODERATION_ROOT, 'anything', { with: 'object' }]];

  it('keeps every moderation query out of the persisted cache', () => {
    for (const key of keys) {
      expect(key[0]).toBe(MODERATION_ROOT);
      expect(isPersistableModerationQuery(key)).toBe(false);
    }
  });
  it('is wired into the one predicate the query provider really uses', () => {
    for (const key of keys) expect(isPersistableQuery(key)).toBe(false);
  });
  it('does not touch other queries and the other private roots stay private', () => {
    expect(isPersistableModerationQuery(['classes', 'mine'])).toBe(true);
    expect(isPersistableQuery(['classes', 'mine'])).toBe(true);
    for (const root of ['feed', 'messaging', 'announcements', 'calendar', 'portfolio', 'notifications']) expect(isPersistableQuery([root, 'x'])).toBe(false);
  });
  it('keeps unused answers in memory for minutes, not a day', () => {
    expect(MODERATION_GC_MS).toBeLessThanOrEqual(10 * 60_000);
  });
});
