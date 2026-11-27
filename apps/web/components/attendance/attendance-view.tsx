"use client";

import { useTranslations } from "next-intl";
import { PageHeader } from "@/components/school/page-header";
import { RequirePermission } from "@/components/school/require-permission";
import { useSchool } from "@/components/school/school-context";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { attendancePageAccess } from "@/lib/attendance/access";
import { AttendanceReport } from "./attendance-report";
import { DailyDashboard } from "./daily-dashboard";
import { PeriodReport } from "./period-report";
import { StaffAttendanceTab } from "./staff-tab";

/**
 * Attendance of the school: today's dashboard (which classes took their roll, per roll call and subject), the summary
 * reports and the per-period summary, for attendance managers and readers (every class) and teachers (their classes),
 * and the Staff tab for staff attendance readers and managers (never a teacher). The API checks every call.
 */
export function AttendanceView() {
  const t = useTranslations("school.attendance");
  const { permissions, isTeacher } = useSchool();
  const access = attendancePageAccess(permissions, isTeacher);
  if (!access.canSee) return <RequirePermission anyOf={[]}>{null}</RequirePermission>;
  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <PageHeader title={t("page.title")} description={t("page.subtitle")} />
      <Tabs defaultValue={access.canSeeStudents ? "today" : "staff"}>
        <TabsList className="w-full justify-start overflow-x-auto group-data-[orientation=horizontal]/tabs:h-11">
          {access.canSeeStudents ? (
            <>
              <TabsTrigger value="today" className="min-h-10 flex-none px-4" data-testid="tab-today">
                {t("page.tabs.today")}
              </TabsTrigger>
              <TabsTrigger value="reports" className="min-h-10 flex-none px-4" data-testid="tab-reports">
                {t("page.tabs.reports")}
              </TabsTrigger>
              <TabsTrigger value="periods" className="min-h-10 flex-none px-4" data-testid="tab-periods">
                {t("page.tabs.periods")}
              </TabsTrigger>
            </>
          ) : null}
          {access.canSeeStaff ? (
            <TabsTrigger value="staff" className="min-h-10 flex-none px-4" data-testid="tab-staff">
              {t("page.tabs.staff")}
            </TabsTrigger>
          ) : null}
        </TabsList>
        {access.canSeeStudents ? (
          <>
            <TabsContent value="today" className="pt-4">
              <DailyDashboard />
            </TabsContent>
            <TabsContent value="reports" className="pt-4">
              <AttendanceReport />
            </TabsContent>
            <TabsContent value="periods" className="pt-4">
              <PeriodReport />
            </TabsContent>
          </>
        ) : null}
        {access.canSeeStaff ? (
          <TabsContent value="staff" className="pt-4">
            <StaffAttendanceTab />
          </TabsContent>
        ) : null}
      </Tabs>
    </div>
  );
}
