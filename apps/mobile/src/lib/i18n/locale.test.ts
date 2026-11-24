import { describe, expect, it } from 'vitest';

import { pickInitialLocale } from './locale';

describe('pickInitialLocale', () => {
  it('uses the saved preference first', () => {
    expect(pickInitialLocale('am', ['en-US'])).toBe('am');
    expect(pickInitialLocale('en', ['am-ET'])).toBe('en');
  });

  it('ignores unsupported saved values and falls back to the device language', () => {
    expect(pickInitialLocale('fr', ['am-ET', 'en-US'])).toBe('am');
    expect(pickInitialLocale(null, ['fr-FR', 'am'])).toBe('am');
    expect(pickInitialLocale(undefined, ['en-GB'])).toBe('en');
  });

  it('defaults to English when nothing matches', () => {
    expect(pickInitialLocale(null, ['fr-FR', 'de'])).toBe('en');
    expect(pickInitialLocale(null, [])).toBe('en');
  });
});
