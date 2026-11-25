/** Routines and checklists of the toolkit: editing drafts, what is sent, and the progress of a tick-list. */

export interface ItemDraft {
  /** Local key only (never sent). */
  key: string;
  text: string;
  /** Empty string for "no time"; otherwise whole minutes as typed. */
  minutes: string;
}

export interface ItemBody {
  text: string;
  minutes?: number;
}

export const MAX_ITEMS = 100;
export const MAX_MINUTES = 600;
export const MAX_TEXT = 300;

/** Blank rows are dropped, text is trimmed and cut to the API's limit, minutes become 0..600 or are left out. */
export function itemBodies(drafts: readonly ItemDraft[]): ItemBody[] {
  const out: ItemBody[] = [];
  for (const draft of drafts) {
    const text = draft.text.trim().slice(0, MAX_TEXT);
    if (!text) continue;
    const parsed = draft.minutes.trim() === "" ? NaN : Math.round(Number(draft.minutes));
    out.push(Number.isFinite(parsed) && parsed > 0 ? { text, minutes: Math.min(parsed, MAX_MINUTES) } : { text });
    if (out.length >= MAX_ITEMS) break;
  }
  return out;
}

export function draftsFrom(items: readonly { text?: string; minutes?: number }[], newKey: () => string): ItemDraft[] {
  return items.map((item) => ({ key: newKey(), text: item.text ?? "", minutes: item.minutes ? String(item.minutes) : "" }));
}

/** Moves a draft one place up (-1) or down (+1); out-of-range moves leave the list as it is. */
export function moveDraft<T>(list: readonly T[], index: number, direction: -1 | 1): T[] {
  const target = index + direction;
  if (index < 0 || index >= list.length || target < 0 || target >= list.length) return list.slice();
  const next = list.slice();
  [next[index], next[target]] = [next[target]!, next[index]!];
  return next;
}

export interface Progress {
  done: number;
  total: number;
  /** 0..1 */
  ratio: number;
  complete: boolean;
}

export function progress(total: number, checked: ReadonlySet<number>): Progress {
  const done = [...checked].filter((i) => i >= 0 && i < total).length;
  return { done, total, ratio: total === 0 ? 0 : done / total, complete: total > 0 && done === total };
}

/** Minutes the whole routine takes (items without a time count as 0). */
export const totalMinutes = (items: readonly { minutes?: number }[]): number => items.reduce((n, i) => n + (i.minutes ?? 0), 0);

/** Visibility choices the user may make: school-wide sharing needs `school.points.manage`. */
export function visibilityChoices(canShareSchoolWide: boolean): ("PRIVATE" | "CLASS" | "SCHOOL")[] {
  return canShareSchoolWide ? ["PRIVATE", "CLASS", "SCHOOL"] : ["PRIVATE", "CLASS"];
}

/**
 * The templates the toolkit of one class shows: the teacher's own, the school-wide ones and those shared with
 * THIS class. (The API's `classId` filter returns only that class's own templates, so the list is asked
 * without it and narrowed here.)
 */
export function templatesForClass<T extends { visibility: "PRIVATE" | "CLASS" | "SCHOOL"; classId?: string | null }>(
  templates: readonly T[],
  classId: string,
): T[] {
  return templates.filter((t) => t.visibility !== "CLASS" || t.classId === classId);
}
