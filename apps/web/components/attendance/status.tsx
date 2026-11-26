"use client";

import { CheckIcon, ClockIcon, LogOutIcon, ShieldCheckIcon, XIcon, type LucideIcon } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { useCallback } from "react";
import { useCalendarPreference } from "@/components/calendar-preference";
import { formatInstant } from "@/lib/format-instant";
import type { AttendanceStatus } from "@/lib/attendance/status";
import { cn } from "@/lib/utils";

/**
 * How each mark looks. Always an icon and a word, never color alone; colors are the design tokens
 * (success, destructive, warning, info), so there are no per-component colors.
 */
export const STATUS_UI: Record<AttendanceStatus, { icon: LucideIcon; tile: string; icon_: string }> = {
  PRESENT: { icon: CheckIcon, tile: "border-success/50 bg-success/10", icon_: "text-success" },
  ABSENT: { icon: XIcon, tile: "border-destructive/50 bg-destructive/10", icon_: "text-destructive" },
  LATE: { icon: ClockIcon, tile: "border-warning/60 bg-warning/10", icon_: "text-warning" },
  EXCUSED: { icon: ShieldCheckIcon, tile: "border-info/50 bg-info/10", icon_: "text-info" },
  LEFT_EARLY: { icon: LogOutIcon, tile: "border-border bg-muted", icon_: "text-muted-foreground" },
};

/** A small mark label: icon and text. */
export function StatusPill({ status, label, className }: { status: AttendanceStatus; label: string; className?: string }) {
  const ui = STATUS_UI[status];
  const Icon = ui.icon;
  return (
    <span className={cn("inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-sm font-semibold", ui.tile, className)}>
      <Icon className={cn("size-4", ui.icon_)} aria-hidden />
      {label}
    </span>
  );
}

/** Formats an instant in a given IANA zone (the school's), in the viewer's calendar and language. */
export function useFormatInstantIn(): (instant: string, timeZone: string) => string {
  const locale = useLocale();
  const { calendar } = useCalendarPreference();
  return useCallback((instant, timeZone) => formatInstant(instant, { locale, calendar, timeZone }), [locale, calendar]);
}

/** "Late · 10 min": the words of a mark. */
export function useMarkText(): (status: AttendanceStatus, minutesLate?: number) => string {
  const t = useTranslations("school.attendance");
  return useCallback(
    (status, minutesLate) => {
      const label = t(`status.${status}`);
      return minutesLate ? `${label} · ${t("roll.minutesShort", { minutes: minutesLate })}` : label;
    },
    [t],
  );
}
