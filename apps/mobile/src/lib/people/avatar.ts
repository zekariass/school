/** Placeholder avatars: a friendly colour and initials, the same every time for the same person. */

/** Dark enough for white text (contrast at least 4.5:1). */
export const AVATAR_PALETTE = [
  '#C2410C',
  '#B45309',
  '#15803D',
  '#0F766E',
  '#0369A1',
  '#4338CA',
  '#7E22CE',
  '#BE185D',
  '#B91C1C',
  '#475569',
] as const;

/** 32-bit FNV-1a hash: stable across runs and platforms. */
export function hashString(value: string): number {
  let hash = 0x811c9dc5;
  for (let i = 0; i < value.length; i++) {
    hash ^= value.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash >>> 0;
}

const HEX_COLOR = /^#[0-9a-fA-F]{6}$/;

/**
 * Background of a placeholder avatar: the colour in `avatarConfig.color` when it is a valid
 * `#rrggbb`, otherwise one from the palette chosen by the seed (the person's id or name).
 */
export function avatarColor(seed: string, avatarConfig?: Record<string, unknown> | null): string {
  const configured = avatarConfig?.['color'];
  if (typeof configured === 'string' && HEX_COLOR.test(configured)) return configured;
  return AVATAR_PALETTE[hashString(seed) % AVATAR_PALETTE.length]!;
}

/** First letter of up to `max` words ("Abebe Kebede Tadesse" -> "AK"; Ethiopic letters work too). */
export function initialsOf(name: string, max = 2): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  return words
    .slice(0, max)
    .map((word) => Array.from(word)[0]!.toLocaleUpperCase())
    .join('');
}
