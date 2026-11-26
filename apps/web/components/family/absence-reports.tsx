"use client";

import { useInfiniteQuery } from "@tanstack/react-query";
import { CalendarOffIcon, PlusIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { ak, cancelAbsence, fetchStudentAbsences, reportAbsence, type AbsenceReport } from "@/lib/attendance/queries";
import { buildAbsenceRequest, canCancel, hasErrors, isReason, isReportStatus, MAX_ABSENCE_NOTE, REASONS, validateAbsenceForm, type AbsenceForm, type AbsenceFormErrors } from "@/lib/attendance/absence";
import { localDateIn } from "@/lib/attendance/dates";
import { DISPLAY_TIME_ZONE } from "@/lib/format-instant";
import { errorMessage, fieldErrors } from "@/lib/console/errors";
import { useFormatDate } from "@/components/calendar-preference";
import { FormField } from "@/components/console/form-field";
import { EmptyNotice, ErrorNotice, LoadingRows, LoadMore } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { ConfirmDialog } from "@/components/school/confirm-dialog";
import { SelectField } from "@/components/school/fields";
import { FormDialog } from "@/components/school/form-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";

const emptyForm = (): AbsenceForm => {
  // The family's own calendar day is a good starting point; the API checks the school's window.
  const today = localDateIn(DISPLAY_TIME_ZONE, new Date());
  return { fromDate: today, toDate: today, reasonCode: "", note: "" };
};

/**
 * A guardian reports an absence of a child and sees the reports they filed. The reason and note are health
 * information: the form says plainly who can read them, and they stay in this page's memory only.
 */
export function AbsenceReports({ schoolId, studentId, childName }: { schoolId: string; studentId: string; childName: string }) {
  const t = useTranslations("family.absence");
  const [formOpen, setFormOpen] = useState(false);
  const [withdraw, setWithdraw] = useState<AbsenceReport | null>(null);

  const list = useInfiniteQuery({
    queryKey: ak.studentAbsences(schoolId, studentId),
    queryFn: ({ pageParam }) => fetchStudentAbsences(schoolId, studentId, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    retry: false,
  });
  const items = list.data?.pages.flatMap((p) => p.items) ?? [];

  const cancel = useSubmit({
    mutationFn: async (report: AbsenceReport) => cancelAbsence(schoolId, report.id),
    invalidate: [ak.studentAbsences(schoolId, studentId)],
    successMessage: t("withdrawn"),
    errorFallback: t("withdrawFailed"),
    toastOnError: false,
    onSuccess: () => setWithdraw(null),
  });

  return (
    <section aria-labelledby={`absence-title-${studentId}`} data-testid="absence-reports">
      <Card>
        <CardHeader className="flex-row flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <CardTitle id={`absence-title-${studentId}`} className="flex items-center gap-2 text-xl">
              <CalendarOffIcon className="size-5 text-primary" aria-hidden />
              {t("title")}
            </CardTitle>
            <p className="pt-1 text-sm text-muted-foreground">{t("intro")}</p>
          </div>
          <Button className="min-h-11" onClick={() => setFormOpen(true)} data-testid="report-absence">
            <PlusIcon className="size-4" aria-hidden />
            {t("open")}
          </Button>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <h3 className="text-base font-bold">{t("listTitle")}</h3>
          {list.isPending ? (
            <LoadingRows rows={2} />
          ) : list.isError ? (
            <ErrorNotice message={errorMessage(list.error, t("listFailed"))} onRetry={() => void list.refetch()} />
          ) : items.length === 0 ? (
            <EmptyNotice>{t("listEmpty")}</EmptyNotice>
          ) : (
            <ul className="flex flex-col gap-2" data-testid="absence-list">
              {items.map((report) => (
                <ReportRow key={report.id} report={report} onWithdraw={() => setWithdraw(report)} />
              ))}
            </ul>
          )}
          <LoadMore hasMore={!!list.hasNextPage} loading={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()} />
        </CardContent>
      </Card>

      {formOpen ? <AbsenceFormDialog schoolId={schoolId} studentId={studentId} childName={childName} onClose={() => setFormOpen(false)} /> : null}
      {withdraw ? (
        <ConfirmDialog
          title={t("withdrawTitle")}
          description={t("withdrawBody")}
          confirmLabel={t("withdrawConfirm")}
          pending={cancel.isPending}
          error={cancel.isError ? errorMessage(cancel.error, t("withdrawFailed")) : null}
          onConfirm={() => cancel.mutate(withdraw)}
          onClose={() => {
            cancel.reset();
            setWithdraw(null);
          }}
        />
      ) : null}
    </section>
  );
}

function ReportRow({ report, onWithdraw }: { report: AbsenceReport; onWithdraw: () => void }) {
  const t = useTranslations("family.absence");
  const formatDate = useFormatDate();
  const status = isReportStatus(report.status) ? report.status : null;
  return (
    <li className="flex flex-col gap-2 rounded-2xl border p-3" data-testid={`absence-row-${report.id}`} data-status={report.status}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="font-semibold">
            {report.fromDate === report.toDate ? t("day", { date: formatDate(report.fromDate) }) : t("range", { from: formatDate(report.fromDate), to: formatDate(report.toDate) })}
          </p>
          {report.reasonCode && isReason(report.reasonCode) ? <p className="text-sm text-muted-foreground">{t(`reasonName.${report.reasonCode}`)}</p> : null}
        </div>
        {status ? <Badge variant={status === "ACKNOWLEDGED" ? "default" : "secondary"}>{t(`status.${status}`)}</Badge> : null}
      </div>
      {report.note ? <p className="rounded-xl bg-muted p-2 text-sm break-words">{report.note}</p> : null}
      {canCancel(report.status) ? (
        <div>
          <Button variant="outline" size="sm" className="min-h-11" onClick={onWithdraw} data-testid={`withdraw-${report.id}`}>
            {t("withdraw")}
          </Button>
        </div>
      ) : null}
    </li>
  );
}

function AbsenceFormDialog({ schoolId, studentId, childName, onClose }: { schoolId: string; studentId: string; childName: string; onClose: () => void }) {
  const t = useTranslations("family.absence");
  const [form, setForm] = useState<AbsenceForm>(emptyForm);
  const [errors, setErrors] = useState<AbsenceFormErrors>({});

  const submit = useSubmit({
    mutationFn: (body: ReturnType<typeof buildAbsenceRequest>, idempotencyKey) => reportAbsence(schoolId, studentId, body, idempotencyKey),
    invalidate: [ak.studentAbsences(schoolId, studentId)],
    successMessage: t("sent"),
    errorFallback: t("sendFailed"),
    toastOnError: false,
    onSuccess: onClose,
  });
  const apiErrors = fieldErrors(submit.error);
  const message = (code: string | undefined) => (code ? t(`error.${code as "required"}`) : null);

  function update(patch: Partial<AbsenceForm>) {
    setForm((f) => ({ ...f, ...patch }));
    setErrors({});
  }

  function send() {
    const found = validateAbsenceForm(form, localDateIn(DISPLAY_TIME_ZONE, new Date()));
    setErrors(found);
    if (!hasErrors(found)) submit.mutate(buildAbsenceRequest(form));
  }

  return (
    <FormDialog
      title={t("title")}
      description={childName}
      submitLabel={t("submit")}
      pending={submit.isPending}
      error={submit.isError && Object.keys(apiErrors).length === 0 ? errorMessage(submit.error, t("sendFailed")) : null}
      onSubmit={send}
      onClose={onClose}
    >
      <p className="rounded-xl bg-muted p-3 text-sm" data-testid="absence-privacy">
        {t("privacy")}
      </p>
      <div className="grid gap-3 sm:grid-cols-2">
        <FormField label={t("from")} error={message(errors.fromDate) ?? apiErrors.fromDate}>
          {(props) => <Input {...props} type="date" value={form.fromDate} onChange={(e) => update({ fromDate: e.target.value })} className="h-11" data-testid="absence-from" />}
        </FormField>
        <FormField label={t("to")} error={message(errors.toDate) ?? apiErrors.toDate}>
          {(props) => <Input {...props} type="date" value={form.toDate} min={form.fromDate || undefined} onChange={(e) => update({ toDate: e.target.value })} className="h-11" data-testid="absence-to" />}
        </FormField>
      </div>
      <SelectField
        label={t("reason")}
        value={form.reasonCode}
        onChange={(v) => update({ reasonCode: v })}
        placeholder={t("reasonPlaceholder")}
        options={REASONS.map((r) => ({ value: r, label: t(`reasonName.${r}`) }))}
        error={message(errors.reasonCode) ?? apiErrors.reasonCode}
      />
      <FormField label={t("note")} hint={t("noteHint")} error={message(errors.note) ?? apiErrors.note}>
        {(props) => <Textarea {...props} rows={3} maxLength={MAX_ABSENCE_NOTE} value={form.note} onChange={(e) => update({ note: e.target.value })} data-testid="absence-note" />}
      </FormField>
    </FormDialog>
  );
}
