import { describe, expect, it } from 'vitest';

import { languageLabel, scriptOfLocale } from './locales';

describe('scriptOfLocale', () => {
  it('reads the script from the catalog list, by base language, and defaults to Latin', () => {
    expect(scriptOfLocale('am')).toBe('Ethi');
    expect(scriptOfLocale('am-ET')).toBe('Ethi');
    expect(scriptOfLocale('en')).toBe('Latn');
    expect(scriptOfLocale('sw')).toBe('Latn');
  });
});

describe('languageLabel', () => {
  it('names a language for the reader', () => {
    expect(languageLabel('en', 'en')).toBe('English');
    expect(languageLabel('sw', 'en')).toBe('Swahili');
  });

  it('never returns an empty name, even for a code nobody knows', () => {
    expect(languageLabel('zz-invalid-tag-', 'en')).toBeTruthy();
    expect(languageLabel('xx', 'en')).toBeTruthy();
  });
});
