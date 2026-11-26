import { getTranslations } from "next-intl/server";
import { CalendarPage } from "@/components/calendar/calendar-page";
import { CalendarShell } from "@/components/calendar/calendar-shell";
import { calendarSchools, calendarWorkSchools, isCalendarGuardian } from "@/lib/calendar/schools";

export async function generateMetadata() {
  const t = await getTranslations("family.calendar");
  return { title: t("title") };
}

/** The signed-in person's calendar: events, holidays, academic dates and their own events, across their schools. */
export default function CalendarRoute() {
  return (
    <CalendarShell>
      {(me) => (
        <CalendarPage
          schools={calendarSchools(me)}
          workSchools={calendarWorkSchools(me)}
          isGuardian={isCalendarGuardian(me)}
          userTimeZone={me.timezone ?? null}
        />
      )}
    </CalendarShell>
  );
}
