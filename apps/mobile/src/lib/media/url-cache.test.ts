import { describe, expect, it } from 'vitest';

import { mediaUrlKey, shouldPersistQuery, URL_EXPIRY_MARGIN_MS, URL_MAX_AGE_MS, urlStaleTimeMs } from './url-cache';

const NOW = Date.parse('2026-11-24T10:00:00Z');
const at = (ms: number) => new Date(NOW + ms).toISOString();

describe('urlStaleTimeMs', () => {
  it('reuses a fresh link for at most four minutes', () => {
    expect(urlStaleTimeMs(at(5 * 60_000), NOW)).toBe(URL_MAX_AGE_MS);
  });

  it('stops shortly before the link expires', () => {
    expect(urlStaleTimeMs(at(2 * 60_000), NOW)).toBe(2 * 60_000 - URL_EXPIRY_MARGIN_MS);
  });

  it('never reuses an expired, missing or malformed link', () => {
    expect(urlStaleTimeMs(at(10_000), NOW)).toBe(0);
    expect(urlStaleTimeMs(at(-5), NOW)).toBe(0);
    expect(urlStaleTimeMs(undefined, NOW)).toBe(0);
    expect(urlStaleTimeMs('later', NOW)).toBe(0);
  });
});

describe('persistence', () => {
  it('keeps signed links out of the persisted cache', () => {
    expect(shouldPersistQuery(mediaUrlKey('m', 'SMALL'))).toBe(false);
    expect(shouldPersistQuery(['me'])).toBe(true);
    expect(shouldPersistQuery([])).toBe(true);
  });
});
