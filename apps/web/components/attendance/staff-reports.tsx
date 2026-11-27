"use client";

import { useInfiniteQuery, useMutation, useQuery } from "@tanstack/react-query";
import { DownloadIcon, Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { EmptyNotice, ErrorNotice, LoadingRows, LoadMore } from "@/components/console/states";
import { useSchool } from "@/components/school/school-context";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { defaultRange, localDateIn } from "@/lib/attendance/dates";
import { ak, downloadStaffCsv, fetchStaffDaily, fetchStaffMonthly } from "@/lib/attendance/queries";
import { isValidRange } from "@/lib/attendance/report";
import { isMonth, monthOf, staffCsvFileName, staffFailure } from "@/lib/attendance/staff";
import { errorMessage } from "@/lib/console/errors";
import { saveBlob } from "@/lib/points/queries";
import { cn } from "@/lib/utils";
import { ExportError } from "./export-error";
import { useStaffTypeLabel } from "./staff-status";

type Kind = "monthly" | "daily";

/**
 * The staff summaries (read or manage): per staff member for a month, and per school day for a range, each with a CSV
 * download (audited by the API, its own export limit: 429 shows the wait). Names are in memory only.
 */
export function StaffReports() {
  const t = useTranslations("school.attendance.staff.reports");
  const [kind, setKind] = useState<Kind>("monthly");
  return (
    <section className="flex flex-col gap-4" aria-labelledby="staff-reports-title" data-testid="staff-reports">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="staff-reports-title" className="text-lg font-bold">
          {t("title")}
        </h2>
        <div role="group" aria-label={t("kind")} className="inline-flex gap-1 rounded-xl bg-muted p-1">
          {(["monthly", "daily"] as const).map((k) => (
            <button
              key={k}
              type="button"
              aria-pressed={kind === k}
              onClick={() => setKind(k)}
              className={cn("min-h-10 rounded-lg px-4 text-sm font-semibold outline-none focus-visible:ring-[3px] focus-visible:ring-ring/60", kind === k ? "bg-background shadow-sm" : "text-muted-foreground")}
              data-testid={`staff-report-${k}`}
            >
              {t(k)}
            </button>
          ))}
        </div>
      </div>
      {kind === "monthly" ? <Monthly /> : <Daily />}
    </section>
  );
}

function Monthly() {
  const t = useTranslations("school.attendance.staff.reports");
  const { school } = useSchool();
  const typeLabel = useStaffTypeLabel();
  const [month, setMonth] = useState(() => monthOf(localDateIn(school.timezone, new Date())));
  const ok = isMonth(month);

  const report = useInfiniteQuery({
    queryKey: ak.staffMonthly(school.id, month),
    queryFn: ({ pageParam }) => fetchStaffMonthly(school.id, month, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.staff.nextCursor ?? undefined,
    enabled: ok,
    gcTime: 5 * 60_000,
    retry: false,
  });
  const exportCsv = useMutation({
    mutationFn: async () => saveBlob(await downloadStaffCsv(school.id, "monthly", { month }), staffCsvFileName("monthly", { month })),
    onSuccess: () => toast.success(t("exported")),
  });
  const rows = report.data?.pages.flatMap((p) => p.staff.items) ?? [];

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <label className="flex flex-col gap-1.5 text-sm font-medium">
          {t("month")}
          <Input type="month" value={month} onChange={(e) => setMonth(e.target.value)} className="h-11 w-44" data-testid="staff-month" />
        </label>
        <ExportButton pending={exportCsv.isPending} disabled={!ok} onClick={() => exportCsv.mutate()} label={t("export")} testId="export-staff-monthly" />
      </div>
      {exportCsv.isError ? <ExportError error={exportCsv.error} fallback={t("exportFailed")} /> : null}
      {!ok ? (
        <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
          {t("badMonth")}
        </p>
      ) : report.isPending ? (
        <LoadingRows rows={3} />
      ) : report.isError ? (
        <ReportFailure error={report.error} fallback={t("loadFailed")} onRetry={() => void report.refetch()} />
      ) : rows.length === 0 ? (
        <EmptyNotice>{t("empty")}</EmptyNotice>
      ) : (
        <>
          <div className="overflow-x-auto rounded-2xl border">
            <Table data-testid="staff-monthly-table">
              <caption className="sr-only">{t("monthlyCaption")}</caption>
              <TableHeader>
                <TableRow>
                  <TableHead scope="col">{t("name")}</TableHead>
                  <TableHead scope="col">{t("role")}</TableHead>
                  <Counters head />
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((row) => (
                  <TableRow key={row.staffId}>
                    <TableCell className="font-medium">{row.displayName}</TableCell>
                    <TableCell>{[typeLabel(row.staffType), row.jobTitle].filter(Boolean).join(" · ")}</TableCell>
                    <Counters row={row} />
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <LoadMore hasMore={!!report.hasNextPage} loading={report.isFetchingNextPage} onClick={() => void report.fetchNextPage()} />
        </>
      )}
    </div>
  );
}

function Daily() {
  const t = useTranslations("school.attendance.staff.reports");
  const { school } = useSchool();
  const initial = defaultRange(school.timezone, new Date());
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const ok = isValidRange(from || undefined, to || undefined);

  const report = useQuery({
    queryKey: ak.staffDaily(school.id, from, to),
    queryFn: () => fetchStaffDaily(school.id, from, to),
    enabled: ok,
    gcTime: 5 * 60_000,
    retry: false,
  });
  const exportCsv = useMutation({
    mutationFn: async () => saveBlob(await downloadStaffCsv(school.id, "daily", { from, to }), staffCsvFileName("daily", { from, to })),
    onSuccess: () => toast.success(t("exported")),
  });
  const rows = report.data?.rows ?? [];

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="flex flex-wrap items-end gap-3">
          <label className="flex flex-col gap-1.5 text-sm font-medium">
            {t("from")}
            <Input type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} className="h-11" data-testid="staff-from" />
          </label>
          <label className="flex flex-col gap-1.5 text-sm font-medium">
            {t("to")}
            <Input type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} className="h-11" data-testid="staff-to" />
          </label>
        </div>
        <ExportButton pending={exportCsv.isPending} disabled={!ok} onClick={() => exportCsv.mutate()} label={t("export")} testId="export-staff-daily" />
      </div>
      <p className="text-sm text-muted-foreground">{t("dailyHint")}</p>
      {exportCsv.isError ? <ExportError error={exportCsv.error} fallback={t("exportFailed")} /> : null}
      {!ok ? (
        <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
          {t("badRange")}
        </p>
      ) : report.isPending ? (
        <LoadingRows rows={3} />
      ) : report.isError ? (
        <ReportFailure error={report.error} fallback={t("loadFailed")} onRetry={() => void report.refetch()} />
      ) : rows.length === 0 ? (
        <EmptyNotice>{t("empty")}</EmptyNotice>
      ) : (
        <div className="overflow-x-auto rounded-2xl border">
          <Table data-testid="staff-daily-table">
            <caption className="sr-only">{t("dailyCaption")}</caption>
            <TableHeader>
              <TableRow>
                <TableHead scope="col">{t("date")}</TableHead>
                <Counters head />
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((row) => (
                <TableRow key={row.date}>
                  <TableCell className="font-medium tabular-nums">{row.date}</TableCell>
                  <Counters row={row} />
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  );
}

/** The counter columns both summaries share (header cells or one row's cells). */
function Counters({ head, row }: { head?: boolean; row?: { marked?: number; present?: number; absent?: number; late?: number; halfDay?: number; excused?: number; minutesLate?: number } }) {
  const t = useTranslations("school.attendance.staff.reports");
  const keys = ["marked", "present", "absent", "late", "halfDay", "excused", "minutesLate"] as const;
  return (
    <>
      {keys.map((key) =>
        head ? (
          <TableHead key={key} scope="col" className="text-end">
            {t(key)}
          </TableHead>
        ) : (
          <TableCell key={key} className="text-end tabular-nums">
            {row?.[key] ?? 0}
          </TableCell>
        ),
      )}
    </>
  );
}

function ExportButton({ pending, disabled, onClick, label, testId }: { pending: boolean; disabled: boolean; onClick: () => void; label: string; testId: string }) {
  return (
    <Button variant="outline" onClick={onClick} disabled={disabled || pending} data-testid={testId}>
      {pending ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : <DownloadIcon className="size-4" aria-hidden />}
      {label}
    </Button>
  );
}

/** A summary that cannot be shown: the setting is off or access is refused (explained once, by the day above) or a failure with the API's detail. */
function ReportFailure({ error, fallback, onRetry }: { error: unknown; fallback: string; onRetry: () => void }) {
  const t = useTranslations("school.attendance.staff");
  const failure = staffFailure(error);
  if (failure === "other") return <ErrorNotice message={errorMessage(error, fallback)} onRetry={onRetry} />;
  return <EmptyNotice>{t(`${failure}.title`)}</EmptyNotice>;
}
