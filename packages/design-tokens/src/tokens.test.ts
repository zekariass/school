import { describe, expect, it } from 'vitest';

import { colors, type SemanticColors } from './tokens';

/** WCAG 2.x relative luminance / contrast ratio. */
function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => {
    const c = parseInt(hex.slice(i, i + 2), 16) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  }) as [number, number, number];
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (hi + 0.05) / (lo + 0.05);
}

const PAIRS: [keyof SemanticColors, keyof SemanticColors][] = [
  ['foreground', 'background'],
  ['cardForeground', 'card'],
  ['primaryForeground', 'primary'],
  ['secondaryForeground', 'secondary'],
  ['mutedForeground', 'muted'],
  ['accentForeground', 'accent'],
  ['destructiveForeground', 'destructive'],
];

describe.each(['light', 'dark'] as const)('%s theme', (mode) => {
  it.each(PAIRS)('%s on %s meets WCAG AA for UI text (≥ 3:1)', (fg, bg) => {
    expect(contrast(colors[mode][fg], colors[mode][bg])).toBeGreaterThanOrEqual(3);
  });

  it('body text meets WCAG AA (≥ 4.5:1)', () => {
    expect(contrast(colors[mode].foreground, colors[mode].background)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(colors[mode].mutedForeground, colors[mode].background)).toBeGreaterThanOrEqual(4.5);
  });
});
