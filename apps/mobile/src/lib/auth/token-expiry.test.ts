import { describe, expect, it } from 'vitest';

import { REFRESH_SKEW_MS, expiryFrom, needsProactiveRefresh } from './token-expiry';

describe('token expiry', () => {
  it('computes the expiry instant from expiresIn seconds', () => {
    expect(expiryFrom(1_000, 900)).toBe(901_000);
    expect(expiryFrom(1_000, -5)).toBe(1_000);
  });

  it('refreshes when the token expires within 60 seconds, or already has', () => {
    const expiresAt = 1_000_000;
    expect(REFRESH_SKEW_MS).toBe(60_000);
    expect(needsProactiveRefresh(expiresAt, expiresAt - 61_000)).toBe(false);
    expect(needsProactiveRefresh(expiresAt, expiresAt - 60_000)).toBe(true);
    expect(needsProactiveRefresh(expiresAt, expiresAt - 1)).toBe(true);
    expect(needsProactiveRefresh(expiresAt, expiresAt + 5_000)).toBe(true);
  });

  it('does not refresh when no expiry is known', () => {
    expect(needsProactiveRefresh(null, 123)).toBe(false);
  });
});
