"use client";

import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { PlusIcon, SearchIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { EmptyNotice, ErrorNotice, LoadingRows, LoadMore } from "@/components/console/states";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Link } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { SP } from "@/lib/school/access";
import {
  fetchClasses,
  fetchGrades,
  fetchStudents,
  sk,
  type StudentFilters,
  type StudentView,
} from "@/lib/school/queries";
import { useDebounced } from "@/lib/school/use-debounced";
import { SelectField } from "./fields";
import { PageHeader } from "./page-header";
import { RequirePermission } from "./require-permission";
import { useSchool } from "./school-context";
import { StudentAvatar } from "./student-avatar";
import { StudentFormDialog } from "./student-form";

const STATUSES = ["ACTIVE", "PENDING", "TRANSFERRED", "GRADUATED", "WITHDRAWN"] as const;

export function StudentsView() {
  const t = useTranslations("school.students");
  const { school } = useSchool();
  const [search, setSearch] = useState("");
  const [classId, setClassId] = useState("");
  const [gradeLevelId, setGradeLevelId] = useState("");
  const [status, setStatus] = useState("ACTIVE");
  const [adding, setAdding] = useState(false);
  const q = useDebounced(search.trim());

  const filters: StudentFilters = {
    ...(classId ? { classId } : {}),
    ...(gradeLevelId ? { gradeLevelId } : {}),
    ...(status ? { status: status as NonNullable<StudentView["status"]> } : {}),
    q,
  };
  const classes = useQuery({
    queryKey: sk.classList(school.id, { picker: true }),
    queryFn: () => fetchClasses(school.id, {}, undefined, 100),
    staleTime: 60_000,
  });
  const grades = useQuery({ queryKey: sk.grades(school.id), queryFn: () => fetchGrades(school.id), staleTime: 60_000 });
  const list = useInfiniteQuery({
    queryKey: sk.studentList(school.id, filters),
    queryFn: ({ pageParam }) => fetchStudents(school.id, filters, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const rows = list.data?.pages.flatMap((p) => p.items) ?? [];
  const filtered = !!(q || classId || gradeLevelId || status !== "ACTIVE");
  const base = `/s/${encodeURIComponent(school.slug)}`;

  return (
    <RequirePermission anyOf={[SP.students]}>
      <div className="mx-auto flex max-w-5xl flex-col gap-6">
        <PageHeader
          title={t("title")}
          description={t("subtitle")}
          actions={
            <Button onClick={() => setAdding(true)}>
              <PlusIcon className="size-4" aria-hidden />
              {t("add")}
            </Button>
          }
        />

        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
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
          <SelectField
            label={t("filters.class")}
            value={classId}
            onChange={setClassId}
            noneLabel={t("filters.allClasses")}
            options={(classes.data?.items ?? []).map((c) => ({ value: c.id, label: c.name }))}
          />
          <SelectField
            label={t("filters.grade")}
            value={gradeLevelId}
            onChange={setGradeLevelId}
            noneLabel={t("filters.allGrades")}
            options={[...(grades.data ?? [])].sort((a, b) => a.sequence - b.sequence).map((g) => ({ value: g.id, label: g.name }))}
          />
          <SelectField
            label={t("filters.status")}
            value={status}
            onChange={setStatus}
            noneLabel={t("filters.allStatuses")}
            options={STATUSES.map((s) => ({ value: s, label: t(`status.${s}`) }))}
          />
        </div>

        {list.isPending ? (
          <LoadingRows rows={6} />
        ) : list.isError ? (
          <ErrorNotice message={errorMessage(list.error, t("loadFailed"))} onRetry={() => void list.refetch()} />
        ) : rows.length === 0 ? (
          <EmptyNotice>{filtered ? t("emptyFiltered") : t("empty")}</EmptyNotice>
        ) : (
          <ul className="grid gap-2 md:grid-cols-2">
            {rows.map((student) => (
              <li key={student.id}>
                <Link
                  href={`${base}/students/${student.id}`}
                  className="flex min-h-16 items-center gap-3 rounded-2xl border bg-card p-3 transition-colors outline-none hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring/50"
                >
                  <StudentAvatar
                    id={student.id}
                    name={student.displayName}
                    mediaId={student.avatar?.id}
                    config={student.avatarConfig}
                    size={48}
                  />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-semibold">{student.displayName}</span>
                    <span className="flex flex-wrap items-center gap-1 pt-0.5">
                      {student.studentNumber ? (
                        <span className="font-mono text-xs text-muted-foreground">#{student.studentNumber}</span>
                      ) : null}
                      {(student.classes ?? []).map((c) => (
                        <Badge key={c.id} variant="secondary">
                          {c.name}
                        </Badge>
                      ))}
                      {student.status && student.status !== "ACTIVE" ? (
                        <Badge variant="outline">{t(`status.${student.status}`)}</Badge>
                      ) : null}
                    </span>
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
        <LoadMore hasMore={!!list.hasNextPage} loading={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()} />

        {adding ? (
          <StudentFormDialog onClose={() => setAdding(false)} />
        ) : null}
      </div>
    </RequirePermission>
  );
}
