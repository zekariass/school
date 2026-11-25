/** Initials and a stable pastel color for students without a photo. */

const HEX_COLOR = /^#[0-9a-fA-F]{6}$/;

/** Up to two initials: first letters of the first and last word (works for Amharic too). */
export function initialsOf(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return "?";
  const first = Array.from(words[0]!)[0] ?? "?";
  const last = words.length > 1 ? (Array.from(words[words.length - 1]!)[0] ?? "") : "";
  return `${first}${last}`.toLocaleUpperCase();
}

/** FNV-1a: a small, stable hash so the same student always gets the same color. */
export function hashString(value: string): number {
  let hash = 0x811c9dc5;
  for (let i = 0; i < value.length; i++) {
    hash ^= value.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash >>> 0;
}

export interface AvatarLook {
  initials: string;
  /** Emoji from the avatar builder, when it set one. */
  emoji: string | null;
  background: string;
  color: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * The look of a generated avatar. `config` is the avatar builder's JSON (owned by the
 * clients): an `emoji` (short string) and a `color` (`#rrggbb`) are honoured when present.
 */
export function avatarLook(seed: string, name: string, config?: unknown): AvatarLook {
  const hue = hashString(seed) % 360;
  const cfg = isRecord(config) ? config : {};
  const emoji = typeof cfg.emoji === "string" && cfg.emoji.length > 0 && cfg.emoji.length <= 8 ? cfg.emoji : null;
  const custom = typeof cfg.color === "string" && HEX_COLOR.test(cfg.color) ? cfg.color : null;
  return {
    initials: initialsOf(name),
    emoji,
    background: custom ?? `hsl(${hue} 70% 86%)`,
    color: custom ? "#ffffff" : `hsl(${hue} 55% 24%)`,
  };
}
