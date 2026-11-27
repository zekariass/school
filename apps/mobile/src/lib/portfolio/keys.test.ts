import { describe, expect, it } from 'vitest';

import { isPersistableQuery } from '@/lib/query/persistence';

import {
  PORTFOLIO_MAX_PAGES,
  capabilitiesKey,
  childrenKey,
  commentsKey,
  historyKey,
  isPersistablePortfolioQuery,
  itemKey,
  mineKey,
  nextPortfolioPage,
  queueKey,
} from './keys';

const KEYS = [
  capabilitiesKey('s'),
  mineKey('s', null),
  mineKey('s', 'DRAFT'),
  queueKey('s', 'c'),
  childrenKey(null),
  childrenKey('st'),
  itemKey('s', 'i'),
  commentsKey('s', 'i'),
  historyKey('s', 'i'),
];

describe('isPersistablePortfolioQuery', () => {
  it('keeps every portfolio query out of the persisted cache: work, notes and comments describe children', () => {
    for (const key of KEYS) expect(isPersistablePortfolioQuery(key)).toBe(false);
  });
  it('does not touch other queries', () => {
    expect(isPersistablePortfolioQuery(['classes', 'mine'])).toBe(true);
    expect(isPersistablePortfolioQuery(['feed', 'class'])).toBe(true);
  });
});

describe('the single persistence predicate of the app', () => {
  it('refuses to persist any portfolio query, however it is built', () => {
    for (const key of KEYS) expect(isPersistableQuery(key)).toBe(false);
    expect(isPersistableQuery(['portfolio', 'item-any', 'x', 'a,b'])).toBe(false);
    expect(isPersistableQuery(['portfolio'])).toBe(false);
  });
  it('still persists what it persisted before', () => {
    expect(isPersistableQuery(['classes', 'mine'])).toBe(true);
  });
});

describe('nextPortfolioPage', () => {
  it('follows the cursor', () => {
    expect(nextPortfolioPage({ nextCursor: 'abc' }, 1)).toBe('abc');
    expect(nextPortfolioPage({}, 1)).toBeUndefined();
    expect(nextPortfolioPage({ nextCursor: '' }, 1)).toBeUndefined();
  });
  it('stops at the memory bound', () => {
    expect(nextPortfolioPage({ nextCursor: 'abc' }, PORTFOLIO_MAX_PAGES)).toBeUndefined();
    expect(nextPortfolioPage({ nextCursor: 'abc' }, PORTFOLIO_MAX_PAGES - 1)).toBe('abc');
  });
});
