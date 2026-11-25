"use client";

import { useTranslations } from "next-intl";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { SP } from "@/lib/school/access";
import { GradesTab, SubjectsTab } from "./academic-grades";
import { YearsTab } from "./academic-years";
import { PageHeader } from "./page-header";
import { RequirePermission } from "./require-permission";

export function AcademicView() {
  const t = useTranslations("school.academic");
  return (
    <RequirePermission anyOf={[SP.academic]}>
      <div className="mx-auto flex max-w-4xl flex-col gap-6">
        <PageHeader title={t("title")} description={t("subtitle")} />
        <Tabs defaultValue="years">
          <TabsList className="h-auto flex-wrap">
            <TabsTrigger value="years" className="min-h-10 px-4">
              {t("tabs.years")}
            </TabsTrigger>
            <TabsTrigger value="grades" className="min-h-10 px-4">
              {t("tabs.grades")}
            </TabsTrigger>
            <TabsTrigger value="subjects" className="min-h-10 px-4">
              {t("tabs.subjects")}
            </TabsTrigger>
          </TabsList>
          <TabsContent value="years" className="pt-4">
            <YearsTab />
          </TabsContent>
          <TabsContent value="grades" className="pt-4">
            <GradesTab />
          </TabsContent>
          <TabsContent value="subjects" className="pt-4">
            <SubjectsTab />
          </TabsContent>
        </Tabs>
      </div>
    </RequirePermission>
  );
}
