"use client";

import { ShieldAlertIcon } from "lucide-react";
import { useId } from "react";
import { useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";
import type { PortfolioGateError } from "@/lib/portfolio/consent-error";

/**
 * The consent gate (R-09) explained inline. The text is the API's own localized `detail`; this adds what the person
 * can do. An author can remove the tag of a child without media consent or confirm that no such child appears; a
 * reviewer cannot confirm for the author, so the notice points at the narrower audience, a note to the student, or
 * the school. Nothing the person typed or uploaded is lost: the refused request created nothing.
 */
export function GateNotice({
  error,
  audience,
  onUntag,
  confirmed,
  onConfirmChange,
  disabled,
}: {
  error: PortfolioGateError;
  audience: "author" | "reviewer";
  onUntag?: (studentIds: string[]) => void;
  confirmed?: boolean;
  onConfirmChange?: (value: boolean) => void;
  disabled?: boolean;
}) {
  const t = useTranslations("school.portfolio.gate");
  const checkId = useId();
  const named = error.kind === "consent_missing" ? error.students.filter((s) => s.displayName) : [];
  const unnamed = error.kind === "consent_missing" ? error.students.length - named.length : 0;
  return (
    <section role="alert" data-testid="portfolio-gate" data-kind={error.kind} className="flex flex-col gap-3 rounded-xl border border-warning/50 bg-warning/10 p-4 text-sm">
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
          <p className="text-muted-foreground">{audience === "author" ? t("missingAdviceAuthor") : t("missingAdviceReviewer")}</p>
          {audience === "author" && onUntag ? (
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="self-start"
              disabled={disabled}
              onClick={() => onUntag(error.students.map((s) => s.studentId))}
              data-testid="portfolio-gate-untag"
            >
              {t("untag")}
            </Button>
          ) : null}
        </>
      ) : audience === "author" ? (
        <label htmlFor={checkId} className="flex cursor-pointer items-start gap-3">
          <input
            id={checkId}
            type="checkbox"
            className="mt-0.5 size-5 shrink-0 accent-primary"
            checked={!!confirmed}
            disabled={disabled}
            onChange={(event) => onConfirmChange?.(event.target.checked)}
            data-testid="portfolio-gate-confirm"
          />
          <span className="font-medium">{t("confirmLabel")}</span>
        </label>
      ) : (
        <p className="text-muted-foreground">{t("confirmAdviceReviewer")}</p>
      )}
    </section>
  );
}
