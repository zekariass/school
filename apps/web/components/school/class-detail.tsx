"use client";

import { useQuery } from "@tanstack/react-query";
import { ArrowLeftIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { ErrorNotice, LoadingRows } from "@/components/console/states";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Link } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { classAttendanceAbilities } from "@/lib/attendance/access";
import { classPointsAbilities } from "@/lib/points/access";
import { classAbilities, toClassCard } from "@/lib/school/class-view";
import { fetchClass, sk } from "@/lib/school/queries";
import { ClassAbsencesTab } from "@/components/attendance/class-absences-tab";
import { ClassAttendanceTab } from "@/components/attendance/class-attendance-tab";
import { ClassFeedTab } from "@/components/feed/class-feed-tab";
import { ClassPointsTab } from "@/components/points/class-points-tab";
import { ClassToolkitTab } from "@/components/points/toolkit/class-toolkit-tab";
import { ClassCodesTab } from "./class-codes";
import { ClassConsentTab } from "./consent-views";
import { ClassGroupsTab } from "./class-groups";
import { ClassRosterTab } from "./class-roster";
import { ClassSettingsTab } from "./class-settings";
import { ClassTeachersTab } from "./class-teachers";
import { useSchool } from "./school-context";

/** `initialTab` comes from `?tab=` (a notification opens the absence inbox); it applies only when the caller may see that tab. */
export function ClassDetail({ classId, initialTab, initialDate, initialSlot }: { classId: string; initialTab?: string; initialDate?: string; initialSlot?: string }) {
  const t = useTranslations("school.classDetail");
  const { school, permissions } = useSchool();
  const [tab, setTab] = useState<string | null>(initialTab ?? null);
  // The roll is mounted when first opened and then kept (hidden) so unsaved marks survive a tab switch.
  const [rollOpened, setRollOpened] = useState(false);
  const cls = useQuery({ queryKey: sk.classOne(school.id, classId), queryFn: () => fetchClass(school.id, classId) });

  if (cls.isPending) return <LoadingRows rows={4} />;
  if (cls.isError) {
    return (
      <div className="mx-auto flex max-w-4xl flex-col gap-4">
        <BackLink />
        <ErrorNotice message={errorMessage(cls.error, t("loadFailed"))} onRetry={() => void cls.refetch()} />
      </div>
    );
  }
  const data = cls.data;
  const card = toClassCard(data);
  const abilities = classAbilities(data, permissions);
  const points = classPointsAbilities(data, permissions);
  const attendance = classAttendanceAbilities(data, permissions);
  const available = new Set<string>([
    "feed",
    ...(points.canUse ? ["points", "toolkit"] : []),
    ...(attendance.canView ? ["attendance", "absences"] : []),
    "roster",
    "teachers",
    ...(abilities.canTeach ? ["groups", "codes", "consent"] : []),
    ...(abilities.canLead ? ["settings"] : []),
  ]);
  const active = tab && available.has(tab) ? tab : points.canUse ? "points" : "roster";

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <BackLink />
      <header className="flex flex-wrap items-center gap-4">
        <span
          aria-hidden
          style={{ backgroundColor: card.color }}
          className="grid size-20 shrink-0 place-items-center rounded-3xl text-5xl shadow-inner"
        >
          {card.icon}
        </span>
        <div className="min-w-0 flex-1">
          <h1 className="truncate text-3xl font-bold">{data.name}</h1>
          <p className="text-muted-foreground">{[card.grade, card.year].filter(Boolean).join(" · ")}</p>
          <div className="flex flex-wrap items-center gap-2 pt-1">
            {card.archived ? <Badge variant="outline">{t("archived")}</Badge> : null}
            <Badge variant="secondary">{t("students", { count: data.studentCount })}</Badge>
          </div>
        </div>
      </header>

      {card.archived ? (
        <p role="status" className="rounded-xl bg-muted p-3 text-sm font-medium">
          {t("archivedNote")}
        </p>
      ) : null}

      <Tabs
        value={active}
        onValueChange={(next) => {
          if (active === "attendance") setRollOpened(true);
          setTab(next);
        }}
      >
        <TabsList className="w-full justify-start overflow-x-auto group-data-[orientation=horizontal]/tabs:h-11">
          {points.canUse ? (
            <TabsTrigger value="points" className="min-h-10 flex-none px-4" data-testid="tab-points">
              {t("tabs.points")}
            </TabsTrigger>
          ) : null}
          {points.canUse ? (
            <TabsTrigger value="toolkit" className="min-h-10 flex-none px-4" data-testid="tab-toolkit">
              {t("tabs.toolkit")}
            </TabsTrigger>
          ) : null}
          {attendance.canView ? (
            <TabsTrigger value="attendance" className="min-h-10 flex-none px-4" data-testid="tab-attendance">
              {t("tabs.attendance")}
            </TabsTrigger>
          ) : null}
          {attendance.canView ? (
            <TabsTrigger value="absences" className="min-h-10 flex-none px-4" data-testid="tab-absences">
              {t("tabs.absences")}
            </TabsTrigger>
          ) : null}
          <TabsTrigger value="feed" className="min-h-10 flex-none px-4" data-testid="tab-feed">
            {t("tabs.feed")}
          </TabsTrigger>
          <TabsTrigger value="roster" className="min-h-10 flex-none px-4">
            {t("tabs.roster")}
          </TabsTrigger>
          <TabsTrigger value="teachers" className="min-h-10 flex-none px-4">
            {t("tabs.teachers")}
          </TabsTrigger>
          {abilities.canTeach ? (
            <TabsTrigger value="groups" className="min-h-10 flex-none px-4">
              {t("tabs.groups")}
            </TabsTrigger>
          ) : null}
          {abilities.canTeach ? (
            <TabsTrigger value="codes" className="min-h-10 flex-none px-4">
              {t("tabs.codes")}
            </TabsTrigger>
          ) : null}
          {abilities.canTeach ? (
            <TabsTrigger value="consent" className="min-h-10 flex-none px-4">
              {t("tabs.consent")}
            </TabsTrigger>
          ) : null}
          {abilities.canLead ? (
            <TabsTrigger value="settings" className="min-h-10 flex-none px-4">
              {t("tabs.settings")}
            </TabsTrigger>
          ) : null}
        </TabsList>
        <TabsContent value="feed" className="pt-4">
          <ClassFeedTab cls={data} />
        </TabsContent>
        {points.canUse ? (
          <TabsContent value="points" className="pt-4">
            <ClassPointsTab cls={data} />
          </TabsContent>
        ) : null}
        {points.canUse ? (
          <TabsContent value="toolkit" className="pt-4">
            <ClassToolkitTab cls={data} />
          </TabsContent>
        ) : null}
        {attendance.canView ? (
          <TabsContent value="attendance" forceMount className="pt-4 data-[state=inactive]:hidden">
            {active === "attendance" || rollOpened ? <ClassAttendanceTab cls={data} initialDate={initialDate} initialSlot={initialSlot} /> : null}
          </TabsContent>
        ) : null}
        {attendance.canView ? (
          <TabsContent value="absences" className="pt-4">
            <ClassAbsencesTab cls={data} />
          </TabsContent>
        ) : null}
        <TabsContent value="roster" className="pt-4">
          <ClassRosterTab cls={data} abilities={abilities} />
        </TabsContent>
        <TabsContent value="teachers" className="pt-4">
          <ClassTeachersTab cls={data} abilities={abilities} />
        </TabsContent>
        {abilities.canTeach ? (
          <TabsContent value="groups" className="pt-4">
            <ClassGroupsTab cls={data} />
          </TabsContent>
        ) : null}
        {abilities.canTeach ? (
          <TabsContent value="codes" className="pt-4">
            <ClassCodesTab cls={data} />
          </TabsContent>
        ) : null}
        {abilities.canTeach ? (
          <TabsContent value="consent" className="pt-4">
            <ClassConsentTab cls={data} />
          </TabsContent>
        ) : null}
        {abilities.canLead ? (
          <TabsContent value="settings" className="pt-4">
            <ClassSettingsTab cls={data} abilities={abilities} />
          </TabsContent>
        ) : null}
      </Tabs>
    </div>
  );
}

function BackLink() {
  const t = useTranslations("school.classDetail");
  const { school } = useSchool();
  return (
    <Link
      href={`/s/${encodeURIComponent(school.slug)}/classes`}
      className="inline-flex min-h-11 items-center gap-2 self-start text-sm font-semibold text-primary hover:underline"
    >
      <ArrowLeftIcon className="size-4 rtl:rotate-180" aria-hidden />
      {t("back")}
    </Link>
  );
}
