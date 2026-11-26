"use client";

import { useLocale, useTranslations } from "next-intl";
import { useEffect, useRef } from "react";
import { addDays, shiftMonth, type CalendarSystem } from "@/lib/calendar/dates";
import { dayLabel, weekdayName } from "@/lib/calendar/format";
import type { DayCell } from "@/lib/calendar/grid";
import { entriesOn, type CalendarItem, type DayEntry } from "@/lib/calendar/items";
import { ethiopianMonthName } from "@school/calendar";
import { cn } from "@/lib/utils";
import { EntryButton } from "./entry";

/** How many entries a month cell shows before "+n more" (the week view shows all of them). */
const MONTH_LIMIT = 3;

/**
 * The month (several rows) or week (one row) as an ARIA grid: column headers with the weekday names, one gridcell per
 * day with a button for the day (roving tabindex) and the day's entries. Keys on a day: arrows move by a day or a week,
 * Home and End go to the start and end of the week, PageUp and PageDown to the same day of the previous or next month,
 * Enter selects the day (its entries are listed under the grid). The entries inside the cells are pointer shortcuts
 * (tabindex -1): a keyboard reaches every entry in the list of the selected day.
 */
export function DayGrid({
  label,
  weeks,
  buckets,
  system,
  focusIso,
  selectedIso,
  onFocusDay,
  onSelectDay,
  onOpenItem,
  viewerZone,
  limit = MONTH_LIMIT,
}: {
  label: string;
  weeks: DayCell[][];
  buckets: ReadonlyMap<string, DayEntry[]>;
  system: CalendarSystem;
  focusIso: string;
  selectedIso: string | null;
  /** Moves the keyboard focus to a day (the parent changes the visible month or week when the day is outside it). */
  onFocusDay: (iso: string) => void;
  onSelectDay: (iso: string) => void;
  onOpenItem: (item: CalendarItem) => void;
  viewerZone: string | null;
  /** Entries shown per cell before "+n more"; `Infinity` for all. */
  limit?: number;
}) {
  const t = useTranslations("family.calendar");
  const locale = useLocale();
  const gridRef = useRef<HTMLDivElement>(null);
  const wantFocus = useRef(false);

  // After a key moved the focus, the button of the new day (possibly in a new month) gets the real focus.
  useEffect(() => {
    if (!wantFocus.current) return;
    wantFocus.current = false;
    gridRef.current?.querySelector<HTMLButtonElement>(`button[data-iso="${focusIso}"]`)?.focus();
  }, [focusIso, weeks]);

  const header = weeks[0] ?? [];

  function move(iso: string, key: string): string | null {
    const weekIndex = weeks.findIndex((w) => w.some((c) => c.iso === iso));
    const row = weekIndex >= 0 ? weeks[weekIndex]! : null;
    switch (key) {
      case "ArrowLeft":
        return addDays(iso, -1);
      case "ArrowRight":
        return addDays(iso, 1);
      case "ArrowUp":
        return addDays(iso, -7);
      case "ArrowDown":
        return addDays(iso, 7);
      case "Home":
        return row ? row[0]!.iso : iso;
      case "End":
        return row ? row[6]!.iso : iso;
      case "PageUp":
        return shiftMonth(iso, system, -1);
      case "PageDown":
        return shiftMonth(iso, system, 1);
      default:
        return null;
    }
  }

  return (
    <div ref={gridRef} role="grid" aria-label={label} className="overflow-hidden rounded-2xl border" data-testid="calendar-grid">
      <div role="row" className="grid grid-cols-7 border-b bg-muted/50">
        {header.map((cell) => (
          <div key={cell.iso} role="columnheader" aria-label={weekdayName(cell.weekday, locale, "long")} className="px-1 py-2 text-center text-xs font-semibold text-muted-foreground sm:text-sm">
            {weekdayName(cell.weekday, locale, "short")}
          </div>
        ))}
      </div>
      {weeks.map((week) => (
        <div key={week[0]!.iso} role="row" className="grid grid-cols-7 border-b last:border-b-0">
          {week.map((cell) => {
            const entries = entriesOn(buckets, cell.iso);
            const shown = Number.isFinite(limit) ? entries.slice(0, limit) : entries;
            const hidden = entries.length - shown.length;
            const selected = selectedIso === cell.iso;
            const monthName = cell.day === 1 && system === "ethiopian" ? ethiopianMonthName(cell.month, locale) : null;
            const ariaLabel = [
              dayLabel(cell.iso, system, locale),
              cell.isToday ? t("today") : null,
              entries.length === 0 ? t("noItems") : t("itemCount", { count: entries.length }),
            ]
              .filter(Boolean)
              .join(", ");
            return (
              <div
                key={cell.iso}
                role="gridcell"
                aria-selected={selected}
                data-testid="calendar-cell"
                data-iso={cell.iso}
                data-inmonth={cell.inMonth}
                className={cn(
                  "flex min-h-24 min-w-0 flex-col gap-1 border-e p-1 last:border-e-0 sm:min-h-28",
                  !cell.inMonth && "bg-muted/40 text-muted-foreground",
                  selected && "bg-primary/5",
                )}
              >
                <button
                  type="button"
                  data-iso={cell.iso}
                  data-testid="calendar-day"
                  aria-label={ariaLabel}
                  tabIndex={cell.iso === focusIso ? 0 : -1}
                  onClick={() => onSelectDay(cell.iso)}
                  onFocus={() => {
                    if (cell.iso !== focusIso) onFocusDay(cell.iso);
                  }}
                  onKeyDown={(event) => {
                    if (event.altKey || event.ctrlKey || event.metaKey) return;
                    const next = move(cell.iso, event.key);
                    if (!next) return;
                    event.preventDefault();
                    wantFocus.current = true;
                    onFocusDay(next);
                  }}
                  className={cn(
                    "flex min-h-8 items-center gap-1 self-start rounded-full px-2 text-sm font-semibold outline-none focus-visible:ring-[3px] focus-visible:ring-ring/60",
                    cell.isToday ? "bg-primary text-primary-foreground" : "hover:bg-accent hover:text-accent-foreground",
                  )}
                >
                  {monthName ? <span className="text-xs">{monthName}</span> : null}
                  <span>{cell.day}</span>
                </button>
                <ul className="flex min-w-0 flex-col gap-0.5">
                  {shown.map((entry) => (
                    <li key={`${entry.item.id}:${entry.day}`} className="min-w-0">
                      <EntryButton entry={entry} viewerZone={viewerZone} onOpen={onOpenItem} compact tabIndex={-1} />
                    </li>
                  ))}
                </ul>
                {hidden > 0 ? (
                  <button
                    type="button"
                    tabIndex={-1}
                    onClick={() => onSelectDay(cell.iso)}
                    className="self-start rounded-md px-1.5 text-xs font-semibold text-primary hover:underline"
                    data-testid="calendar-more"
                  >
                    {t("more", { count: hidden })}
                  </button>
                ) : null}
              </div>
            );
          })}
        </div>
      ))}
    </div>
  );
}
