"use client";

import {
  BookOpenIcon,
  CalendarDaysIcon,
  ClipboardListIcon,
  FlagIcon,
  GlobeIcon,
  GraduationCapIcon,
  RepeatIcon,
  UserIcon,
  UsersRoundIcon,
} from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { timeOfDay, timeRange, zoneLabel } from "@/lib/calendar/format";
import { isCancelled, needsZoneNote, type CalendarItem, type DayEntry } from "@/lib/calendar/items";
import { cn } from "@/lib/utils";

/** Icon per kind of entry: with the label it makes the kind readable without colour. */
export function ItemIcon({ item, className }: { item: Pick<CalendarItem, "source" | "kind">; className?: string }) {
  switch (item.source) {
    case "PERSONAL_EVENT":
      return <UserIcon className={className} aria-hidden />;
    case "HOLIDAY":
      return <FlagIcon className={className} aria-hidden />;
    case "ACADEMIC_PERIOD":
      return <GraduationCapIcon className={className} aria-hidden />;
    case "EVENT":
      if (item.kind === "CLASS_EVENT") return <BookOpenIcon className={className} aria-hidden />;
      if (item.kind === "EXAM") return <ClipboardListIcon className={className} aria-hidden />;
      if (item.kind === "PARENT_MEETING") return <UsersRoundIcon className={className} aria-hidden />;
      return <CalendarDaysIcon className={className} aria-hidden />;
    default:
      return <CalendarDaysIcon className={className} aria-hidden />;
  }
}

/** Border and tint per source, from the design tokens (never a colour of its own). */
export function itemTone(item: Pick<CalendarItem, "source">): string {
  switch (item.source) {
    case "PERSONAL_EVENT":
      return "border-secondary bg-secondary/25";
    case "HOLIDAY":
      return "border-success bg-success/15";
    case "ACADEMIC_PERIOD":
      return "border-info bg-info/15";
    case "EVENT":
      return "border-primary bg-primary/10";
    default:
      return "border-border bg-muted";
  }
}

const KINDS = [
  "SCHOOL_EVENT",
  "CLASS_EVENT",
  "EXAM",
  "PARENT_MEETING",
  "OTHER",
  "NATIONAL",
  "RELIGIOUS",
  "REGIONAL",
  "SCHOOL_BREAK",
  "YEAR_START",
  "YEAR_END",
  "PERIOD_START",
  "PERIOD_END",
] as const;
const SOURCES = ["EVENT", "PERSONAL_EVENT", "HOLIDAY", "ACADEMIC_PERIOD"] as const;
const isKind = (value: string): value is (typeof KINDS)[number] => (KINDS as readonly string[]).includes(value);
const isSource = (value: string): value is (typeof SOURCES)[number] => (SOURCES as readonly string[]).includes(value);

/** The words for the kind and the source of an item (later sources fall back to a generic label). */
export function useKindLabels() {
  const t = useTranslations("family.calendar");
  return {
    kind: (item: Pick<CalendarItem, "kind" | "source">): string => {
      if (item.source === "PERSONAL_EVENT") return t("sources.PERSONAL_EVENT");
      return isKind(item.kind) ? t(`kinds.${item.kind}`) : t("sources.OTHER");
    },
    source: (source: string): string => (isSource(source) ? t(`sources.${source}`) : t("sources.OTHER")),
  };
}

/** What a chip says: the title, and for academic dates "Term starts: 2026/27". */
export function useEntryText() {
  const labels = useKindLabels();
  return (item: CalendarItem): string => (item.source === "ACADEMIC_PERIOD" ? `${labels.kind(item)}: ${item.title}` : item.title);
}

/**
 * The time line of an item in its own zone: "All day", "9:00 AM - 10:00 AM" or, on a day inside a multi-day span, only
 * what is true of that day.
 */
export function useWhen() {
  const t = useTranslations("family.calendar");
  const locale = useLocale();
  return (entry: DayEntry, compact: boolean): string => {
    const { item } = entry;
    if (item.allDay) return t("allDay");
    if (entry.days > 1) {
      if (entry.startsToday) return t("startsAt", { time: timeOfDay(item.startAt, item.timeZone, locale) });
      if (entry.endsToday) return t("endsAt", { time: timeOfDay(item.endAt, item.timeZone, locale) });
      return t("allDay");
    }
    return compact ? timeOfDay(item.startAt, item.timeZone, locale) : timeRange(item.startAt, item.endAt, item.timeZone, locale);
  };
}

