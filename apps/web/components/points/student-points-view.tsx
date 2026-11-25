"use client";

import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { AwardIcon, HeartIcon, SchoolIcon, SparklesIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useFormatInstant } from "@/components/calendar-preference";
import { EmptyNotice, ErrorNotice, LoadingRows, LoadMore } from "@/components/console/states";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { errorMessage } from "@/lib/console/errors";
import { categoryColor, readableOn } from "@/lib/points/categories";
import { donutSlices } from "@/lib/points/donut";
import { fetchStudentPoints, fetchTimeline, pk, type PointsSection, type StudentPoints, type TimelineItem } from "@/lib/points/queries";
import { sectionsOf } from "@/lib/points/sections";
import { cn } from "@/lib/utils";
import { CategoryIcon } from "./category-icon";
import { PointsDonut } from "./donut-chart";
import { HomeAwardButton } from "./home-award-sheet";

/**
 * A child's points: School points and Home points as two separate sections, never added together. Used by the
 * student's own screen and by the guardian's child detail. Server-side the API decides what each caller may see
 * (`home` arrives only for the student and their guardians).
 */
export function StudentPointsView({
  schoolId,
  studentId,
  audience,
  canAwardHome = false,
  heading,
}: {
  schoolId: string;
  studentId: string;
  audience: "student" | "guardian";
  /** The guardian's link allows awarding home points. */
  canAwardHome?: boolean;
  /** Shown above the sections, e.g. the school's name. */
  heading?: string;
}) {
  const t = useTranslations("family.points");
  const points = useQuery({
    queryKey: pk.student(schoolId, studentId),
    queryFn: () => fetchStudentPoints(schoolId, studentId),
    staleTime: 20_000,
  });

  if (points.isPending) return <LoadingRows rows={4} />;
  if (points.isError) return <ErrorNotice message={errorMessage(points.error, t("loadFailed"))} onRetry={() => void points.refetch()} />;
  const data = points.data;
  const { school, home } = sectionsOf(data);

  return (
    <div className="flex flex-col gap-6" data-testid="student-points">
      {heading ? (
        <h2 className="flex items-center gap-2 text-lg font-bold">
          <SchoolIcon className="size-5 text-primary" aria-hidden />
          {heading}
        </h2>
      ) : null}

      <Section
        schoolId={schoolId}
        studentId={studentId}
        kind="SCHOOL"
        section={school}
        title={t("school.title")}
        description={t(audience === "student" ? "school.descriptionStudent" : "school.descriptionGuardian")}
        testId="school-points-section"
      />

      {data.badges.length > 0 ? <BadgesEarned badges={data.badges} /> : null}

      {home ? (
        <Section
          schoolId={schoolId}
          studentId={studentId}
          kind="HOME"
          section={home}
          title={t("home.title")}
          description={t(audience === "student" ? "home.descriptionStudent" : "home.descriptionGuardian")}
          testId="home-points-section"
          action={audience === "guardian" && canAwardHome ? <HomeAwardButton schoolId={schoolId} studentId={studentId} childName={data.displayName} /> : null}
          note={t("home.note")}
        />
      ) : null}
    </div>
  );
}

