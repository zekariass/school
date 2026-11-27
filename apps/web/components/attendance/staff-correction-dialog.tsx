"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { useSubmit } from "@/components/console/use-submit";
import { SelectField } from "@/components/school/fields";
import { FormDialog } from "@/components/school/form-dialog";
import { useSchool } from "@/components/school/school-context";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { ak, correctStaffRecord } from "@/lib/attendance/queries";
import {
  MAX_STAFF_NOTE,
  MAX_STAFF_REASON,
  STAFF_STATUSES,
  buildCorrection,
  correctionErrors,
  correctionFormOf,
  isStaffStatus,
  isVersionConflict,
  staffTakesMinutes,
  type CorrectionForm,
  type StaffRecord,
} from "@/lib/attendance/staff";
import { errorMessage } from "@/lib/console/errors";

/**
 * Corrects a staff record: the new status (minutes only with LATE), an optional new note and a required reason; the
 * correction is appended to the record's history and the record's `version` is sent as `expectedVersion`. One
 * `Idempotency-Key` per submission (the API's correction endpoint is idempotent).
 */
export function StaffCorrectionDialog({ record, onClose }: { record: StaffRecord; onClose: () => void }) {
  const t = useTranslations("school.attendance.staff");
  const tv = useTranslations("validation");
  const { school } = useSchool();
  const queryClient = useQueryClient();
  const [form, setForm] = useState<CorrectionForm>(() => correctionFormOf(record));
  const [submitted, setSubmitted] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = () => Promise.all([queryClient.invalidateQueries({ queryKey: ak.allStaffDays(school.id) }), queryClient.invalidateQueries({ queryKey: ak.staffRecord(school.id, record.id) })]);

  const correct = useSubmit({
    mutationFn: (request: NonNullable<ReturnType<typeof buildCorrection>>, key) => correctStaffRecord(school.id, record.id, request, key),
    invalidate: [ak.allStaffDays(school.id), ak.staffRecord(school.id, record.id)],
    successMessage: t("correct.saved"),
    errorFallback: t("correct.failed"),
    toastOnError: false,
    onSuccess: onClose,
  });

  const errors = correctionErrors(record, form);
  const text = (key: "invalid" | "tooLong" | "required" | "nothingChanged" | undefined) =>
    key === "required" ? tv("required") : key === "tooLong" ? tv("tooLong") : key === "invalid" ? tv("invalid") : key === "nothingChanged" ? t("correct.nothingChanged") : null;

  function submit() {
    setSubmitted(true);
    setError(null);
    const request = buildCorrection(record, form);
    if (Object.keys(errors).length > 0 || !request) return;
    correct.mutate(request, {
      onError: (e) => {
        if (isVersionConflict(e)) {
          // Somebody corrected the record meanwhile: load what is stored now and start again from it.
          toast.error(errorMessage(e, t("correct.failed")));
          void refresh();
          onClose();
          return;
        }
        setError(errorMessage(e, t("correct.failed")));
      },
    });
  }

  return (
    <FormDialog title={t("correct.title", { name: record.staffName })} description={t("correct.description")} submitLabel={t("correct.submit")} pending={correct.isPending} error={error} onSubmit={submit} onClose={onClose}>
      <SelectField
        label={t("correct.status")}
        value={form.status}
        onChange={(v) => isStaffStatus(v) && setForm({ ...form, status: v })}
        options={STAFF_STATUSES.map((s) => ({ value: s, label: t(`status.${s}`) }))}
      />
      {staffTakesMinutes(form.status) ? (
        <label className="flex flex-col gap-1.5 text-sm font-medium">
          {t("minutesLate")}
          <Input
            type="text"
            inputMode="numeric"
            autoComplete="off"
            value={form.minutes}
            onChange={(e) => setForm({ ...form, minutes: e.target.value })}
            aria-invalid={errors.minutes ? true : undefined}
            className="h-11 max-w-32"
            data-testid="correct-minutes"
          />
          {errors.minutes ? (
            <span role="alert" className="text-sm font-medium text-destructive">
              {t("minutesInvalid")}
            </span>
          ) : null}
        </label>
      ) : null}
      <label className="flex flex-col gap-1.5 text-sm font-medium">
        {t("note")}
        <Textarea rows={2} maxLength={MAX_STAFF_NOTE} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} data-testid="correct-note" />
      </label>
      <label className="flex flex-col gap-1.5 text-sm font-medium">
        {t("correct.reason")}
        <Textarea
          rows={2}
          maxLength={MAX_STAFF_REASON}
          value={form.reason}
          onChange={(e) => setForm({ ...form, reason: e.target.value })}
          aria-invalid={submitted && errors.reason ? true : undefined}
          data-testid="correct-reason"
        />
        <span className="text-sm font-normal text-muted-foreground">{t("correct.reasonHint")}</span>
        {submitted && errors.reason ? (
          <span role="alert" className="text-sm font-medium text-destructive" data-testid="correct-reason-error">
            {text(errors.reason)}
          </span>
        ) : null}
      </label>
      {submitted && errors.nothing ? (
        <p role="alert" className="text-sm font-medium text-destructive">
          {text(errors.nothing)}
        </p>
      ) : null}
    </FormDialog>
  );
}
