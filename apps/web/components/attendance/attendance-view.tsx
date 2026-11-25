"use client";

import { useTranslations } from "next-intl";
import { PageHeader } from "@/components/school/page-header";
import { RequirePermission } from "@/components/school/require-permission";
import { useSchool } from "@/components/school/school-context";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { attendancePageAccess } from "@/lib/attendance/access";
import { AttendanceReport } from "./attendance-report";
import { DailyDashboard } from "./daily-dashboard";

/**
 * Attendance of the school: today's dashboard (which classes took their roll) and the summary reports, for
 * attendance managers and readers (every class) and teachers (their classes). The API checks every call.
 */
export function AttendanceView() {
  const t = useTranslations("school.attendance");
  const { permissions, isTeacher } = useSchool();
  const access = attendancePageAccess(permissions, isTeacher);
  if (!access.canSee) return <RequirePermission anyOf={[]}>{null}</RequirePermission>;
  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <PageHeader title={t("page.title")} description={t("page.subtitle")} />
      <Tabs defaultValue="today">
        <TabsList className="w-full justify-start overflow-x-auto group-data-[orientation=horizontal]/tabs:h-11">
          <TabsTrigger value="today" className="min-h-10 flex-none px-4" data-testid="tab-today">
            {t("page.tabs.today")}
          </TabsTrigger>
          <TabsTrigger value="reports" className="min-h-10 flex-none px-4" data-testid="tab-reports">
            {t("page.tabs.reports")}
          </TabsTrigger>
        </TabsList>
        <TabsContent value="today" className="pt-4">
          <DailyDashboard />
        </TabsContent>
        <TabsContent value="reports" className="pt-4">
          <AttendanceReport />
        </TabsContent>
      </Tabs>
    </div>
  );
}
