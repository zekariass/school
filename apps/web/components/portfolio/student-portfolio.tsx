"use client";

import { useQuery } from "@tanstack/react-query";
import { PlusIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { EmptyNotice, ErrorNotice } from "@/components/console/states";
import { SelectField } from "@/components/school/fields";
import { Button } from "@/components/ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { errorMessage } from "@/lib/console/errors";
import { itemPath } from "@/lib/portfolio/paths";
import {
  fetchClassGallery,
  fetchMine,
  fetchMyClasses,
  fetchSchoolGallery,
  PORTFOLIO_GC_MS,
  pfk,
  type PortfolioStatus,
} from "@/lib/portfolio/queries";
import { STATUSES } from "@/lib/portfolio/view";
import { cn } from "@/lib/utils";
import { ItemFormDialog } from "./item-form";
import { ItemList } from "./item-list";
import { usePortfolioCapabilities } from "./use-portfolio";

export interface StudentSchool {
  schoolId: string;
  schoolName: string;
  studentId: string;
}

/**
 * A student's own portfolio: their work in every status (with what the teacher said), the class gallery and the school
 * gallery (approved items the API shows to them). "New item" appears when the API says the student may create.
 */
export function StudentPortfolio({ schools }: { schools: readonly StudentSchool[] }) {
  const t = useTranslations("school.portfolio.student");
  const [schoolId, setSchoolId] = useState(schools[0]?.schoolId ?? "");
  const school = schools.find((s) => s.schoolId === schoolId) ?? schools[0];
  if (!school) return <EmptyNotice>{t("notStudent")}</EmptyNotice>;
  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-col gap-1">
        <h1 className="text-3xl font-bold">{t("title")}</h1>
        <p className="text-muted-foreground">{t("subtitle")}</p>
      </header>
      {schools.length > 1 ? (
        <SelectField
          label={t("school")}
          value={school.schoolId}
          onChange={setSchoolId}
          options={schools.map((s) => ({ value: s.schoolId, label: s.schoolName }))}
          className="max-w-sm"
        />
      ) : (
        <p className="text-sm font-medium text-muted-foreground">{school.schoolName}</p>
      )}
      <SchoolPortfolio key={school.schoolId} school={school} />
    </div>
  );
}

function SchoolPortfolio({ school }: { school: StudentSchool }) {
  const t = useTranslations("school.portfolio.student");
  const [creating, setCreating] = useState(false);
  const caps = usePortfolioCapabilities(school.schoolId);
  const href = (item: { id: string }) => itemPath(school.schoolId, item.id);

  if (caps.isError) return <ErrorNotice message={errorMessage(caps.error, t("unavailable"))} onRetry={() => void caps.refetch()} />;
  return (
    <div className="flex flex-col gap-4">
      {caps.data?.studentMayCreate ? (
        <Button type="button" size="lg" className="self-start" onClick={() => setCreating(true)} data-testid="portfolio-new">
          <PlusIcon aria-hidden />
          {t("new")}
        </Button>
      ) : caps.data ? (
        <p role="note" className="rounded-xl bg-muted p-3 text-sm" data-testid="portfolio-create-off">
          {t("createOff")}
        </p>
      ) : null}
      <Tabs defaultValue="mine">
        <TabsList className="w-full justify-start overflow-x-auto group-data-[orientation=horizontal]/tabs:h-11">
          <TabsTrigger value="mine" className="min-h-10 flex-none px-4" data-testid="tab-portfolio-mine">
            {t("tabs.mine")}
          </TabsTrigger>
          <TabsTrigger value="class" className="min-h-10 flex-none px-4" data-testid="tab-portfolio-class">
            {t("tabs.class")}
          </TabsTrigger>
          <TabsTrigger value="school" className="min-h-10 flex-none px-4" data-testid="tab-portfolio-school">
            {t("tabs.school")}
          </TabsTrigger>
        </TabsList>
        <TabsContent value="mine" className="pt-4">
          <MyWork school={school} itemHref={href} />
        </TabsContent>
        <TabsContent value="class" className="pt-4">
          <ClassGalleryPicker school={school} itemHref={href} />
        </TabsContent>
        <TabsContent value="school" className="pt-4">
          <p className="pb-3 text-sm text-muted-foreground">{t("galleryHelp")}</p>
          <ItemList
            queryKey={pfk.schoolGallery(school.schoolId)}
            fetchPage={(cursor) => fetchSchoolGallery(school.schoolId, cursor)}
            itemHref={href}
            showStudent
            emptyText={t("schoolGalleryEmpty")}
            failedText={t("galleryFailed")}
            testId="portfolio-school-gallery"
          />
        </TabsContent>
      </Tabs>
      {creating ? <ItemFormDialog schoolId={school.schoolId} mode={{ kind: "create", selfStudentId: school.studentId }} onClose={() => setCreating(false)} /> : null}
    </div>
  );
}

