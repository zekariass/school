"use client";

import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { CalendarOffIcon, CheckIcon, XIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { useFormatDate, useFormatInstant } from "@/components/calendar-preference";
import { EmptyNotice, ErrorNotice, LoadingRows, LoadMore } from "@/components/console/states";
import { ConfirmDialog } from "@/components/school/confirm-dialog";
import { SelectField } from "@/components/school/fields";
import { useSchool } from "@/components/school/school-context";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { classAttendanceAbilities } from "@/lib/attendance/access";
import { canDecide, inboxStatusParam, isReason, isReportStatus, reportDays, type InboxFilter } from "@/lib/attendance/absence";
import { ak, decideAbsence, fetchClassAbsences, type AbsenceReport, type Decision } from "@/lib/attendance/queries";
import { errorMessage, hasProblemCode } from "@/lib/console/errors";
import type { ClassView } from "@/lib/school/queries";

const FILTERS: InboxFilter[] = ["PENDING", "ACKNOWLEDGED", "DECLINED"];

/**
 * The teachers' inbox of absence reports: waiting, acknowledged or declined. Lead, co and subject teachers and
 * attendance managers decide (once: a second decision is the API's 409 `absence.already_decided`, shown inline).
 * Reason and note are health data: they arrive only for people who may read them, and live only in this page.
 */
export function ClassAbsencesTab({ cls }: { cls: ClassView }) {
  const t = useTranslations("school.absence");
  const { school, permissions } = useSchool();
  const abilities = classAttendanceAbilities(cls, permissions);
  const queryClient = useQueryClient();
  const [filter, setFilter] = useState<InboxFilter>("PENDING");
  const [deciding, setDeciding] = useState<{ report: AbsenceReport; decision: Decision } | null>(null);
  const status = inboxStatusParam(filter);

  const list = useInfiniteQuery({
    queryKey: ak.classAbsences(school.id, cls.id, status),
    queryFn: ({ pageParam }) => fetchClassAbsences(school.id, cls.id, status, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled: abilities.canView,
    retry: false,
  });

  const decide = useMutation({
    mutationFn: ({ report, decision }: { report: AbsenceReport; decision: Decision }) => decideAbsence(school.id, report.id, decision),
    onSuccess: async (_report, { decision }) => {
      toast.success(t(decision === "acknowledge" ? "acknowledged" : "declined"));
      setDeciding(null);
      // The roll shows the new suggestion (Excused) and the dashboards do not change; both inboxes and rolls refresh.
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ak.allClassAbsences(school.id, cls.id) }),
        queryClient.invalidateQueries({ queryKey: ak.allRolls(school.id, cls.id) }),
      ]);
    },
    onError: (error) => {
      // Somebody decided first: the list is out of date.
      if (hasProblemCode(error, "absence.already_decided")) void queryClient.invalidateQueries({ queryKey: ak.allClassAbsences(school.id, cls.id) });
    },
  });

  if (!abilities.canView) return <EmptyNotice>{t("notForYou")}</EmptyNotice>;
  const items = list.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <div className="flex flex-col gap-4" data-testid="absence-inbox">
      <div className="flex flex-col gap-1">
        <h2 className="text-lg font-bold">{t("title")}</h2>
        <p className="text-sm text-muted-foreground">{t("intro")}</p>
        <p className="text-sm text-muted-foreground">{t("privacy")}</p>
      </div>
      <SelectField
        label={t("filter")}
        value={filter}
        onChange={(v) => setFilter(v as InboxFilter)}
        options={FILTERS.map((f) => ({ value: f, label: t(`filters.${f}`) }))}
        className="w-full sm:w-64"
      />
      {list.isPending ? (
        <LoadingRows rows={3} />
      ) : list.isError ? (
        <ErrorNotice message={errorMessage(list.error, t("loadFailed"))} onRetry={() => void list.refetch()} />
      ) : items.length === 0 ? (
        <EmptyNotice>{t("empty")}</EmptyNotice>
      ) : (
        <ul className="flex flex-col gap-3" aria-label={t("list")}>
          {items.map((report) => (
            <ReportCard key={report.id} report={report} canDecide={abilities.canDecide && canDecide(report.status)} onDecide={(decision) => setDeciding({ report, decision })} />
          ))}
        </ul>
      )}
      <LoadMore hasMore={!!list.hasNextPage} loading={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()} />

      {deciding ? (
        <DecisionDialog
          report={deciding.report}
          decision={deciding.decision}
          pending={decide.isPending}
          error={decide.isError ? errorMessage(decide.error, t("decideFailed")) : null}
          onConfirm={() => decide.mutate(deciding)}
          onClose={() => {
            decide.reset();
            setDeciding(null);
          }}
        />
      ) : null}
    </div>
  );
}

