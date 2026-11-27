"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import { FormField } from "@/components/console/form-field";
import { FormDialog } from "@/components/school/form-dialog";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/console/errors";
import { charCount } from "@/lib/messaging/text";

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
