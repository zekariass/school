import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { CalendarShell } from "@/components/calendar/calendar-shell";
import { EventPage } from "@/components/calendar/event-dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { isUuid } from "@/lib/feed/ids";

// The title is generic on purpose: an event's own title (a parent meeting's may name a child) never goes into the document.
export async function generateMetadata() {
  const t = await getTranslations("family.calendar");
  return { title: t("detail.pageTitle") };
}

type Params = PageProps<"/[locale]/calendar/events/[eventId]">["params"];

/** One event opened from a notification or a link (`/calendar/events/{id}`): ids only, never event text, in the URL. */
export default function CalendarEventPage({ params }: PageProps<"/[locale]/calendar/events/[eventId]">) {
  return (
    <CalendarShell>
      {(me) => (
        <Suspense fallback={<Skeleton className="h-64 rounded-2xl" />}>
          <Opened params={params} timezone={me.timezone ?? me.country.defaultTimezone} />
        </Suspense>
      )}
    </CalendarShell>
  );
}

async function Opened({ params, timezone }: { params: Params; timezone: string | null }) {
  const { eventId } = await params;
  // The id goes into an API path: only a well-formed one passes.
  if (!isUuid(eventId)) notFound();
  return <EventPage eventId={eventId} userTimeZone={timezone} />;
}
