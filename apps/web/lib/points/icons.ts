/**
 * Skills and badges store an `icon` as a short name from a shared vocabulary ("heart", "hand-helping", ...), the
 * same on every client (the seeded defaults use it). A client shows its own drawing for each name; an emoji
 * (anything outside ASCII) is shown as it is; an unknown name falls back to the default.
 */

export const ICON_NAMES = [
  "star",
  "hand-helping",
  "hand-raised",
  "users",
  "mountain",
  "target",
  "heart",
  "book-open",
  "lightbulb",
  "smile",
  "trophy",
  "sprout",
  "award",
  "pencil",
  "calendar-check",
  "house",
  "backpack",
  "compass",
  "message-x",
  "volume-high",
] as const;

export type IconName = (typeof ICON_NAMES)[number];

/** The names offered when someone picks an icon for a new skill. */
export const SKILL_ICON_CHOICES: readonly IconName[] = [
  "star",
  "hand-helping",
  "hand-raised",
  "users",
  "mountain",
  "target",
  "heart",
  "book-open",
  "lightbulb",
  "smile",
  "trophy",
  "sprout",
];

/** The names offered for badges. */
export const BADGE_ICON_CHOICES: readonly IconName[] = [
  "award",
  "star",
  "trophy",
  "sprout",
  "smile",
  "heart",
  "lightbulb",
  "book-open",
  "users",
  "mountain",
  "target",
  "pencil",
];

export type ResolvedIcon = { kind: "name"; name: IconName } | { kind: "emoji"; text: string };

/** What to draw for a stored icon value. */
export function resolveIcon(value: string | null | undefined, fallback: IconName): ResolvedIcon {
  const text = value?.trim();
  if (!text) return { kind: "name", name: fallback };
  const known = ICON_NAMES.find((name) => name === text);
  if (known) return { kind: "name", name: known };
  // "Anything outside ASCII" is what marks an emoji.
  if (/[^\x00-\x7F]/.test(text)) return { kind: "emoji", text };
  return { kind: "name", name: fallback };
}
