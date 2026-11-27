"use client";

import { useMutation } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { FormDialog } from "@/components/school/form-dialog";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/console/errors";
import { EMPTY_DECISION, checkDecision, reasonRemaining, checkReason, type DecisionDraft } from "@/lib/moderation/forms";
import { decideCase, reasonedCaseAction, type ReasonAction } from "@/lib/moderation/queries";
import {
  INCIDENT_CATEGORIES,
  INCIDENT_SEVERITIES,
  REASON_MAX,
  REASON_MIN,
  type CaseDetail,
  type Decision,
} from "@/lib/moderation/types";
import { cn } from "@/lib/utils";

/** The reason box shared by every decision and reversal: 10 to 500 characters, kept in this component's memory only. */
export function ReasonBox({
  id,
  value,
  onChange,
  problem,
}: {
  id: string;
  value: string;
  onChange: (value: string) => void;
  problem: "short" | "long" | null;
}) {
  const t = useTranslations("moderation.reason");
  const left = reasonRemaining(value);
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-medium">
        {t("label")}
      </label>
      <Textarea
        id={id}
        rows={3}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        aria-describedby={`${id}-hint`}
        aria-invalid={problem ? true : undefined}
        className="[field-sizing:fixed] [overflow-wrap:anywhere]"
        data-testid="reason-input"
      />
      <p id={`${id}-hint`} className={cn("text-sm", problem || left < 0 ? "font-medium text-destructive" : "text-muted-foreground")}>
        {problem === "short" ? t("tooShort", { min: REASON_MIN }) : problem === "long" || left < 0 ? t("tooLong", { max: REASON_MAX }) : t("hint", { min: REASON_MIN, max: REASON_MAX })}
      </p>
    </div>
  );
}

/**
 * Decide an open case: one of the decisions the case page offers (the API refuses what does not fit), the reason, and for
 * a suspension its level, for an escalation the incident's category and severity. Nothing is sent but what was typed.
 */
export function DecisionDialog({
  caseId,
  decisions,
  platformLock,
  initial,
  onDone,
  onClose,
}: {
  caseId: string;
  decisions: readonly Decision[];
  platformLock: boolean;
  initial?: Decision;
  onDone: (detail: CaseDetail) => void;
  onClose: () => void;
}) {
  const t = useTranslations("moderation.decision");
  const [draft, setDraft] = useState<DecisionDraft>({ ...EMPTY_DECISION, decision: initial ?? null });
  const [problem, setProblem] = useState<"decisionMissing" | "short" | "long" | null>(null);
  const decide = useMutation({
    mutationFn: () => {
      const check = checkDecision(draft, decisions, platformLock);
      if (!check.ok) throw new Error(check.problem);
      return decideCase(caseId, check.request);
    },
    onSuccess: onDone,
    gcTime: 0,
  });

  return (
    <FormDialog
      title={t("title")}
      description={t("description")}
      submitLabel={t("submit")}
      pending={decide.isPending}
      error={decide.isError ? errorMessage(decide.error, t("failed")) : null}
      onClose={onClose}
      onSubmit={() => {
        const check = checkDecision(draft, decisions, platformLock);
        setProblem(check.ok ? null : check.problem);
        if (check.ok) decide.mutate();
      }}
    >
      <fieldset className="flex flex-col gap-2" data-testid="decision-choices">
        <legend className="mb-1 text-sm font-medium">{t("choose")}</legend>
        {decisions.map((value) => (
          <label key={value} className={cn("flex min-h-11 cursor-pointer items-start gap-3 rounded-xl border px-3 py-2", draft.decision === value && "border-primary bg-primary/5")}>
            <input
              type="radio"
              name="decision"
              value={value}
              checked={draft.decision === value}
              onChange={() => setDraft({ ...draft, decision: value })}
              className="mt-1 size-4 accent-primary"
              data-testid={`decision-${value}`}
            />
            <span className="flex flex-col">
              <span className="font-medium">{t(`choices.${value}.label`)}</span>
              <span className="text-sm text-muted-foreground">{t(`choices.${value}.help`)}</span>
            </span>
          </label>
        ))}
        {problem === "decisionMissing" ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {t("chooseRequired")}
          </p>
        ) : null}
      </fieldset>

      {draft.decision === "SUSPEND_USER" && platformLock ? (
        <fieldset className="flex flex-col gap-2" data-testid="decision-level">
          <legend className="mb-1 text-sm font-medium">{t("level.label")}</legend>
          {(["SCHOOL", "PLATFORM"] as const).map((level) => (
            <label key={level} className="flex min-h-10 cursor-pointer items-center gap-3 rounded-xl border px-3">
              <input type="radio" name="level" checked={draft.level === level} onChange={() => setDraft({ ...draft, level })} className="size-4 accent-primary" />
              <span>{t(`level.${level}`)}</span>
            </label>
          ))}
        </fieldset>
      ) : null}

      {draft.decision === "ESCALATE" ? (
        <div className="grid gap-3 sm:grid-cols-2" data-testid="decision-escalate">
          <label className="flex flex-col gap-1.5 text-sm font-medium">
            {t("escalate.category")}
            <select
              className="min-h-10 rounded-xl border bg-background px-3"
              value={draft.category}
              onChange={(event) => setDraft({ ...draft, category: event.target.value as DecisionDraft["category"] })}
            >
              {INCIDENT_CATEGORIES.map((c) => (
                <option key={c} value={c}>
                  {t(`escalate.categories.${c}`)}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1.5 text-sm font-medium">
            {t("escalate.severity")}
            <select
              className="min-h-10 rounded-xl border bg-background px-3"
              value={draft.severity}
              onChange={(event) => setDraft({ ...draft, severity: event.target.value as DecisionDraft["severity"] })}
            >
              {INCIDENT_SEVERITIES.map((s) => (
                <option key={s} value={s}>
                  {t(`escalate.severities.${s}`)}
                </option>
              ))}
            </select>
          </label>
          <p className="text-sm text-muted-foreground sm:col-span-2">{t("escalate.note")}</p>
        </div>
      ) : null}

      <ReasonBox id="decision-reason" value={draft.reason} onChange={(reason) => setDraft({ ...draft, reason })} problem={problem === "short" || problem === "long" ? problem : null} />
    </FormDialog>
  );
}

/** A move that needs only a reason: reopen, restore (undo a hide), lift a suspension, hand up to the platform. */
export function ReasonActionDialog({
  caseId,
  action,
  onDone,
  onClose,
}: {
  caseId: string;
  action: ReasonAction;
  onDone: (detail: CaseDetail) => void;
  onClose: () => void;
}) {
  const t = useTranslations("moderation.reasonAction");
  const [reason, setReason] = useState("");
  const [problem, setProblem] = useState<"short" | "long" | null>(null);
  const run = useMutation({
    mutationFn: (text: string) => reasonedCaseAction(caseId, action, text),
    onSuccess: onDone,
    gcTime: 0,
  });
  return (
    <FormDialog
      title={t(`${action}.title`)}
      description={t(`${action}.description`)}
      submitLabel={t(`${action}.submit`)}
      pending={run.isPending}
      error={run.isError ? errorMessage(run.error, t("failed")) : null}
      onClose={onClose}
      onSubmit={() => {
        const check = checkReason(reason);
        setProblem(check.ok ? null : check.problem);
        if (check.ok) run.mutate(check.text);
      }}
    >
      <ReasonBox id="reason-action" value={reason} onChange={setReason} problem={problem} />
    </FormDialog>
  );
}
