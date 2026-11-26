"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import { FormField } from "@/components/console/form-field";
import { FormDialog } from "@/components/school/form-dialog";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/console/errors";
import { charCount } from "@/lib/messaging/text";
import { REPORT_REASONS, type ReportReason } from "@/lib/messaging/types";
import { cn } from "@/lib/utils";

/** Report a message: a reason from a short list and an optional note. The note is for people, never shown to the author. */
export function ReportDialog({
  pending,
  error,
  onSubmit,
  onClose,
}: {
  pending: boolean;
  error: unknown;
  onSubmit: (reason: ReportReason, note: string) => void;
  onClose: () => void;
}) {
  const t = useTranslations("school.messaging.report");
  const [reason, setReason] = useState<ReportReason | null>(null);
  const [note, setNote] = useState("");
  const [touched, setTouched] = useState(false);
  return (
    <FormDialog
      title={t("title")}
      description={t("description")}
      submitLabel={t("submit")}
      pending={pending}
      error={error ? errorMessage(error, t("failed")) : null}
      onClose={onClose}
      onSubmit={() => {
        setTouched(true);
        if (reason) onSubmit(reason, note);
      }}
    >
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-sm font-medium">{t("reasonLabel")}</legend>
        {REPORT_REASONS.map((value) => (
          <label key={value} className={cn("flex min-h-11 cursor-pointer items-center gap-3 rounded-xl border px-3", reason === value && "border-primary bg-primary/5")}>
            <input type="radio" name="report-reason" value={value} checked={reason === value} onChange={() => setReason(value)} className="size-4 accent-primary" />
            <span>{t(`reasons.${value}`)}</span>
          </label>
        ))}
        {touched && !reason ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {t("reasonRequired")}
          </p>
        ) : null}
      </fieldset>
      <FormField label={t("noteLabel")} hint={t("noteHint")}>
        {(props) => <Textarea {...props} value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} rows={3} className="[field-sizing:fixed] [overflow-wrap:anywhere]" />}
      </FormField>
    </FormDialog>
  );
}

/** A reason that is required and kept in the audit log (hiding a message, removing a member). */
export function ReasonDialog({
  title,
  description,
  label,
  submitLabel,
  maxLength,
  pending,
  error,
  onSubmit,
  onClose,
}: {
  title: string;
  description: string;
  label: string;
  submitLabel: string;
  maxLength: number;
  pending: boolean;
  error: unknown;
  onSubmit: (reason: string) => void;
  onClose: () => void;
}) {
  const t = useTranslations("school.messaging.reason");
  const [reason, setReason] = useState("");
  const [touched, setTouched] = useState(false);
  const trimmed = reason.trim();
  return (
    <FormDialog
      title={title}
      description={description}
      submitLabel={submitLabel}
      pending={pending}
      error={error ? errorMessage(error, t("failed")) : null}
      onClose={onClose}
      onSubmit={() => {
        setTouched(true);
        if (trimmed && charCount(trimmed) <= maxLength) onSubmit(trimmed);
      }}
    >
      <FormField label={label} hint={t("hint", { max: maxLength })} error={touched && !trimmed ? t("required") : null}>
        {(props) => <Textarea {...props} value={reason} maxLength={maxLength} rows={3} onChange={(e) => setReason(e.target.value)} className="[field-sizing:fixed] [overflow-wrap:anywhere]" />}
      </FormField>
    </FormDialog>
  );
}
