"use client";

import { useTranslations } from "next-intl";
import { PageHeader } from "@/components/school/page-header";
import { RequirePermission } from "@/components/school/require-permission";
import { useSchool } from "@/components/school/school-context";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { pointsPageAccess } from "@/lib/points/access";
import { BadgesManager } from "./badges-manager";
import { PointsReport } from "./report-view";
import { SkillsPanel } from "./skills-manager";

/**
 * Points and badges of the school: the report (teachers for their classes, points readers for everything) and,
 * for `school.points.manage`, the school's skills and badges. The API checks every call; this only hides tabs.
 */
export function PointsView() {
  const t = useTranslations("school.points");
  const { permissions, isTeacher } = useSchool();
  const access = pointsPageAccess(permissions, isTeacher);
  if (!access.canSeeReport && !access.canManage) {
    return <RequirePermission anyOf={[]}>{null}</RequirePermission>;
  }
  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <PageHeader title={t("page.title")} description={t("page.subtitle")} />
      <Tabs defaultValue={access.canSeeReport ? "report" : "skills"}>
        <TabsList className="h-auto flex-wrap">
          {access.canSeeReport ? (
            <TabsTrigger value="report" className="min-h-10 px-4" data-testid="tab-report">
              {t("page.tabs.report")}
            </TabsTrigger>
          ) : null}
          {access.canManage ? (
            <TabsTrigger value="skills" className="min-h-10 px-4" data-testid="tab-skills">
              {t("page.tabs.skills")}
            </TabsTrigger>
          ) : null}
          {access.canManage ? (
            <TabsTrigger value="badges" className="min-h-10 px-4" data-testid="tab-badges">
              {t("page.tabs.badges")}
            </TabsTrigger>
          ) : null}
        </TabsList>
        {access.canSeeReport ? (
          <TabsContent value="report" className="pt-4">
            <PointsReport />
          </TabsContent>
        ) : null}
        {access.canManage ? (
          <TabsContent value="skills" className="pt-4">
            <SkillsPanel scope={{ kind: "school" }} />
          </TabsContent>
        ) : null}
        {access.canManage ? (
          <TabsContent value="badges" className="pt-4">
            <BadgesManager />
          </TabsContent>
        ) : null}
      </Tabs>
    </div>
  );
}
