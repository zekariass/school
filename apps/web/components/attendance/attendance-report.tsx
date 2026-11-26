"use client";

import { useInfiniteQuery, useMutation, useQuery } from "@tanstack/react-query";
import { DownloadIcon, Loader2Icon, SearchIcon, XIcon } from "lucide-react";
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
import { ak, downloadAttendanceCsv, fetchAttendanceReport } from "@/lib/attendance/queries";
import { csvFileName, isValidRange, sumReport, type ReportFilters } from "@/lib/attendance/report";
import { errorMessage } from "@/lib/console/errors";
import { saveBlob } from "@/lib/points/queries";
import { fetchClasses, fetchRoster, fetchStudents, sk } from "@/lib/school/queries";
import { useDebounced } from "@/lib/school/use-debounced";

/**
 * Attendance marks per student and class over school-local dates, for teachers (a class they teach) and for
 * attendance readers and managers (any class or the whole school). Keyset paged; the CSV goes through the BFF as a
 * Blob, `no-store`. Problems (403, 404, 429 `export rate limited`) are shown with the API's own message.
 */
export function AttendanceReport() {
  const t = useTranslations("school.attendance.report");
  const { school, permissions, isTeacher } = useSchool();
  const access = attendancePageAccess(permissions, isTeacher);
  const [classId, setClassId] = useState("");
  const [student, setStudent] = useState<{ id: string; name: string } | null>(null);
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");

  const classes = useQuery({
    queryKey: sk.classList(school.id, { attendanceReport: true, mine: !access.canSeeSchoolWide }),
    queryFn: () => fetchClasses(school.id, { ...(access.canSeeSchoolWide ? {} : { mine: true }) }, undefined, 100),
    staleTime: 60_000,
  });

  const rangeOk = isValidRange(from || undefined, to || undefined);
  // Teachers without `school.attendance.read` must choose a class: the school-wide report is not theirs to read.
  const needsClass = !access.canSeeSchoolWide && !classId;
  const filters: ReportFilters = { classId, studentId: student?.id ?? "", from, to };
  const enabled = rangeOk && !needsClass;

  const report = useInfiniteQuery({
    queryKey: ak.report(school.id, filters),
    queryFn: ({ pageParam }) => fetchAttendanceReport(school.id, filters, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled,
    retry: false,
  });
  const rows = report.data?.pages.flatMap((p) => p.items) ?? [];
  const totals = sumReport(rows);

  const exportCsv = useMutation({
    mutationFn: async () => saveBlob(await downloadAttendanceCsv(school.id, filters), csvFileName(filters)),
    onSuccess: () => toast.success(t("exported")),
  });

  return (
    <div className="flex flex-col gap-4">
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <SelectField
          label={t("class")}
          value={classId}
          onChange={(v) => {
            setClassId(v);
            setStudent(null);
          }}
          noneLabel={access.canSeeSchoolWide ? t("allClasses") : t("chooseClass")}
          options={(classes.data?.items ?? []).map((c) => ({ value: c.id, label: c.name }))}
        />
        <StudentFilter classId={classId} canSearchSchool={access.canSeeSchoolWide} value={student} onChange={setStudent} />
        <label className="flex flex-col gap-1.5 text-sm font-medium">
          {t("from")}
          <Input type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} className="h-11" data-testid="report-from" />
        </label>
        <label className="flex flex-col gap-1.5 text-sm font-medium">
          {t("to")}
          <Input type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} className="h-11" data-testid="report-to" />
        </label>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-muted-foreground">{t("rangeHint")}</p>
        <Button variant="outline" onClick={() => exportCsv.mutate()} disabled={!enabled || exportCsv.isPending} data-testid="export-csv">
          {exportCsv.isPending ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : <DownloadIcon className="size-4" aria-hidden />}
          {t("export")}
        </Button>
      </div>
      {exportCsv.isError ? (
        <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="export-error">
          {errorMessage(exportCsv.error, t("exportFailed"))}
        </p>
      ) : null}

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
            <Table data-testid="attendance-report-table">
              <caption className="sr-only">{t("caption")}</caption>
              <TableHeader>
                <TableRow>
                  <TableHead scope="col">{t("studentColumn")}</TableHead>
                  <TableHead scope="col">{t("classColumn")}</TableHead>
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
                  <TableRow key={`${row.studentId}:${row.classId}`}>
                    <TableCell className="font-medium">{row.studentName}</TableCell>
                    <TableCell>{row.className}</TableCell>
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
          <p className="text-sm text-muted-foreground" data-testid="report-sum">
            {t("sum", { rows: rows.length })} · {t("marked")} {totals.marked} · {t("absent")} {totals.absent} · {t("late")} {totals.late}
          </p>
          <LoadMore hasMore={!!report.hasNextPage} loading={report.isFetchingNextPage} onClick={() => void report.fetchNextPage()} />
        </>
      )}
    </div>
  );
}

