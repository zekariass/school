"use client";

import { keepPreviousData, useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { EmptyNotice, ErrorNotice, LoadingRows, LoadMore } from "@/components/console/states";
import { SelectField } from "@/components/school/fields";
import { useSchool } from "@/components/school/school-context";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Link } from "@/i18n/navigation";
import { activeSubjects, isPeriodSlot, subjectLabel } from "@/lib/attendance/periods";
import { ak, fetchDashboard } from "@/lib/attendance/queries";
import { dashboardState, visibleDashboardRows } from "@/lib/attendance/report";
import { errorMessage } from "@/lib/console/errors";
import { fetchSubjects, sk } from "@/lib/school/queries";
import { cn } from "@/lib/utils";
import { DateSlotBar } from "./date-slot-bar";

/**
 * Which classes took their roll on a school-local day: per class taken or not taken and the counts, with a filter
 * for the classes still waiting. Keyset paged; each class links into its roll for that day.
 */
export function DailyDashboard() {
  const t = useTranslations("school.attendance");
  const { school } = useSchool();
  const [date, setDate] = useState<string | null>(null);
  const [slot, setSlot] = useState<string | null>(null);
  const [subjectId, setSubjectId] = useState<string | null>(null);
  const [onlyNotTaken, setOnlyNotTaken] = useState(false);

  const subjects = useQuery({ queryKey: sk.subjects(school.id), queryFn: () => fetchSubjects(school.id), staleTime: 5 * 60_000, retry: false });
  const subjectOptions = activeSubjects(subjects.data);

  const dashboard = useInfiniteQuery({
    queryKey: ak.dashboard(school.id, date, slot, subjectId),
    queryFn: ({ pageParam }) => fetchDashboard(school.id, date, slot, pageParam, subjectId),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.classes.nextCursor ?? undefined,
    placeholderData: keepPreviousData,
    refetchOnWindowFocus: false,
    retry: false,
  });

  if (dashboard.isPending) return <LoadingRows rows={5} />;
  if (dashboard.isError) {
    return <ErrorNotice message={errorMessage(dashboard.error, t("dashboard.loadFailed"))} onRetry={() => void dashboard.refetch()} />;
  }

  const first = dashboard.data.pages[0]!;
  const rows = visibleDashboardRows(dashboard.data.pages.flatMap((p) => p.classes.items), onlyNotTaken);
  const totals = first.totals;
  const switching = dashboard.isFetching && dashboard.isPlaceholderData;
  const base = `/s/${encodeURIComponent(school.slug)}/classes`;
  const rollQuery = new URLSearchParams({ tab: "attendance", date: first.date, slot: first.slot }).toString();

  return (
    <div className="flex flex-col gap-4" data-testid="attendance-dashboard" aria-busy={switching}>
      <DateSlotBar
        date={first.date}
        timeZone={first.timeZone}
        slot={first.slot}
        slots={first.slots}
        busy={switching}
        onDate={setDate}
        onToday={() => setDate(null)}
        onSlot={(next) => {
          setSlot(next);
          setSubjectId(null);
        }}
      />

      {isPeriodSlot(first.slot) && subjectOptions.length > 0 ? (
        <SelectField
          label={t("periods.subjectFilter")}
          value={subjectId ?? ""}
          onChange={(v) => setSubjectId(v || null)}
          noneLabel={t("periods.anySubject")}
          options={subjectOptions.map((s) => ({ value: s.id, label: s.name }))}
          className="w-56"
          disabled={switching}
        />
      ) : null}

      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm font-medium" data-testid="dashboard-summary">
          {t("dashboard.summary", { taken: totals.classesTaken ?? 0, total: totals.classes ?? 0 })}{" "}
          {(totals.classesNotTaken ?? 0) > 0 ? t("dashboard.notTakenCount", { count: totals.classesNotTaken ?? 0 }) : null}
        </p>
        <label className="flex min-h-11 cursor-pointer items-center gap-3 text-sm font-medium">
          <input type="checkbox" className="size-5 accent-primary" checked={onlyNotTaken} onChange={(e) => setOnlyNotTaken(e.target.checked)} data-testid="not-taken-filter" />
          {t("dashboard.notTakenOnly")}
        </label>
      </div>

      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-6" aria-label={t("dashboard.totals")}>
        <Total label={t("dashboard.students")} value={totals.students} />
        <Total label={t("dashboard.present")} value={totals.present} />
        <Total label={t("dashboard.absent")} value={totals.absent} tone="text-destructive" />
        <Total label={t("dashboard.late")} value={totals.late} />
        <Total label={t("dashboard.excused")} value={totals.excused} />
        <Total label={t("dashboard.unmarked")} value={totals.unmarked} />
      </div>

      {rows.length === 0 ? (
        <EmptyNotice>{onlyNotTaken && dashboard.data.pages.some((p) => p.classes.items.length > 0) ? t("dashboard.noneNotTaken") : t("dashboard.none")}</EmptyNotice>
      ) : (
        <div className="overflow-x-auto rounded-2xl border">
          <Table data-testid="dashboard-table">
            <caption className="sr-only">{t("dashboard.caption")}</caption>
            <TableHeader>
              <TableRow>
                <TableHead scope="col">{t("dashboard.class")}</TableHead>
                <TableHead scope="col">{t("dashboard.state")}</TableHead>
                <TableHead scope="col" className="text-end">{t("dashboard.students")}</TableHead>
                <TableHead scope="col" className="text-end">{t("dashboard.present")}</TableHead>
                <TableHead scope="col" className="text-end">{t("dashboard.absent")}</TableHead>
                <TableHead scope="col" className="text-end">{t("dashboard.late")}</TableHead>
                <TableHead scope="col" className="text-end">{t("dashboard.excused")}</TableHead>
                <TableHead scope="col" className="text-end">{t("dashboard.leftEarly")}</TableHead>
                <TableHead scope="col" className="text-end">{t("dashboard.unmarked")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((row) => {
                const state = dashboardState(row);
                return (
                  <TableRow key={row.classId} data-testid={`dashboard-row-${row.classId}`} data-state={state}>
                    <TableCell className="font-medium">
                      <Link
                        href={`${base}/${row.classId}?${rollQuery}`}
                        className="inline-flex min-h-11 items-center text-primary hover:underline focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
                        aria-label={`${row.className}: ${t("dashboard.open")}`}
                      >
                        {row.className}
                      </Link>
                      {subjectLabel(row) ? (
                        <span className="block text-sm font-normal text-muted-foreground" data-testid={`dashboard-subject-${row.classId}`}>
                          {subjectLabel(row)}
                        </span>
                      ) : null}
                    </TableCell>
                    <TableCell>
                      <Badge variant={state === "taken" ? "secondary" : state === "notTaken" ? "outline" : "ghost"} className={cn(state === "notTaken" && "border-warning text-foreground")}>
                        {t(state === "taken" ? "dashboard.taken" : state === "notTaken" ? "dashboard.notTaken" : "dashboard.empty")}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-end tabular-nums">{row.students ?? 0}</TableCell>
                    <Count value={row.present} state={state} />
                    <Count value={row.absent} state={state} />
                    <Count value={row.late} state={state} />
                    <Count value={row.excused} state={state} />
                    <Count value={row.leftEarly} state={state} />
                    <Count value={row.unmarked} state={state} />
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </div>
      )}
      <LoadMore hasMore={!!dashboard.hasNextPage} loading={dashboard.isFetchingNextPage} onClick={() => void dashboard.fetchNextPage()} />
    </div>
  );
}

function Total({ label, value, tone }: { label: string; value?: number; tone?: string }) {
  return (
    <div className="rounded-xl border bg-card p-3">
      <p className="text-xs font-medium text-muted-foreground">{label}</p>
      <p className={cn("text-2xl font-extrabold tabular-nums", tone)}>{value ?? 0}</p>
    </div>
  );
}

function Count({ value, state }: { value?: number; state: ReturnType<typeof dashboardState> }) {
  return <TableCell className="text-end tabular-nums">{state === "empty" ? "–" : (value ?? 0)}</TableCell>;
}