function Section({
  schoolId,
  studentId,
  kind,
  section,
  title,
  description,
  testId,
  action,
  note,
}: {
  schoolId: string;
  studentId: string;
  kind: "SCHOOL" | "HOME";
  section: PointsSection;
  title: string;
  description: string;
  testId: string;
  action?: React.ReactNode;
  note?: string;
}) {
  const t = useTranslations("family.points");
  const slices = donutSlices(section.categories);
  const sum = slices.reduce((n, s) => n + s.points, 0);
  const Icon = kind === "HOME" ? HeartIcon : SchoolIcon;
  const headingId = `${testId}-title`;

  return (
    <section aria-labelledby={headingId} data-testid={testId}>
      <Card>
        <CardHeader className="flex-row flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <CardTitle id={headingId} className="flex items-center gap-2 text-xl">
              <Icon className="size-5 text-primary" aria-hidden />
              {title}
            </CardTitle>
            <p className="pt-1 text-sm text-muted-foreground">{description}</p>
          </div>
          {action}
        </CardHeader>
        <CardContent className="flex flex-col gap-5">
          {note ? (
            <p className="rounded-xl bg-muted p-3 text-sm" data-testid="home-note">
              {note}
            </p>
          ) : null}
          <div className="flex flex-wrap items-center gap-6">
            <PointsDonut categories={section.categories} total={section.total ?? 0} />
            <dl className="flex flex-col gap-1">
              <div className="flex items-baseline gap-2">
                <dt className="text-sm text-muted-foreground">{t("total")}</dt>
                <dd className="text-3xl font-extrabold tabular-nums" data-testid="section-total">
                  {section.total ?? 0}
                </dd>
              </div>
              <div className="flex gap-4 text-sm">
                <div>
                  <dt className="inline text-muted-foreground">{t("positive")} </dt>
                  <dd className="inline font-semibold tabular-nums">{section.positiveTotal ?? 0}</dd>
                </div>
                {kind === "SCHOOL" ? (
                  <div>
                    <dt className="inline text-muted-foreground">{t("needsWork")} </dt>
                    <dd className="inline font-semibold tabular-nums">{section.needsWorkTotal ?? 0}</dd>
                  </div>
                ) : null}
              </div>
            </dl>
          </div>

          {section.categories.length === 0 ? (
            <EmptyNotice>{t("noPoints")}</EmptyNotice>
          ) : (
            <div className="overflow-x-auto rounded-2xl border">
              <Table>
                <caption className="sr-only">{t("tableCaption", { section: title })}</caption>
                <TableHeader>
                  <TableRow>
                    <TableHead scope="col">{t("skill")}</TableHead>
                    <TableHead scope="col" className="text-end">
                      {t("points")}
                    </TableHead>
                    <TableHead scope="col" className="text-end">
                      {t("share")}
                    </TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {section.categories.map((c) => {
                    const share = c.polarity === "POSITIVE" && sum > 0 && (c.points ?? 0) > 0 ? Math.round(((c.points ?? 0) / sum) * 100) : null;
                    const slice = slices.find((s) => s.categoryId === c.categoryId);
                    return (
                      <TableRow key={c.categoryId}>
                        <TableCell className="font-medium">
                          <span className="inline-flex items-center gap-2">
                            <span
                              aria-hidden
                              style={{ backgroundColor: slice?.color ?? categoryColor(c), color: readableOn(slice?.color ?? categoryColor(c)) }}
                              className="grid size-7 place-items-center rounded-lg text-sm"
                            >
                              <CategoryIcon icon={c.icon} className="size-4 text-sm" />
                            </span>
                            {c.name}
                            {c.polarity === "NEEDS_WORK" ? <Badge variant="outline">{t("needsWorkTag")}</Badge> : null}
                          </span>
                        </TableCell>
                        <TableCell className="text-end font-bold tabular-nums">{c.points ?? 0}</TableCell>
                        <TableCell className="text-end tabular-nums">{share === null ? "–" : `${share}%`}</TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </div>
          )}

          <Timeline schoolId={schoolId} studentId={studentId} kind={kind} />
        </CardContent>
      </Card>
    </section>
  );
}

function Timeline({ schoolId, studentId, kind }: { schoolId: string; studentId: string; kind: "SCHOOL" | "HOME" }) {
  const t = useTranslations("family.points");
  const timeline = useInfiniteQuery({
    queryKey: pk.timeline(schoolId, studentId, kind),
    queryFn: ({ pageParam }) => fetchTimeline(schoolId, studentId, kind, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    staleTime: 20_000,
  });
  const items = timeline.data?.pages.flatMap((p) => p.items) ?? [];
  return (
    <div className="flex flex-col gap-2">
      <h3 className="text-base font-bold">{t("timeline")}</h3>
      {timeline.isPending ? (
        <LoadingRows rows={3} />
      ) : timeline.isError ? (
        <ErrorNotice message={errorMessage(timeline.error, t("timelineFailed"))} onRetry={() => void timeline.refetch()} />
      ) : items.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t("timelineEmpty")}</p>
      ) : (
        <ol className="flex flex-col gap-2" data-testid={`timeline-${kind.toLowerCase()}`}>
          {items.map((item) => (
            <TimelineRow key={item.id} item={item} />
          ))}
        </ol>
      )}
      <LoadMore hasMore={!!timeline.hasNextPage} loading={timeline.isFetchingNextPage} onClick={() => void timeline.fetchNextPage()} />
    </div>
  );
}

function TimelineRow({ item }: { item: TimelineItem }) {
  const t = useTranslations("family.points");
  const formatInstant = useFormatInstant();
  const points = item.points ?? 0;
  const color = categoryColor(item);
  return (
    <li className={cn("flex items-center gap-3 rounded-2xl border p-3", item.reversed && "opacity-60")}>
      <span aria-hidden style={{ backgroundColor: color, color: readableOn(color) }} className="grid size-10 shrink-0 place-items-center rounded-xl text-xl">
        <CategoryIcon icon={item.icon} className="size-5 text-xl" />
      </span>
      <div className="min-w-0 flex-1">
        <p className={cn("font-semibold", item.reversed && "line-through")}>
          {item.type === "REVERSAL" ? t("undoRow", { skill: item.categoryName }) : item.categoryName}
        </p>
        <p className="text-xs text-muted-foreground">
          {[item.awardedByName ? t("by", { name: item.awardedByName }) : null, item.className, formatInstant(item.createdAt)].filter(Boolean).join(" · ")}
        </p>
        {item.note ? <p className="pt-1 text-sm break-words">{item.note}</p> : null}
        {item.reversed ? <Badge variant="outline">{t("undone")}</Badge> : null}
      </div>
      <span className={cn("text-lg font-extrabold tabular-nums", points < 0 && "text-destructive")} aria-label={t("pointsChange", { points })}>
        {points > 0 ? `+${points}` : points}
      </span>
    </li>
  );
}

function BadgesEarned({ badges }: { badges: StudentPoints["badges"] }) {
  const t = useTranslations("family.points");
  const formatInstant = useFormatInstant();
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-xl">
          <AwardIcon className="size-5 text-primary" aria-hidden />
          {t("badges")}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <ul className="grid gap-3 sm:grid-cols-2" data-testid="badges-earned">
          {badges.map((badge) => (
            <li key={badge.badgeId} className="flex items-center gap-3 rounded-2xl border p-3">
              <span aria-hidden className="grid size-11 shrink-0 place-items-center rounded-xl bg-muted text-2xl">
                <CategoryIcon icon={badge.icon} fallback="award" className="size-6" />
              </span>
              <div className="min-w-0">
                <p className="font-semibold">{badge.name}</p>
                <p className="text-xs text-muted-foreground">{formatInstant(badge.awardedAt)}</p>
              </div>
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  );
}

/** The student's own page: one points view per school the student belongs to. */
export function StudentHome({ schools, name }: { schools: { schoolId: string; schoolName: string; studentId: string }[]; name: string }) {
  const t = useTranslations("family.points");
  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-6">
      <header className="flex items-center gap-3">
        <SparklesIcon className="size-8 text-primary" aria-hidden />
        <div>
          <h1 className="text-3xl font-bold">{t("studentTitle")}</h1>
          <p className="text-muted-foreground">{t("studentSubtitle", { name })}</p>
        </div>
      </header>
      {schools.length === 0 ? <EmptyNotice>{t("noSchool")}</EmptyNotice> : null}
      {schools.map((school) => (
        <StudentPointsView
          key={school.studentId}
          schoolId={school.schoolId}
          studentId={school.studentId}
          audience="student"
          heading={schools.length > 1 ? school.schoolName : undefined}
        />
      ))}
    </div>
  );
}