function MyWork({ school, itemHref }: { school: StudentSchool; itemHref: (item: { id: string }) => string }) {
  const t = useTranslations("school.portfolio.student");
  const ts = useTranslations("school.portfolio.status");
  const [status, setStatus] = useState<PortfolioStatus | "ALL">("ALL");
  const options: (PortfolioStatus | "ALL")[] = ["ALL", ...STATUSES];
  return (
    <div className="flex flex-col gap-4">
      <div role="group" aria-label={t("filter")} className="flex flex-wrap gap-2">
        {options.map((value) => (
          <button
            key={value}
            type="button"
            aria-pressed={status === value}
            onClick={() => setStatus(value)}
            className={cn("min-h-10 rounded-full border px-4 text-sm font-medium", status === value ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent")}
            data-testid={`portfolio-filter-${value}`}
          >
            {value === "ALL" ? t("all") : ts(value)}
          </button>
        ))}
      </div>
      <ItemList
        queryKey={pfk.mine(school.schoolId, status)}
        fetchPage={(cursor) => fetchMine(school.schoolId, status === "ALL" ? undefined : status, cursor)}
        itemHref={itemHref}
        emptyText={t("mineEmpty")}
        failedText={t("mineFailed")}
        testId="portfolio-mine"
      />
    </div>
  );
}

function ClassGalleryPicker({ school, itemHref }: { school: StudentSchool; itemHref: (item: { id: string }) => string }) {
  const t = useTranslations("school.portfolio.student");
  const classes = useQuery({ queryKey: pfk.myClasses, queryFn: fetchMyClasses, staleTime: 60_000, gcTime: PORTFOLIO_GC_MS });
  const mine = (classes.data ?? []).filter((c) => c.schoolId === school.schoolId && c.role === "STUDENT" && c.status === "ACTIVE");
  const [picked, setPicked] = useState("");
  const classId = picked || mine[0]?.id || "";
  if (classes.isError) return <ErrorNotice message={errorMessage(classes.error, t("galleryFailed"))} onRetry={() => void classes.refetch()} />;
  if (!classes.isPending && mine.length === 0) return <EmptyNotice>{t("noClasses")}</EmptyNotice>;
  return (
    <div className="flex flex-col gap-4">
      {mine.length > 1 ? (
        <SelectField label={t("class")} value={classId} onChange={setPicked} options={mine.map((c) => ({ value: c.id, label: c.name }))} className="max-w-sm" />
      ) : null}
      <p className="text-sm text-muted-foreground">{t("galleryHelp")}</p>
      <ItemList
        key={classId}
        queryKey={pfk.classGallery(school.schoolId, classId || "none")}
        fetchPage={(cursor) => fetchClassGallery(school.schoolId, classId, cursor)}
        itemHref={itemHref}
        showStudent
        emptyText={t("classGalleryEmpty")}
        failedText={t("galleryFailed")}
        enabled={!!classId}
        testId="portfolio-class-gallery"
      />
    </div>
  );
}
