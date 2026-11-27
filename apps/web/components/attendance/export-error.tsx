"use client";

import { useTranslations } from "next-intl";
import { ApiRequestError } from "@/lib/api-error";
import { retryWait } from "@/lib/attendance/period-report";
import { errorMessage } from "@/lib/console/errors";

/**
 * Why a CSV download failed: the API's localized detail and, for a 429 (the export limit), how long to wait
 * (`Retry-After`). Renders nothing without an error.
 */
export function ExportError({ error, fallback }: { error: unknown; fallback: string }) {
  const t = useTranslations("school.attendance.export");
  if (!error) return null;
  const wait = error instanceof ApiRequestError && error.status === 429 && error.retryAfter !== undefined ? retryWait(error.retryAfter) : null;
  return (
    <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="export-error">
      {errorMessage(error, fallback)}
      {wait ? ` ${t("retryIn", wait)}` : ""}
    </p>
  );
}
