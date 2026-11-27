"use client";

import { CheckIcon, ClockIcon, ContrastIcon, ShieldCheckIcon, XIcon, type LucideIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useCallback } from "react";
import type { StaffStatus } from "@/lib/attendance/staff";
import { cn } from "@/lib/utils";

/** How each staff mark looks: an icon and a word, never color alone; colors are the design tokens. */
export const STAFF_STATUS_UI: Record<StaffStatus, { icon: LucideIcon; tile: string; iconClass: string }> = {
  PRESENT: { icon: CheckIcon, tile: "border-success/50 bg-success/10", iconClass: "text-success" },
  ABSENT: { icon: XIcon, tile: "border-destructive/50 bg-destructive/10", iconClass: "text-destructive" },
  LATE: { icon: ClockIcon, tile: "border-warning/60 bg-warning/10", iconClass: "text-warning" },
  HALF_DAY: { icon: ContrastIcon, tile: "border-border bg-muted", iconClass: "text-muted-foreground" },
  EXCUSED: { icon: ShieldCheckIcon, tile: "border-info/50 bg-info/10", iconClass: "text-info" },
};

export function StaffStatusPill({ status, minutesLate, className }: { status: StaffStatus; minutesLate?: number | null; className?: string }) {
  const t = useTranslations("school.attendance.staff");
  const ui = STAFF_STATUS_UI[status];
  const Icon = ui.icon;
  return (
    <span className={cn("inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-sm font-semibold", ui.tile, className)} data-testid="staff-status">
      <Icon className={cn("size-4", ui.iconClass)} aria-hidden />
      {t(`status.${status}`)}
      {minutesLate ? ` · ${t("history.minutes", { minutes: minutesLate })}` : ""}
    </span>
  );
}

const TYPES = ["TEACHER", "ADMINISTRATOR", "SUPPORT_STAFF"];

/** The words of a staff type (the same as the staff list); a type this screen does not know is shown as it is. */
export function useStaffTypeLabel(): (staffType: string) => string {
  const t = useTranslations("school.teachers.type");
  return useCallback((staffType) => (TYPES.includes(staffType) ? t(staffType as "TEACHER") : staffType), [t]);
}
