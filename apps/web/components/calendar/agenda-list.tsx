"use client";

import { PlusIcon } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import type { CalendarSystem } from "@/lib/calendar/dates";
import { dayLabel } from "@/lib/calendar/format";
import { entriesOn, type CalendarItem, type DayEntry } from "@/lib/calendar/items";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { EntryButton } from "./entry";

/**
 * Days as sections with their entries: the agenda view, the month on a phone, a week on a phone and the list of the
 * selected day under the grid. `showEmpty` keeps days without entries (a week); otherwise only busy days are listed.
 */
export function AgendaList({
  days,
  buckets,
  system,
  today,
  viewerZone,
  onOpenItem,
  showEmpty = false,
  onAdd,
  headingLevel = 3,
  className,
}: {
  days: readonly string[];
  buckets: ReadonlyMap<string, DayEntry[]>;
  system: CalendarSystem;
  today: string;
  viewerZone: string | null;
  onOpenItem: (item: CalendarItem) => void;
  showEmpty?: boolean;
  /** When the caller may create events, each day gets an "add" button. */
  onAdd?: (iso: string) => void;
  headingLevel?: 2 | 3;
  className?: string;
}) {
  const t = useTranslations("family.calendar");
  const locale = useLocale();
  const Heading = headingLevel === 2 ? "h2" : "h3";
  const shown = showEmpty ? days : days.filter((iso) => entriesOn(buckets, iso).length > 0);
  if (shown.length === 0) {
    return (
      <p className="rounded-xl border border-dashed p-6 text-center text-muted-foreground" data-testid="calendar-empty">
        {t("emptyRange")}
      </p>
    );
  }
  return (
    <ol className={cn("flex flex-col gap-4", className)} data-testid="calendar-agenda">
      {shown.map((iso) => {
        const entries = entriesOn(buckets, iso);
        return (
          <li key={iso} data-iso={iso} data-testid="agenda-day">
            <section aria-labelledby={`agenda-${iso}`} className="flex flex-col gap-2">
              <div className="flex items-center justify-between gap-2">
                <Heading id={`agenda-${iso}`} className={cn("text-base font-bold", iso === today && "text-primary")}>
                  {dayLabel(iso, system, locale)}
                  {iso === today ? <span className="ms-2 rounded-full bg-primary px-2 py-0.5 text-xs font-bold text-primary-foreground">{t("today")}</span> : null}
                </Heading>
                {onAdd ? (
                  <Button type="button" size="sm" variant="ghost" onClick={() => onAdd(iso)} aria-label={t("addOn", { date: dayLabel(iso, system, locale) })}>
                    <PlusIcon className="size-4" aria-hidden />
                    {t("add")}
                  </Button>
                ) : null}
              </div>
              {entries.length === 0 ? (
                <p className="text-sm text-muted-foreground">{t("noItems")}</p>
              ) : (
                <ul className="flex flex-col gap-1.5">
                  {entries.map((entry) => (
                    <li key={`${entry.item.id}:${entry.day}`}>
                      <EntryButton entry={entry} viewerZone={viewerZone} onOpen={onOpenItem} />
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </li>
        );
      })}
    </ol>
  );
}
