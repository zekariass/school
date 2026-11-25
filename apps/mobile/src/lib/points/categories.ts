/** Point categories ("skills") as the award sheet and the home award sheet show them. */

export interface CategoryLike {
  id: string;
  name: string;
  icon?: string | undefined;
  color?: string | undefined;
  defaultPoints?: number | undefined;
  polarity: 'POSITIVE' | 'NEEDS_WORK';
  sortOrder?: number | undefined;
  active?: boolean | undefined;
}

const HEX_COLOR = /^#[0-9a-fA-F]{6}$/;

/** A category colour only when it is a well-formed hex colour; the screens fall back to a theme token. */
export function categoryColor(category: Pick<CategoryLike, 'color'>): string | null {
  return category.color && HEX_COLOR.test(category.color) ? category.color : null;
}

/** True when dark text reads better on this `#rrggbb` colour (WCAG relative luminance). */
export function isLightColor(hex: string): boolean {
  if (!HEX_COLOR.test(hex)) return true;
  const channel = (offset: number) => {
    const value = parseInt(hex.slice(offset, offset + 2), 16) / 255;
    return value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  };
  const luminance = 0.2126 * channel(1) + 0.7152 * channel(3) + 0.0722 * channel(5);
  // Dark text wins when its contrast with the colour is at least that of white text.
  return (luminance + 0.05) / 0.05 >= 1.05 / (luminance + 0.05);
}

/** The points an award of this category is worth by default (at least 1). */
export function defaultPointsOf(category: Pick<CategoryLike, 'defaultPoints'>): number {
  const points = category.defaultPoints;
  return points !== undefined && Number.isInteger(points) && points >= 1 ? points : 1;
}

/** Signed points of an award: needs-work is negative. `override` is the teacher's 1-10 choice, or null. */
export function signedPoints(category: Pick<CategoryLike, 'polarity' | 'defaultPoints'>, override: number | null): number {
  const magnitude = override ?? defaultPointsOf(category);
  return category.polarity === 'NEEDS_WORK' ? -magnitude : magnitude;
}

/** Positive skills first (the friendly default), each group in the school's order; inactive ones are left out. */
export function sortedCategories<T extends CategoryLike>(categories: readonly T[]): { positive: T[]; needsWork: T[] } {
  const byOrder = (a: T, b: T) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || a.name.localeCompare(b.name);
  const usable = categories.filter((category) => category.active !== false);
  return {
    positive: usable.filter((category) => category.polarity === 'POSITIVE').sort(byOrder),
    needsWork: usable.filter((category) => category.polarity === 'NEEDS_WORK').sort(byOrder),
  };
}

/** "+2" / "-1" with a real minus sign, for bubbles and timelines. */
export function formatSigned(points: number): string {
  if (points > 0) return `+${points}`;
  if (points < 0) return `−${Math.abs(points)}`;
  return '0';
}

/** The points choices of the award sheet: the category default ("null") and a few quick values. */
export const POINT_CHOICES: readonly (number | null)[] = [null, 1, 2, 3, 5];

type Icon = { ios: string; android: string };

const ICONS: Readonly<Record<string, Icon>> = {
  'hand-helping': { ios: 'hands.sparkles.fill', android: 'volunteer_activism' },
  'hand-raised': { ios: 'hand.raised.fill', android: 'front_hand' },
  users: { ios: 'person.3.fill', android: 'groups' },
  mountain: { ios: 'mountain.2.fill', android: 'landscape' },
  target: { ios: 'scope', android: 'target' },
  heart: { ios: 'heart.fill', android: 'favorite' },
  'book-open': { ios: 'book.fill', android: 'menu_book' },
  lightbulb: { ios: 'lightbulb.fill', android: 'lightbulb' },
  'volume-high': { ios: 'speaker.wave.3.fill', android: 'volume_up' },
  backpack: { ios: 'backpack.fill', android: 'backpack' },
  compass: { ios: 'safari.fill', android: 'explore' },
  'message-x': { ios: 'text.bubble.fill', android: 'chat_bubble' },
  pencil: { ios: 'pencil', android: 'edit' },
  house: { ios: 'house.fill', android: 'home' },
  smile: { ios: 'face.smiling.fill', android: 'sentiment_satisfied' },
  sprout: { ios: 'leaf.fill', android: 'eco' },
  star: { ios: 'star.fill', android: 'star' },
  trophy: { ios: 'trophy.fill', android: 'emoji_events' },
  'calendar-check': { ios: 'calendar.badge.checkmark', android: 'event_available' },
  award: { ios: 'rosette', android: 'workspace_premium' },
};

const FALLBACK_ICON: Icon = { ios: 'star.circle.fill', android: 'star' };

/** The symbol for a category or badge icon key (the backend stores client icon keys). */
export function iconFor(key: string | undefined): Icon {
  return (key ? ICONS[key] : undefined) ?? FALLBACK_ICON;
}
