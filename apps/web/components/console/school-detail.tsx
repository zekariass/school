"use client";

import { useQuery } from "@tanstack/react-query";
import { ArrowLeftIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Link } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { P } from "@/lib/console/permissions";
import { fetchSchool, queryKeys } from "@/lib/console/queries";
import { useCan } from "./console-context";
import { SchoolAdmins } from "./school-admins";
import { SchoolCampuses } from "./school-campuses";
import { SchoolConfigurationView } from "./school-configuration";
import { SchoolOverview } from "./school-overview";
import { SchoolSubscription } from "./school-subscription";
import { ErrorNotice, LoadingRows } from "./states";
import { StatusBadge } from "./status-badge";

export function SchoolDetail({ schoolId }: { schoolId: string }) {
  const t = useTranslations("console");
  const canManageRoles = useCan(P.rolesManage);
  const school = useQuery({ queryKey: queryKeys.school(schoolId), queryFn: () => fetchSchool(schoolId) });

  return (
    <div className="flex flex-col gap-6">
      <Link
        href="/platform/schools"
        className="inline-flex w-fit items-center gap-2 text-sm font-semibold text-muted-foreground hover:text-foreground"
      >
        <ArrowLeftIcon className="size-4 rtl:rotate-180" aria-hidden />
        {t("schools.backToList")}
      </Link>

      {school.isPending ? (
        <LoadingRows rows={3} />
      ) : school.isError ? (
        <ErrorNotice message={errorMessage(school.error, t("loadFailed"))} onRetry={() => void school.refetch()} />
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="text-2xl font-bold">{school.data.name}</h1>
            <StatusBadge status={school.data.status} />
            <span className="font-mono text-sm text-muted-foreground">{school.data.slug}</span>
          </div>

          <Tabs defaultValue="overview">
            <TabsList className="h-auto flex-wrap">
              <TabsTrigger value="overview">{t("schoolTabs.overview")}</TabsTrigger>
              <TabsTrigger value="campuses">{t("schoolTabs.campuses")}</TabsTrigger>
              <TabsTrigger value="subscription">{t("schoolTabs.subscription")}</TabsTrigger>
              <TabsTrigger value="configuration">{t("schoolTabs.configuration")}</TabsTrigger>
              {canManageRoles ? <TabsTrigger value="admins">{t("schoolTabs.admins")}</TabsTrigger> : null}
            </TabsList>
            <TabsContent value="overview" className="pt-4">
              <SchoolOverview school={school.data} />
            </TabsContent>
            <TabsContent value="campuses" className="pt-4">
              <SchoolCampuses school={school.data} />
            </TabsContent>
            <TabsContent value="subscription" className="pt-4">
              <SchoolSubscription school={school.data} />
            </TabsContent>
            <TabsContent value="configuration" className="pt-4">
              <SchoolConfigurationView school={school.data} />
            </TabsContent>
            {canManageRoles ? (
              <TabsContent value="admins" className="pt-4">
                <SchoolAdmins school={school.data} />
              </TabsContent>
            ) : null}
          </Tabs>
        </>
      )}
    </div>
  );
}
