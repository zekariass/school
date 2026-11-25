"use client";

import { useQuery } from "@tanstack/react-query";
import { SmileIcon, UsersIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { Badge } from "@/components/ui/badge";
import { Link } from "@/i18n/navigation";
import { toClassCard } from "@/lib/school/class-view";
import { fetchClassTeachers, sk, type ClassView } from "@/lib/school/queries";
import { cn } from "@/lib/utils";
import { useSchool } from "./school-context";

/** A friendly class card: icon on the class color, name, grade, who teaches it and how many students. */
export function ClassCard({ cls }: { cls: ClassView }) {
  const t = useTranslations("school.classes");
  const tRole = useTranslations("school.teacherRole");
  const { school } = useSchool();
  const card = toClassCard(cls);

  return (
    <Link
      href={`/s/${encodeURIComponent(school.slug)}/classes/${cls.id}`}
      className={cn(
        "group flex min-h-40 flex-col gap-3 rounded-2xl border bg-card p-4 shadow-sm transition-all hover:-translate-y-0.5 hover:shadow-md focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none",
        card.archived && "opacity-70",
      )}
    >
      <div className="flex items-start gap-3">
        <span
          aria-hidden
          style={{ backgroundColor: card.color }}
          className="grid size-14 shrink-0 place-items-center rounded-2xl text-3xl shadow-inner"
        >
          {card.icon}
        </span>
        <div className="min-w-0 flex-1">
          <h3 className="truncate text-lg font-bold">{card.name}</h3>
          <p className="truncate text-sm text-muted-foreground">
            {[card.grade, card.year].filter(Boolean).join(" · ") || t("noGrade")}
          </p>
        </div>
      </div>
      <TeacherNames classId={cls.id} count={card.teacherCount} />
      <div className="mt-auto flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
        <span className="inline-flex items-center gap-1.5 font-semibold text-foreground">
          <SmileIcon className="size-4" aria-hidden />
          {t("studentCount", { count: card.studentCount })}
        </span>
        <span className="inline-flex items-center gap-1.5">
          <UsersIcon className="size-4" aria-hidden />
          {t("teacherCount", { count: card.teacherCount })}
        </span>
        {card.myTeacherRole ? <Badge variant="secondary">{tRole(card.myTeacherRole)}</Badge> : null}
        {card.archived ? <Badge variant="outline">{t("archivedBadge")}</Badge> : null}
      </div>
    </Link>
  );
}

/** The lead teacher's name (and "+N"), loaded per card and cached. */
function TeacherNames({ classId, count }: { classId: string; count: number }) {
  const t = useTranslations("school.classes");
  const { school } = useSchool();
  const teachers = useQuery({
    queryKey: sk.classTeachers(school.id, classId),
    queryFn: () => fetchClassTeachers(school.id, classId),
    staleTime: 300_000,
    enabled: count > 0,
  });
  if (count === 0) return <p className="text-sm text-muted-foreground">{t("noTeachers")}</p>;
  if (!teachers.data) return <p className="h-5 text-sm text-muted-foreground" aria-hidden />;
  const [lead, ...rest] = teachers.data;
  if (!lead) return <p className="text-sm text-muted-foreground">{t("noTeachers")}</p>;
  return (
    <p className="truncate text-sm">
      <span className="font-semibold">{lead.displayName}</span>
      {rest.length > 0 ? <span className="text-muted-foreground"> {t("moreTeachers", { count: rest.length })}</span> : null}
    </p>
  );
}
