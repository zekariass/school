"use client";

import { useQuery } from "@tanstack/react-query";
import { CheckCircle2Icon, CircleIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Link } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { SP } from "@/lib/school/access";
import { buildChecklist } from "@/lib/school/checklist";
import { fetchClasses, fetchSummary, sk } from "@/lib/school/queries";
import { ClassCard } from "./class-card";
import { useSchool } from "./school-context";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { Skeleton } from "@/components/ui/skeleton";

/** Counts and a setup checklist for administrators; "My classes" for teachers. */
export function DashboardView() {
  const { permissions, isTeacher } = useSchool();
  const canSummary = permissions.has(SP.read);
  const isClassAdmin = permissions.has(SP.classManage);
  return (
    <div className="flex flex-col gap-6">
      {canSummary ? <AdminOverview /> : null}
      {isTeacher && !isClassAdmin ? <MyClasses /> : null}
    </div>
  );
}

function AdminOverview() {
  const t = useTranslations("school.dashboard");
  const { school, permissions } = useSchool();
  const summary = useQuery({ queryKey: sk.summary(school.id), queryFn: () => fetchSummary(school.id) });

  if (summary.isPending) {
    return (
      <div className="grid gap-4 sm:grid-cols-3" aria-hidden>
        {[0, 1, 2].map((i) => (
          <Skeleton key={i} className="h-28 rounded-2xl" />
        ))}
      </div>
    );
  }
  if (summary.isError) {
    return (
      <ErrorNotice
        message={errorMessage(summary.error, t("loadFailed"))}
        onRetry={() => void summary.refetch()}
      />
    );
  }
  const counts = summary.data;
  const base = `/s/${encodeURIComponent(school.slug)}`;
  const stats = [
    { id: "students", label: t("students"), value: counts.students, href: permissions.has(SP.students) ? `${base}/students` : null },
    { id: "teachers", label: t("teachers"), value: counts.teachers, href: permissions.has(SP.staff) ? `${base}/teachers` : null },
    { id: "classes", label: t("classes"), value: counts.classes, href: permissions.has(SP.classManage) ? `${base}/classes` : null },
  ];
  const checklist = buildChecklist(counts);
  const done = checklist.filter((c) => c.done).length;

  return (
    <>
      <div className="grid gap-4 sm:grid-cols-3">
        {stats.map((s) => {
          const body = (
            <Card className="h-full shadow-sm transition-shadow hover:shadow-md">
              <CardContent className="flex flex-col gap-1 py-5">
                <span className="text-4xl font-extrabold tabular-nums" data-testid={`stat-${s.id}`}>
                  {s.value}
                </span>
                <span className="text-sm font-semibold text-muted-foreground">{s.label}</span>
              </CardContent>
            </Card>
          );
          return s.href ? (
            <Link key={s.id} href={s.href} className="rounded-2xl focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none">
              {body}
            </Link>
          ) : (
            <div key={s.id}>{body}</div>
          );
        })}
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center justify-between gap-2 text-lg">
            {t("checklistTitle")}
            <span className="text-sm font-semibold text-muted-foreground">
              {t("checklistProgress", { done, total: checklist.length })}
            </span>
          </CardTitle>
        </CardHeader>
        <CardContent>
          <ul className="flex flex-col gap-1">
            {checklist.map((item) => {
              const href = checklistHref(item.id, base, permissions);
              const row = (
                <span className="flex min-h-11 items-center gap-3 rounded-xl px-2 py-1.5">
                  {item.done ? (
                    <CheckCircle2Icon className="size-5 shrink-0 text-success" aria-hidden />
                  ) : (
                    <CircleIcon className="size-5 shrink-0 text-muted-foreground" aria-hidden />
                  )}
                  <span className={item.done ? "text-muted-foreground line-through" : "font-semibold"}>
                    {t(`items.${item.id}`)}
                  </span>
                  <span className="sr-only">{item.done ? t("done") : t("todo")}</span>
                </span>
              );
              return (
                <li key={item.id}>
                  {href && !item.done ? (
                    <Link href={href} className="block rounded-xl hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none">
                      {row}
                    </Link>
                  ) : (
                    row
                  )}
                </li>
              );
            })}
          </ul>
        </CardContent>
      </Card>
    </>
  );
}

function checklistHref(id: string, base: string, permissions: ReadonlySet<string>): string | null {
  switch (id) {
    case "year":
    case "grades":
    case "subjects":
      return permissions.has(SP.academic) ? `${base}/academic` : null;
    case "teacher":
      return permissions.has(SP.staff) ? `${base}/teachers` : null;
    case "class":
      return permissions.has(SP.classManage) ? `${base}/classes` : null;
    case "students":
      return permissions.has(SP.students) ? `${base}/students` : null;
    default:
      return null;
  }
}

function MyClasses() {
  const t = useTranslations("school.dashboard");
  const { school } = useSchool();
  const classes = useQuery({
    queryKey: sk.classList(school.id, { mine: true, status: "ACTIVE", dashboard: true }),
    queryFn: () => fetchClasses(school.id, { mine: true, status: "ACTIVE" }, undefined, 24),
  });
  return (
    <section className="flex flex-col gap-3" aria-labelledby="my-classes-heading">
      <div className="flex items-center justify-between gap-3">
        <h2 id="my-classes-heading" className="text-lg font-semibold">
          {t("myClasses")}
        </h2>
        <Link
          href={`/s/${encodeURIComponent(school.slug)}/classes`}
          className="text-sm font-semibold text-primary underline-offset-4 hover:underline"
        >
          {t("allClasses")}
        </Link>
      </div>
      {classes.isPending ? (
        <LoadingRows rows={2} />
      ) : classes.isError ? (
        <ErrorNotice message={errorMessage(classes.error, t("loadFailed"))} onRetry={() => void classes.refetch()} />
      ) : classes.data.items.length === 0 ? (
        <EmptyNotice>{t("noClasses")}</EmptyNotice>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {classes.data.items.map((c) => (
            <ClassCard key={c.id} cls={c} />
          ))}
        </div>
      )}
    </section>
  );
}
