import type { Message } from "./types";

/**
 * Editing is allowed for `messaging.edit_window_minutes` after sending (0 switches it off). The API decides (`canEdit` on
 * every message and 409 `messaging.edit_window_expired`); this only counts the time down so the button disappears when the
 * window closes while the conversation stays open.
 */
export function editSecondsLeft(sentAt: string | undefined, windowMinutes: number, now: Date): number {
  if (!sentAt || windowMinutes <= 0) return 0;
  const sent = Date.parse(sentAt);
  if (Number.isNaN(sent)) return 0;
  return Math.max(0, Math.ceil((sent + windowMinutes * 60_000 - now.getTime()) / 1000));
}

/** Shows "Edit" only when the API allowed it and the window has not closed since. */
export function canEditNow(message: Pick<Message, "canEdit" | "sentAt">, windowMinutes: number, now: Date): boolean {
  return message.canEdit && editSecondsLeft(message.sentAt, windowMinutes, now) > 0;
}

/** Whole minutes left, rounded up ("1" for 61 seconds), for "You can edit for 12 more minutes". */
export function editMinutesLeft(seconds: number): number {
  return Math.max(0, Math.ceil(seconds / 60));
}

/** The next moment a list of messages needs a fresh clock: when the soonest window closes (ms from now), or null. */
export function nextEditDeadlineMs(messages: readonly Pick<Message, "canEdit" | "sentAt" | "mine">[], windowMinutes: number, now: Date): number | null {
  let soonest: number | null = null;
  for (const m of messages) {
    if (!m.mine || !m.canEdit) continue;
    const left = editSecondsLeft(m.sentAt, windowMinutes, now);
    if (left <= 0) continue;
    if (soonest === null || left < soonest) soonest = left;
  }
  return soonest === null ? null : soonest * 1000;
}
