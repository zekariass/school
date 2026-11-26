"use client";

import { useLocale, useTranslations } from "next-intl";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import type { CalendarSystem } from "@/lib/calendar/dates";
import { dayLabel } from "@/lib/calendar/format";
import { daySpan, type CalendarItem } from "@/lib/calendar/items";
import { ItemIcon, useEntryText, useKindLabels } from "./entry";

/** A holiday or an academic date: nothing to fetch, only what the union says. */
export function InfoDialog({ item, system, onClose }: { item: CalendarItem; system: CalendarSystem; onClose: () => void }) {
  const t = useTranslations("family.calendar");
  const locale = useLocale();
  const labels = useKindLabels();
  const text = useEntryText();
  const span = daySpan(item);
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent data-testid="calendar-info">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <ItemIcon item={item} className="size-5 shrink-0" />
            {text(item)}
          </DialogTitle>
          <DialogDescription>{item.source === "ACADEMIC_PERIOD" ? labels.source(item.source) : labels.kind(item)}</DialogDescription>
        </DialogHeader>
        <p className="font-medium">
          {span.first === span.last ? dayLabel(span.first, system, locale) : t("dateRange", { from: dayLabel(span.first, system, locale), to: dayLabel(span.last, system, locale) })}
        </p>
      </DialogContent>
    </Dialog>
  );
}
