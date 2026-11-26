"use client";

import { ArrowLeftIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { Link, useRouter } from "@/i18n/navigation";
import type { CalendarItem } from "@/lib/calendar/items";
import { itemRef } from "@/lib/calendar/occurrence";
import { EventDetail } from "./event-detail";
import { useViewerTimeZone } from "./use-calendar";

/**
 * An event opened from the calendar, over the page. The visible title is the event's own (in the detail); the dialog's
 * accessible name is generic on purpose: a parent meeting's title may name a child, and event text stays out of anything
 * but the card itself.
 */
export function EventDialog({
  item,
  viewerZone,
  userTimeZone,
  onClose,
}: {
  item: CalendarItem;
  viewerZone: string | null;
  userTimeZone: string | null;
  onClose: () => void;
}) {
  const t = useTranslations("family.calendar");
  const ref = itemRef(item);
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-xl" data-testid="event-dialog">
        <DialogTitle className="sr-only">{t("detail.dialogTitle")}</DialogTitle>
        <DialogDescription className="sr-only">{t("detail.dialogDescription")}</DialogDescription>
        {ref ? (
          <EventDetail eventId={ref.eventId} occurrenceId={ref.occurrenceId} viewerZone={viewerZone} userTimeZone={userTimeZone} onGone={onClose} />
        ) : (
          <p role="alert">{t("detail.loadFailed")}</p>
        )}
      </DialogContent>
    </Dialog>
  );
}

/** `/calendar/events/{id}`: the deep-link target of notifications, as a page (the id was validated by the route). */
export function EventPage({ eventId, userTimeZone }: { eventId: string; userTimeZone: string | null }) {
  const t = useTranslations("family.calendar");
  const router = useRouter();
  const viewerZone = useViewerTimeZone();
  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col gap-4">
      <Link href="/calendar" className="inline-flex min-h-11 items-center gap-2 self-start text-sm font-semibold text-primary hover:underline">
        <ArrowLeftIcon className="size-4 rtl:rotate-180" aria-hidden />
        {t("backToCalendar")}
      </Link>
      <div className="rounded-2xl border p-5">
        <EventDetail eventId={eventId} occurrenceId={null} viewerZone={viewerZone} userTimeZone={userTimeZone} onGone={() => router.push("/calendar")} />
      </div>
    </div>
  );
}
