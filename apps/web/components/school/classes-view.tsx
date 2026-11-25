"use client";

import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { PlusIcon, SearchIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { EmptyNotice, ErrorNotice, LoadingRows, LoadMore } from "@/components/console/states";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { useRouter } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { canTryCreateClass } from "@/lib/school/class-view";
import { fetchClasses, fetchGrades, fetchYears, sk, type ClassFilters } from "@/lib/school/queries";
import { useDebounced } from "@/lib/school/use-debounced";
import { ClassCard } from "./class-card";
import { ClassFormDialog } from "./class-form";
import { SelectField } from "./fields";
import { PageHeader } from "./page-header";
import { useSchool } from "./school-context";
import { SP } from "@/lib/school/access";

/** Every class the caller may see, as friendly cards ("My classes" for teachers). */
export function ClassesView() {
  const t = useTranslations("school.classes");
  const { school, permissions, isTeacher } = useSchool();
  const router = useRouter();
  const isAdmin = permissions.has(SP.classManage);
  const teacherOnly = isTeacher && !isAdmin;
  const [search, setSearch] = useState("");
  const [yearId, setYearId] = useState("");
  const [gradeId, setGradeId] = useState("");
  const [status, setStatus] = useState("ACTIVE");
  const [mine, setMine] = useState(false);
  const [creating, setCreating] = useState(false);
  const q = useDebounced(search.trim());

  const filters: ClassFilters = {
    ...(yearId ? { academicYearId: yearId } : {}),
    ...(gradeId ? { gradeLevelId: gradeId } : {}),
    ...(status ? { status: status as "ACTIVE" | "ARCHIVED" } : {}),
    ...(mine ? { mine: true } : {}),
    q,
  };
  const years = useQuery({ queryKey: sk.years(school.id), queryFn: () => fetchYears(school.id), staleTime: 60_000 });
  const grades = useQuery({ queryKey: sk.grades(school.id), queryFn: () => fetchGrades(school.id), staleTime: 60_000 });
  const list = useInfiniteQuery({
    queryKey: sk.classList(school.id, filters),
    queryFn: ({ pageParam }) => fetchClasses(school.id, filters, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const rows = list.data?.pages.flatMap((p) => p.items) ?? [];
  const filtered = !!(q || yearId || gradeId || mine || status !== "ACTIVE");

  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-6">
      <PageHeader
        title={teacherOnly ? t("titleMine") : t("title")}
        description={teacherOnly ? t("subtitleMine") : t("subtitle")}
        actions={
          canTryCreateClass(permissions, isTeacher) ? (
            <Button onClick={() => setCreating(true)}>
              <PlusIcon className="size-4" aria-hidden />
              {t("create")}
            </Button>
          ) : null
        }
      />

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-[1fr_12rem_12rem_10rem_auto]">
        <div className="relative self-end sm:col-span-2 lg:col-span-1">
          <SearchIcon className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden />
          <Input
            type="search"
            aria-label={t("search")}
            placeholder={t("search")}
            className="ps-9"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
        {(years.data ?? []).length > 0 ? (
          <SelectField
            label={t("filters.year")}
            value={yearId}
            onChange={setYearId}
            noneLabel={t("filters.allYears")}
            options={(years.data ?? []).map((y) => ({ value: y.id, label: y.name }))}
          />
        ) : null}
        {(grades.data ?? []).length > 0 ? (
          <SelectField
            label={t("filters.grade")}
            value={gradeId}
            onChange={setGradeId}
            noneLabel={t("filters.allGrades")}
            options={[...(grades.data ?? [])].sort((a, b) => a.sequence - b.sequence).map((g) => ({ value: g.id, label: g.name }))}
          />
        ) : null}
        <SelectField
          label={t("filters.status")}
          value={status}
          onChange={setStatus}
          noneLabel={t("filters.allStatuses")}
          options={[
            { value: "ACTIVE", label: t("status.ACTIVE") },
            { value: "ARCHIVED", label: t("status.ARCHIVED") },
          ]}
        />
        {isTeacher && isAdmin ? (
          <label className="flex min-h-11 items-center gap-3 self-end text-sm font-semibold">
            <Switch checked={mine} onCheckedChange={setMine} />
            {t("filters.mine")}
          </label>
        ) : null}
      </div>

      {list.isPending ? (
        <LoadingRows rows={4} />
      ) : list.isError ? (
        <ErrorNotice message={errorMessage(list.error, t("loadFailed"))} onRetry={() => void list.refetch()} />
      ) : rows.length === 0 ? (
        <EmptyNotice>{filtered ? t("emptyFiltered") : teacherOnly ? t("emptyMine") : t("empty")}</EmptyNotice>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {rows.map((c) => (
            <ClassCard key={c.id} cls={c} />
          ))}
        </div>
      )}
      <LoadMore hasMore={!!list.hasNextPage} loading={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()} />

      {creating ? (
        <ClassFormDialog
          onClose={() => setCreating(false)}
          onSaved={(saved) => router.push(`/s/${encodeURIComponent(school.slug)}/classes/${saved.id}`)}
        />
      ) : null}
    </div>
  );
}
