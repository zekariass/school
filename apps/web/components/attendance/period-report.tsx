"use client";

import { useInfiniteQuery, useMutation, useQuery } from "@tanstack/react-query";
import { DownloadIcon, Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { EmptyNotice, ErrorNotice, LoadingRows, LoadMore } from "@/components/console/states";
import { SelectField } from "@/components/school/fields";
import { useSchool } from "@/components/school/school-context";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { attendancePageAccess } from "@/lib/attendance/access";
import { periodCsvFileName, type PeriodFilters } from "@/lib/attendance/period-report";
import { activeSubjects, subjectLabel } from "@/lib/attendance/periods";
import { ak, downloadPeriodCsv, fetchDashboard, fetchPeriodReport } from "@/lib/attendance/queries";
import { isValidRange } from "@/lib/attendance/report";
import { errorMessage } from "@/lib/console/errors";
import { saveBlob } from "@/lib/points/queries";
import { fetchClasses, fetchSubjects, sk } from "@/lib/school/queries";
import { ExportError } from "./export-error";
import { useSlotLabel } from "./date-slot-bar";

/**
 * Marks counted per class, roll call and subject over school-local dates (the Periods tab). Teachers must choose a
 * class they teach; readers and managers may ask for the whole school. The roll calls offered are the ones the school
 * enabled (read from the dashboard's answer). The CSV goes through the BFF as a Blob; 429 shows the wait.
 */
export function PeriodReport() {
  const t = useTranslations("school.attendance.periodReport");
  const { school, permissions, isTeacher } = useSchool();
  const access = attendancePageAccess(permissions, isTeacher);
  const slotLabel = useSlotLabel();
  const [classId, setClassId] = useState("");
  const [slot, setSlot] = useState("");
  const [subjectId, setSubjectId] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");

  const classes = useQuery({
    queryKey: sk.classList(school.id, { attendanceReport: true, mine: !access.canSeeSchoolWide }),
    queryFn: () => fetchClasses(school.id, { ...(access.canSeeSchoolWide ? {} : { mine: true }) }, undefined, 100),
    staleTime: 60_000,
  });
  const subjects = useQuery({ queryKey: sk.subjects(school.id), queryFn: () => fetchSubjects(school.id), staleTime: 5 * 60_000, retry: false });
  // The enabled roll calls come with the dashboard's answer (one class row is enough).
  const slots = useQuery({
    queryKey: ak.slots(school.id),
    queryFn: async () => (await fetchDashboard(school.id, null, null, undefined, null, 1)).slots,
    staleTime: 5 * 60_000,
    retry: false,
  });

  const rangeOk = isValidRange(from || undefined, to || undefined);
  const needsClass = !access.canSeeSchoolWide && !classId;
  const filters: PeriodFilters = { classId, slot, subjectId, from, to };
  const enabled = rangeOk && !needsClass;

  const report = useInfiniteQuery({
    queryKey: ak.periodReport(school.id, filters),
    queryFn: ({ pageParam }) => fetchPeriodReport(school.id, filters, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled,
    retry: false,
  });
  const rows = report.data?.pages.flatMap((p) => p.items) ?? [];

  const exportCsv = useMutation({
    mutationFn: async () => saveBlob(await downloadPeriodCsv(school.id, filters), periodCsvFileName(filters)),
    onSuccess: () => toast.success(t("exported")),
  });

  return (
    <div className="flex flex-col gap-4" data-testid="period-report">
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <SelectField
          label={t("class")}
          value={classId}
          onChange={setClassId}
          noneLabel={access.canSeeSchoolWide ? t("allClasses") : t("chooseClass")}
          options={(classes.data?.items ?? []).map((c) => ({ value: c.id, label: c.name }))}
        />
        <SelectField
          label={t("slot")}
          value={slot}
          onChange={setSlot}
          noneLabel={t("allSlots")}
          options={(slots.data ?? []).map((s) => ({ value: s, label: slotLabel(s) }))}
        />
        <SelectField
          label={t("subject")}
          value={subjectId}
          onChange={setSubjectId}
          noneLabel={t("allSubjects")}
          options={activeSubjects(subjects.data).map((s) => ({ value: s.id, label: s.name }))}
        />
        <label className="flex flex-col gap-1.5 text-sm font-medium">
          {t("from")}
          <Input type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} className="h-11" data-testid="period-from" />
        </label>
        <label className="flex flex-col gap-1.5 text-sm font-medium">
          {t("to")}
          <Input type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} className="h-11" data-testid="period-to" />
        </label>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-muted-foreground">{t("rangeHint")}</p>
        <Button variant="outline" onClick={() => exportCsv.mutate()} disabled={!enabled || exportCsv.isPending} data-testid="export-period-csv">
          {exportCsv.isPending ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : <DownloadIcon className="size-4" aria-hidden />}
          {t("export")}
        </Button>
      </div>
      {exportCsv.isError ? <ExportError error={exportCsv.error} fallback={t("exportFailed")} /> : null}

      {!rangeOk ? (
        <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
          {t("badRange")}
        </p>
      ) : needsClass ? (
        <EmptyNotice>{t("needClass")}</EmptyNotice>
      ) : report.isPending ? (
        <LoadingRows rows={5} />
      ) : report.isError ? (
        <ErrorNotice message={errorMessage(report.error, t("loadFailed"))} onRetry={() => void report.refetch()} />
      ) : rows.length === 0 ? (
        <EmptyNotice>{t("empty")}</EmptyNotice>
      ) : (
        <>
          <div className="overflow-x-auto rounded-2xl border">
            <Table data-testid="period-report-table">
              <caption className="sr-only">{t("caption")}</caption>
              <TableHeader>
                <TableRow>
                  <TableHead scope="col">{t("classColumn")}</TableHead>
                  <TableHead scope="col">{t("slot")}</TableHead>
                  <TableHead scope="col">{t("subject")}</TableHead>
                  <TableHead scope="col" className="text-end">{t("rolls")}</TableHead>
                  <TableHead scope="col" className="text-end">{t("marked")}</TableHead>
                  <TableHead scope="col" className="text-end">{t("present")}</TableHead>
                  <TableHead scope="col" className="text-end">{t("absent")}</TableHead>
                  <TableHead scope="col" className="text-end">{t("late")}</TableHead>
                  <TableHead scope="col" className="text-end">{t("excused")}</TableHead>
                  <TableHead scope="col" className="text-end">{t("leftEarly")}</TableHead>
                  <TableHead scope="col" className="text-end">{t("minutesLate")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((row) => (
                  <TableRow key={`${row.classId}:${row.slot}:${row.subjectId ?? "none"}`}>
                    <TableCell className="font-medium">{row.className}</TableCell>
                    <TableCell>{slotLabel(row.slot)}</TableCell>
                    <TableCell>{subjectLabel(row) ?? t("noSubject")}</TableCell>
                    <TableCell className="text-end tabular-nums">{row.rolls ?? 0}</TableCell>
                    <TableCell className="text-end tabular-nums">{row.marked ?? 0}</TableCell>
                    <TableCell className="text-end tabular-nums">{row.present ?? 0}</TableCell>
                    <TableCell className="text-end font-bold tabular-nums">{row.absent ?? 0}</TableCell>
                    <TableCell className="text-end tabular-nums">{row.late ?? 0}</TableCell>
                    <TableCell className="text-end tabular-nums">{row.excused ?? 0}</TableCell>
                    <TableCell className="text-end tabular-nums">{row.leftEarly ?? 0}</TableCell>
                    <TableCell className="text-end tabular-nums">{row.minutesLate ?? 0}</TableCell>
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
