import { addDays } from "@/lib/calendar/dates";
import { fetchWindow, type FetchWindow } from "@/lib/calendar/grid";
import { isCancelled, type CalendarItem } from "@/lib/calendar/items";

/** The announcement the feed pins: the newest one the API lists for the person that is published (a withdrawn one is no news). */
export function pinnedAnnouncement<T extends { status: string }>(cards: readonly T[] | undefined): T | undefined {
  return cards?.find((card) => card.status === "PUBLISHED");
}

/** How many events the "Upcoming" card of the right rail shows. */
export const UPCOMING_COUNT = 3;
/** How far ahead the card looks (days from today). */
export const UPCOMING_DAYS = 60;
/** The only source the card asks for: the events of the school (parent meetings included, as the API lets the person see them). */
export const UPCOMING_SOURCES = "EVENT";

/** The calendar window of the card: today (the viewer's date) and the next days, with the API's usual time zone slack. */
export function upcomingWindow(today: string): FetchWindow {
  return fetchWindow({ from: today, to: addDays(today, UPCOMING_DAYS) });
}

/**
 * The next events of a window the API answered: not cancelled, not over yet, soonest first. An ongoing event stays in
 * until it ends. Only a sort and a cut of what the API sent; nothing is added or guessed.
 */
export function upcomingItems(items: readonly CalendarItem[], now: Date, limit = UPCOMING_COUNT): CalendarItem[] {
  const t = now.getTime();
  return items
    .filter((item) => !isCancelled(item) && new Date(item.endAt).getTime() > t)
    .sort((a, b) => a.startAt.localeCompare(b.startAt) || a.id.localeCompare(b.id))
    .slice(0, limit);
}