function useDates() {
  const t = useTranslations("school.absence");
  const formatDate = useFormatDate();
  return (report: Pick<AbsenceReport, "fromDate" | "toDate">) =>
    report.fromDate === report.toDate ? t("day", { date: formatDate(report.fromDate) }) : t("range", { from: formatDate(report.fromDate), to: formatDate(report.toDate) });
}

function ReportCard({ report, canDecide: decidable, onDecide }: { report: AbsenceReport; canDecide: boolean; onDecide: (decision: Decision) => void }) {
  const t = useTranslations("school.absence");
  const formatInstant = useFormatInstant();
  const dates = useDates();
  const status = isReportStatus(report.status) ? report.status : null;
  return (
    <li className="flex flex-col gap-3 rounded-2xl border bg-card p-4" data-testid={`absence-${report.id}`} data-status={report.status}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="flex items-center gap-2 font-semibold">
            <CalendarOffIcon className="size-4 text-muted-foreground" aria-hidden />
            {report.studentName}
          </p>
          <p className="text-sm">
            {dates(report)} · {t("days", { count: reportDays(report.fromDate, report.toDate) })}
          </p>
          <p className="text-xs text-muted-foreground">{t("reportedOn", { when: formatInstant(report.createdAt) })}</p>
        </div>
        {status ? <Badge variant={status === "SUBMITTED" ? "default" : "secondary"}>{t(`status.${status}`)}</Badge> : null}
      </div>
      {report.reasonCode && isReason(report.reasonCode) ? (
        <p className="text-sm">
          <span className="font-semibold">{t("reasonLabel")}: </span>
          {t(`reason.${report.reasonCode}`)}
        </p>
      ) : (
        <p className="text-sm text-muted-foreground">{t("detailsHidden")}</p>
      )}
      {report.note ? (
        <p className="rounded-xl bg-muted p-3 text-sm break-words">
          <span className="font-semibold">{t("noteLabel")}: </span>
          {report.note}
        </p>
      ) : null}
      {decidable ? (
        <div className="flex flex-wrap gap-2">
          <Button className="min-h-11" onClick={() => onDecide("acknowledge")} data-testid={`acknowledge-${report.id}`}>
            <CheckIcon className="size-4" aria-hidden />
            {t("acknowledge")}
          </Button>
          <Button variant="outline" className="min-h-11" onClick={() => onDecide("decline")} data-testid={`decline-${report.id}`}>
            <XIcon className="size-4" aria-hidden />
            {t("decline")}
          </Button>
        </div>
      ) : null}
    </li>
  );
}

function DecisionDialog({
  report,
  decision,
  pending,
  error,
  onConfirm,
  onClose,
}: {
  report: AbsenceReport;
  decision: Decision;
  pending: boolean;
  error: string | null;
  onConfirm: () => void;
  onClose: () => void;
}) {
  const t = useTranslations("school.absence");
  const dates = useDates();
  const key = decision === "acknowledge" ? "ack" : "dec";
  return (
    <ConfirmDialog
      title={t(`${key}.title`)}
      description={t(`${key}.body`, { name: report.studentName ?? "", dates: dates(report) })}
      confirmLabel={t(`${key}.confirm`)}
      destructive={decision === "decline"}
      pending={pending}
      error={error}
      onConfirm={onConfirm}
      onClose={onClose}
    />
  );
}
