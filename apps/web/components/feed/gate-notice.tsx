"use client";

import { ShieldAlertIcon } from "lucide-react";
import { useId } from "react";
import { useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";
import type { GateError } from "@/lib/feed/consent-error";

/**
 * The consent gate (R-09) explained. The text is the API's own localized `detail`; this adds what the author can do:
 * remove the tag of a child without media consent (or ask the school to record it), or confirm that no such child
 * appears in untagged photos. The author's text and photos stay where they are: a refused request created nothing.
 */
export function GateNotice({
  error,
  onUntag,
  confirmed,
  onConfirmChange,
  disabled,
}: {
  error: GateError;
  /** Removes the tags of these students from the post. */
  onUntag?: (studentIds: string[]) => void;
  confirmed?: boolean;
  onConfirmChange?: (value: boolean) => void;
  disabled?: boolean;
}) {
  const t = useTranslations("school.feed.gate");
  const checkId = useId();
  const named = error.kind === "consent_missing" ? error.students.filter((s) => s.displayName) : [];
  const unnamed = error.kind === "consent_missing" ? error.students.length - named.length : 0;
  return (
    <section role="alert" data-testid="gate-notice" className="flex flex-col gap-3 rounded-xl border border-warning/50 bg-warning/10 p-4 text-sm">
      <h3 className="flex items-center gap-2 font-bold">
        <ShieldAlertIcon className="size-5 shrink-0" aria-hidden />
        {error.kind === "consent_missing" ? t("missingTitle") : t("confirmTitle")}
      </h3>
      {error.detail ? <p>{error.detail}</p> : null}
      {error.kind === "consent_missing" ? (
        <>
          {named.length > 0 || unnamed > 0 ? (
            <ul className="flex flex-wrap gap-1.5" aria-label={t("students")}>
              {named.map((s) => (
                <li key={s.studentId} className="rounded-full bg-background px-3 py-1 font-medium">
                  {s.displayName}
                </li>
              ))}
              {unnamed > 0 ? <li className="rounded-full bg-background px-3 py-1 font-medium">{t("unnamed", { count: unnamed })}</li> : null}
            </ul>
          ) : null}
          <p className="text-muted-foreground">{t("missingAdvice")}</p>
          {onUntag ? (
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="self-start"
              disabled={disabled}
              onClick={() => onUntag(error.students.map((s) => s.studentId))}
              data-testid="gate-untag"
            >
              {t("untag")}
            </Button>
          ) : null}
        </>
      ) : (
        <label htmlFor={checkId} className="flex cursor-pointer items-start gap-3">
          <input
            id={checkId}
            type="checkbox"
            className="mt-0.5 size-5 shrink-0 accent-primary"
            checked={!!confirmed}
            disabled={disabled}
            onChange={(event) => onConfirmChange?.(event.target.checked)}
            data-testid="gate-confirm"
          />
          <span className="font-medium">{t("confirmLabel")}</span>
        </label>
      )}
    </section>
  );
}
