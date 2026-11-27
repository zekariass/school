import { typography } from '@school/design-tokens';
import { scriptOfLocale } from '@school/i18n';

export type FontWeightName = 'regular' | 'semibold' | 'bold' | 'extrabold';
export type FontScript = 'latin' | 'ethiopic';
export type FontPlatform = 'ios' | 'android';

/**
 * Embedded font faces (expo-font config plugin in app.json). Android registers a
 * family per file name; iOS uses each file's PostScript name.
 */
const FONT_FAMILIES = {
  latin: {
    regular: { ios: 'Nunito-Regular', android: 'Nunito_400Regular' },
    semibold: { ios: 'Nunito-SemiBold', android: 'Nunito_600SemiBold' },
    bold: { ios: 'Nunito-Bold', android: 'Nunito_700Bold' },
    extrabold: { ios: 'Nunito-ExtraBold', android: 'Nunito_800ExtraBold' },
  },
  ethiopic: {
    regular: { ios: 'NotoSansEthiopic-Regular', android: 'NotoSansEthiopic_400Regular' },
    semibold: { ios: 'NotoSansEthiopic-SemiBold', android: 'NotoSansEthiopic_600SemiBold' },
    bold: { ios: 'NotoSansEthiopic-Bold', android: 'NotoSansEthiopic_700Bold' },
    extrabold: { ios: 'NotoSansEthiopic-ExtraBold', android: 'NotoSansEthiopic_800ExtraBold' },
  },
} as const satisfies Record<FontScript, Record<FontWeightName, Record<FontPlatform, string>>>;

/** A language written in the Ethiopic script uses Noto Sans Ethiopic first; everything else uses Nunito. Chosen by the script of the language (`@school/i18n`), not by its code. */
export function scriptForLocale(locale: string): FontScript {
  return scriptOfLocale(locale) === 'Ethi' ? 'ethiopic' : 'latin';
}

export function fontFamilyFor(script: FontScript, weight: FontWeightName, platform: FontPlatform): string {
  return FONT_FAMILIES[script][weight][platform];
}

export type TextVariant = 'title' | 'heading' | 'body' | 'caption' | 'label';

const VARIANTS = {
  title: { size: '3xl', weight: 'extrabold' },
  heading: { size: 'xl', weight: 'bold' },
  body: { size: 'base', weight: 'regular' },
  caption: { size: 'sm', weight: 'regular' },
  label: { size: 'sm', weight: 'semibold' },
} as const satisfies Record<TextVariant, { size: keyof typeof typography.fontSize; weight: FontWeightName }>;

export interface TextStyleSpec {
  fontFamily: string;
  fontSize: number;
  /** Absolute line height in px (RN needs px, tokens are unitless). */
  lineHeight: number;
}

/**
 * Typography for a text variant. Ethiopic glyphs are tall, so Amharic text gets
 * extra leading on top of the token line height.
 */
export function textStyleFor(variant: TextVariant, locale: string, platform: FontPlatform): TextStyleSpec {
  const { size, weight } = VARIANTS[variant];
  const token = typography.fontSize[size];
  const script = scriptForLocale(locale);
  const leading = script === 'ethiopic' ? token.lineHeight + 0.1 : token.lineHeight;
  return {
    fontFamily: fontFamilyFor(script, weight, platform),
    fontSize: token.size,
    lineHeight: Math.round(token.size * leading),
  };
}
