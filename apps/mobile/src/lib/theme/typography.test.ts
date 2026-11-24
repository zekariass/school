import { describe, expect, it } from 'vitest';

import { fontFamilyFor, scriptForLocale, textStyleFor } from './typography';

describe('typography', () => {
  it('uses the Ethiopic face for Amharic and Nunito otherwise', () => {
    expect(scriptForLocale('am')).toBe('ethiopic');
    expect(scriptForLocale('en')).toBe('latin');
    expect(textStyleFor('body', 'en', 'ios').fontFamily).toBe('Nunito-Regular');
    expect(textStyleFor('body', 'am', 'android').fontFamily).toBe('NotoSansEthiopic_400Regular');
  });

  it('maps font names per platform', () => {
    expect(fontFamilyFor('latin', 'bold', 'ios')).toBe('Nunito-Bold');
    expect(fontFamilyFor('latin', 'bold', 'android')).toBe('Nunito_700Bold');
  });

  it('gives Amharic taller lines than English at the same size', () => {
    const en = textStyleFor('body', 'en', 'ios');
    const am = textStyleFor('body', 'am', 'ios');
    expect(am.fontSize).toBe(en.fontSize);
    expect(am.lineHeight).toBeGreaterThan(en.lineHeight);
    expect(en.lineHeight).toBe(26);
  });
});
