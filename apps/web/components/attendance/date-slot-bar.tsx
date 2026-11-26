"use client";

import { ChevronLeftIcon, ChevronRightIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useFormatDate } from "@/components/calendar-preference";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { SelectField } from "@/components/school/fields";
import { addDays, isIsoDate, localDateIn } from "@/lib/attendance/dates";
import { isKnownSlot } from "@/lib/attendance/slots";

/**
 * The day and roll call of a roll or the dashboard: previous and next day, a date field (limited to today in the
 * school's time zone, never the browser's) and the roll calls the school enabled. `date` is the school-local date the
 * API answered with.
 */
export function DateSlotBar({
  date,
  timeZone,
  slot,
  slots,
  busy,
  onDate,
  onToday,
  onSlot,
}: {
  date: string;
  timeZone: string;
  slot: string;
  slots: readonly string[];
  busy?: boolean;
  onDate: (date: string) => void;
  onToday: () => void;
  onSlot: (slot: string) => void;
}) {
  const t = useTranslations("school.attendance");
  const slotLabel = useSlotLabel();
  const formatDate = useFormatDate();
  const today = localDateIn(timeZone, new Date());
  const atToday = date >= today;

  return (
    <div className="flex flex-wrap items-end gap-3" data-testid="date-slot-bar">
      <div className="flex items-end gap-1">
        <Button variant="outline" size="icon" className="size-11" aria-label={t("date.prev")} disabled={busy} onClick={() => onDate(addDays(date, -1))} data-testid="prev-day">
          <ChevronLeftIcon className="size-5 rtl:rotate-180" aria-hidden />
        </Button>
        <label className="flex flex-col gap-1.5 text-sm font-medium">
          {t("date.label")}
          <Input
            type="date"
            value={date}
            max={today}
            disabled={busy}
            onChange={(e) => {
              if (isIsoDate(e.target.value) && e.target.value <= today) onDate(e.target.value);
            }}
            className="h-11 w-44"
            data-testid="roll-date"
          />
        </label>
        <Button variant="outline" size="icon" className="size-11" aria-label={t("date.next")} disabled={busy || atToday} onClick={() => onDate(addDays(date, 1))} data-testid="next-day">
          <ChevronRightIcon className="size-5 rtl:rotate-180" aria-hidden />
        </Button>
      </div>
      <Button variant={atToday ? "secondary" : "outline"} className="h-11" disabled={busy || atToday} onClick={onToday} data-testid="go-today">
        {t("date.today")}
      </Button>
      {slots.length > 1 ? (
        <SelectField
          label={t("slot.label")}
          value={slot}
          onChange={onSlot}
          options={slots.map((s) => ({ value: s, label: slotLabel(s) }))}
          className="w-44"
          disabled={busy}
        />
      ) : null}
      <p className="basis-full text-sm text-muted-foreground">
        {formatDate(date)} · {t("date.zone", { zone: timeZone })}
      </p>
    </div>
  );
}

/** "DAY" gives "Whole day"; a slot the screen does not know is shown as it is. */
export function useSlotLabel(): (slot: string) => string {
  const t = useTranslations("school.attendance.slot");
  return (slot) => (isKnownSlot(slot) ? t(slot) : slot);
}
