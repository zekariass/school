import { describe, expect, it } from 'vitest';

import { contentLanguages, initialContentLanguage } from './composer';

describe('the language of the text (D42)', () => {
  // Invented codes: the languages come from the school's country, whatever they are.
  const LANGS = ['xa', 'xb'];

  it('offers the languages of the country, and the one a saved text already has', () => {
    expect(contentLanguages(LANGS, 'xa')).toEqual(['xa', 'xb']);
    expect(contentLanguages(LANGS, 'xz')).toEqual(['xz', 'xa', 'xb']);
    expect(contentLanguages(LANGS, undefined)).toEqual(['xa', 'xb']);
    expect(contentLanguages([], undefined)).toEqual([]);
  });

  it('starts in the saved language, else the app language when the country uses it, else the default', () => {
    expect(initialContentLanguage({ saved: 'xb', uiLocale: 'xa', countryLanguages: LANGS, defaultLanguage: 'xa' })).toBe('xb');
    expect(initialContentLanguage({ saved: null, uiLocale: 'xb', countryLanguages: LANGS, defaultLanguage: 'xa' })).toBe('xb');
    expect(initialContentLanguage({ saved: null, uiLocale: 'en', countryLanguages: LANGS, defaultLanguage: 'xa' })).toBe('xa');
  });
});