/** The screen-reader name of an entry: title, kind, time, and the things the look alone says (cancelled, repeats). */
export function useEntryLabel() {
  const t = useTranslations("family.calendar");
  const text = useEntryText();
  const labels = useKindLabels();
  const when = useWhen();
  return (entry: DayEntry): string => {
    const { item } = entry;
    const parts = [
      text(item),
      item.source === "ACADEMIC_PERIOD" ? labels.source(item.source) : labels.kind(item),
      when(entry, false),
      entry.days > 1 ? t("dayOf", { n: entry.index + 1, total: entry.days }) : null,
      isCancelled(item) ? t("cancelled") : null,
      item.recurring ? t("repeats") : null,
    ];
    return parts.filter(Boolean).join(", ");
  };
}

/**
 * One item on one day, as a button that opens it (holidays and academic dates open a small read-only card). The kind is
 * an icon and a word (title attribute and screen-reader label), never colour alone; a cancelled item is struck through
 * and says so.
 */
export function EntryButton({
  entry,
  viewerZone,
  onOpen,
  compact = false,
  tabIndex,
}: {
  entry: DayEntry;
  viewerZone: string | null;
  onOpen: (item: CalendarItem) => void;
  compact?: boolean;
  tabIndex?: number;
}) {
  const t = useTranslations("family.calendar");
  const text = useEntryText();
  const label = useEntryLabel();
  const when = useWhen();
  const { item } = entry;
  const cancelled = isCancelled(item);
  const zoneNote = needsZoneNote(item, viewerZone);
  const time = item.allDay || entry.days > 1 ? null : when(entry, compact);
  const stacked = compact && !!time;
  const marks = (
    <>
      {item.recurring ? <RepeatIcon className={cn("shrink-0", compact ? "size-3" : "size-3.5")} aria-hidden /> : null}
      {zoneNote ? <GlobeIcon className={cn("shrink-0", compact ? "size-3" : "size-3.5")} aria-hidden /> : null}
    </>
  );
  return (
    <button
      type="button"
      onClick={(event) => {
        event.stopPropagation();
        onOpen(item);
      }}
      aria-label={label(entry)}
      title={label(entry)}
      tabIndex={tabIndex}
      data-testid="calendar-entry"
      data-source={item.source}
      data-cancelled={cancelled ? "true" : undefined}
      className={cn(
        "flex w-full min-w-0 rounded-md border-s-4 text-start text-foreground outline-none hover:brightness-95 focus-visible:ring-[3px] focus-visible:ring-ring/60",
        stacked ? "flex-col gap-0 px-1.5 py-0.5 text-xs" : "items-center gap-1.5",
        compact && !stacked && "min-h-6 px-1.5 py-0.5 text-xs",
        !compact && "min-h-11 px-3 py-2 text-sm",
        itemTone(item),
        cancelled && "opacity-75",
      )}
    >
      {stacked ? (
        <>
          <span className="flex min-w-0 items-center gap-1">
            <ItemIcon item={item} className="size-3 shrink-0" />
            <span className="min-w-0 flex-1 truncate font-semibold tabular-nums">{time}</span>
            {marks}
          </span>
          <span className={cn("min-w-0 truncate font-medium", cancelled && "line-through")}>{text(item)}</span>
        </>
      ) : (
        <>
          <ItemIcon item={item} className={cn("shrink-0", compact ? "size-3" : "size-4")} />
          {time ? <span className="shrink-0 font-semibold tabular-nums">{time}</span> : null}
          <span className={cn("min-w-0 flex-1 truncate font-medium", cancelled && "line-through")}>{text(item)}</span>
          {marks}
          {cancelled && !compact ? <span className="shrink-0 rounded-full bg-destructive/15 px-2 py-0.5 text-xs font-bold text-destructive">{t("cancelled")}</span> : null}
        </>
      )}
    </button>
  );
}

/** The note under a timed event whose zone differs from the viewer's: its own zone and the viewer's time. */
export function ZoneNote({ item, viewerZone }: { item: Pick<CalendarItem, "allDay" | "timeZone" | "startAt" | "endAt">; viewerZone: string | null }) {
  const t = useTranslations("family.calendar");
  const locale = useLocale();
  if (!needsZoneNote(item, viewerZone) || !viewerZone) return null;
  return (
    <p className="flex items-start gap-2 rounded-lg bg-muted p-2 text-sm text-muted-foreground" data-testid="zone-note">
      <GlobeIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
      <span>
        {t("zoneNote", {
          zone: zoneLabel(item.timeZone, item.startAt, locale),
          yours: timeRange(item.startAt, item.endAt, viewerZone, locale),
          yourZone: viewerZone,
        })}
      </span>
    </p>
  );
}
