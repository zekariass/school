"use client";

import { useTranslations } from "next-intl";
import { PageHeader } from "@/components/school/page-header";
import { useSchool } from "@/components/school/school-context";
import { CalendarView, type SchoolRef } from "./calendar-view";

/** `/calendar`: the signed-in person's own calendar across their schools (guardians, students, teachers, staff). */
export function CalendarPage({
  schools,
  workSchools,
  isGuardian,
  userTimeZone,
}: {
  schools: readonly SchoolRef[];
  workSchools: readonly SchoolRef[];
  isGuardian: boolean;
  userTimeZone: string | null;
}) {
  const t = useTranslations("family.calendar");
  return (
    <div className="flex flex-col gap-5">
      <PageHeader title={t("title")} description={t("subtitle")} />
      <CalendarView schools={schools} workSchools={workSchools} isGuardian={isGuardian} userTimeZone={userTimeZone} />
    </div>
  );
}

/** `/s/{school}/calendar`: the calendar of one school, with its create button (what the caller may create comes from the API). */
export function SchoolCalendarView() {
  const t = useTranslations("school.calendar");
  const { school } = useSchool();
  const ref: SchoolRef = { id: school.id, name: school.name, slug: school.slug };
  return (
    <div className="flex flex-col gap-5">
      <PageHeader title={t("workspaceTitle")} description={t("workspaceSubtitle")} />
      <CalendarView schools={[ref]} workSchools={[ref]} fixedSchool={ref} isGuardian={false} userTimeZone={null} />
    </div>
  );
}
