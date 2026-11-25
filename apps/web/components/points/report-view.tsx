"use client";

import { useInfiniteQuery, useMutation, useQuery } from "@tanstack/react-query";
import { DownloadIcon, Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { EmptyNotice, ErrorNotice, LoadingRows, LoadMore } from "@/components/console/states";
import { SelectField } from "@/components/school/fields";
import { useSchool } from "@/components/school/school-context";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { errorMessage } from "@/lib/console/errors";
import { pointsPageAccess } from "@/lib/points/access";
import { downloadReportCsv, fetchCategories, fetchReport, pk, saveBlob } from "@/lib/points/queries";
import { csvFileName, isValidRange, sumPoints, type ReportFilters } from "@/lib/points/report";
import { fetchClasses, sk } from "@/lib/school/queries";

/**
 * Points per student and category over a date range, for teachers (their classes) and `school.points.read` holders.
 * Keyset paged; the CSV goes through the BFF as a Blob. Home points are never part of it.
 */
export function PointsReport() {
  const t = useTranslations("school.points.report");
  const { school, permissions, isTeacher } = useSchool();
  const access = pointsPageAccess(permissions, isTeacher);
  const [classId, setClassId] = useState("");
  const [categoryId, setCategoryId] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");

  const classes = useQuery({
    queryKey: sk.classList(school.id, { report: true, mine: !access.canSeeSchoolReport }),
    queryFn: () => fetchClasses(school.id, { ...(access.canSeeSchoolReport ? {} : { mine: true }) }, undefined, 100),
    staleTime: 60_000,
  });
  const categories = useQuery({
    queryKey: pk.categories(school.id, classId || null, "report"),
    // Editors may see deactivated skills too (old awards still refer to them).
    queryFn: () => fetchCategories(school.id, classId || null, { includeInactive: access.canManage }),
    staleTime: 60_000,
  });

  const rangeOk = isValidRange(from || undefined, to || undefined);
  // Teachers without `school.points.read` must choose a class: the school-wide report is not theirs to read.
  const needsClass = !access.canSeeSchoolReport && !classId;
  const filters: ReportFilters = { classId, categoryId, from, to };
  const enabled = rangeOk && !needsClass;

  const report = useInfiniteQuery({
    queryKey: pk.report(school.id, filters),
    queryFn: ({ pageParam }) => fetchReport(school.id, filters, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled,
    retry: false,
  });
  const rows = report.data?.pages.flatMap((p) => p.items) ?? [];

  const exportCsv = useMutation({
    mutationFn: async () => saveBlob(await downloadReportCsv(school.id, filters), csvFileName(filters)),
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
            setCategoryId("");
          }}
          noneLabel={access.canSeeSchoolReport ? t("allClasses") : t("chooseClass")}
          options={(classes.data?.items ?? []).map((c) => ({ value: c.id, label: c.name }))}
        />
        <SelectField
          label={t("category")}
          value={categoryId}
          onChange={setCategoryId}
          noneLabel={t("allCategories")}
          options={(categories.data ?? []).filter((c) => c.context !== "HOME").map((c) => ({ value: c.id, label: c.name }))}
        />
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
        <p className="text-sm text-muted-foreground">{t("homeNote")}</p>
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
            <Table data-testid="report-table">
              <caption className="sr-only">{t("caption")}</caption>
              <TableHeader>
                <TableRow>
                  <TableHead scope="col">{t("student")}</TableHead>
                  <TableHead scope="col">{t("categoryColumn")}</TableHead>
                  <TableHead scope="col" className="text-end">
                    {t("points")}
                  </TableHead>
                  <TableHead scope="col" className="text-end">
                    {t("awards")}
                  </TableHead>
                  <TableHead scope="col" className="text-end">
                    {t("reversals")}
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((row) => (
                  <TableRow key={`${row.studentId}:${row.categoryId}`}>
                    <TableCell className="font-medium">{row.studentName}</TableCell>
                    <TableCell>
                      {row.categoryName}{" "}
                      {row.polarity === "NEEDS_WORK" ? <Badge variant="outline">{t("needsWork")}</Badge> : null}
                    </TableCell>
                    <TableCell className="text-end font-bold tabular-nums">{row.points ?? 0}</TableCell>
                    <TableCell className="text-end tabular-nums">{row.awards ?? 0}</TableCell>
                    <TableCell className="text-end tabular-nums">{row.reversals ?? 0}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <p className="text-sm text-muted-foreground" data-testid="report-sum">
            {t("sum", { rows: rows.length, points: sumPoints(rows) })}
          </p>
          <LoadMore hasMore={!!report.hasNextPage} loading={report.isFetchingNextPage} onClick={() => void report.fetchNextPage()} />
        </>
      )}
    </div>
  );
}