/**
 * One student: from the class roster when a class is chosen, else a search over the school (for people who may
 * read the whole school's attendance). When the lists are not readable for this person the filter simply is not offered.
 */
function StudentFilter({
  classId,
  canSearchSchool,
  value,
  onChange,
}: {
  classId: string;
  canSearchSchool: boolean;
  value: { id: string; name: string } | null;
  onChange: (student: { id: string; name: string } | null) => void;
}) {
  const t = useTranslations("school.attendance.report");
  const { school } = useSchool();
  const [search, setSearch] = useState("");
  const q = useDebounced(search.trim());

  const roster = useQuery({
    queryKey: sk.classRoster(school.id, classId),
    queryFn: () => fetchRoster(school.id, classId),
    enabled: !!classId,
    staleTime: 60_000,
    retry: false,
  });
  const found = useQuery({
    queryKey: sk.studentList(school.id, { attendanceFilter: true, q }),
    queryFn: () => fetchStudents(school.id, { status: "ACTIVE", q }, undefined, 8),
    enabled: !classId && canSearchSchool && q.length >= 2,
    staleTime: 30_000,
    retry: false,
  });

  if (classId) {
    if (roster.isError) return null;
    return (
      <SelectField
        label={t("student")}
        value={value?.id ?? ""}
        onChange={(id) => {
          const s = roster.data?.find((r) => r.studentId === id);
          onChange(s ? { id: s.studentId, name: s.displayName } : null);
        }}
        noneLabel={t("studentAll")}
        options={(roster.data ?? []).map((s) => ({ value: s.studentId, label: s.displayName }))}
      />
    );
  }
  if (!canSearchSchool) return <div />;
  if (value) {
    return (
      <div className="flex flex-col gap-1.5 text-sm font-medium">
        {t("student")}
        <span className="flex min-h-11 items-center justify-between gap-2 rounded-lg border px-3">
          <span className="truncate">{value.name}</span>
          <button type="button" aria-label={t("studentClear")} onClick={() => onChange(null)} className="grid size-9 place-items-center rounded-full hover:bg-accent">
            <XIcon className="size-4" aria-hidden />
          </button>
        </span>
      </div>
    );
  }
  const results = found.data?.items ?? [];
  return (
    <div className="relative flex flex-col gap-1.5 text-sm font-medium">
      <label htmlFor="attendance-student-search">{t("student")}</label>
      <div className="relative">
        <SearchIcon className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden />
        <Input
          id="attendance-student-search"
          type="search"
          className="h-11 ps-9"
          placeholder={t("studentSearch")}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          data-testid="report-student-search"
        />
      </div>
      {q.length >= 2 && !found.isPending && !found.isError ? (
        <ul className="absolute top-full z-20 mt-1 max-h-56 w-full overflow-y-auto rounded-xl border bg-popover shadow-md" data-testid="report-student-results">
          {results.length === 0 ? (
            <li className="p-3 text-sm font-normal text-muted-foreground">{t("studentNone")}</li>
          ) : (
            results.map((s) => (
              <li key={s.id}>
                <button
                  type="button"
                  className="flex min-h-11 w-full items-center px-3 text-start font-medium hover:bg-accent"
                  onClick={() => {
                    onChange({ id: s.id, name: s.displayName });
                    setSearch("");
                  }}
                >
                  {s.displayName}
                </button>
              </li>
            ))
          )}
        </ul>
      ) : null}
    </div>
  );
}
