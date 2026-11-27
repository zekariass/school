"use client";

import { useMutation } from "@tanstack/react-query";
import { CheckCircle2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { NothingHere } from "@/components/moderation/nothing-here";
import { useSchool } from "@/components/school/school-context";
import { PageHeader } from "@/components/school/page-header";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/console/errors";
import { newIdempotencyKey } from "@/lib/console/idempotency";
import { outcomeOf } from "@/lib/moderation/outcome";
import { checkConcern, type ConcernDraft } from "@/lib/moderation/forms";
import { raiseConcern } from "@/lib/moderation/queries";
import { INCIDENT_CATEGORIES, INCIDENT_SEVERITIES, NARRATIVE_MIN, NOTE_MAX } from "@/lib/moderation/types";
import { charCount } from "@/lib/moderation/report";
import { cn } from "@/lib/utils";

const EMPTY: ConcernDraft = { category: null, severity: null, narrative: "" };

/**
 * "Raise a safeguarding concern": for teachers and other staff of the school. The narrative is encrypted by the API at once
 * and only safeguarding leads can read it; whoever raises a concern gets no incident back (only a thank-you), and nobody is
 * notified automatically about what it says. The text lives in this form's memory until it is sent and is cleared after.
 * Students and guardians use "Report" instead, never this page.
 */
export function ConcernView() {
  const t = useTranslations("safeguarding.concern");
  const { school, isTeacher, isStaff } = useSchool();
  const [draft, setDraft] = useState<ConcernDraft>(EMPTY);
  const [problem, setProblem] = useState<string | null>(null);
  const [sent, setSent] = useState(false);
  // One key per concern: a retry after a lost answer does not raise it twice; a new concern gets a new key.
  const [key, setKey] = useState(newIdempotencyKey);
  const send = useMutation({
    mutationFn: () => {
      const check = checkConcern(draft);
      if (!check.ok) throw new Error(check.problem);
      return raiseConcern(school.id, check.request, key);
    },
    onSuccess: () => {
      setDraft(EMPTY);
      setKey(newIdempotencyKey());
      setSent(true);
    },
    gcTime: 0,
  });
  // Teachers and staff only. Anybody else sees the page of an unknown address (the API answers 404 to them too).
  if (!isTeacher && !isStaff) return <NothingHere />;
  if (send.isError && outcomeOf(send.error).kind === "nothing") return <NothingHere />;

  if (sent) {
    return (
      <div className="mx-auto flex max-w-2xl flex-col gap-4" data-testid="concern-sent">
        <PageHeader title={t("title")} />
        <p className="flex items-start gap-3 rounded-2xl border bg-card p-4">
          <CheckCircle2Icon className="mt-0.5 size-5 shrink-0 text-primary" aria-hidden />
          <span>{t("sent")}</span>
        </p>
        <div>
          <Button type="button" variant="outline" onClick={() => setSent(false)}>
            {t("another")}
          </Button>
        </div>
      </div>
    );
  }

  const length = charCount(draft.narrative.trim());
  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-5" data-testid="concern-view">
      <PageHeader title={t("title")} description={t("subtitle")} />
      <form
        noValidate
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          const check = checkConcern(draft);
          setProblem(check.ok ? null : check.problem);
          if (check.ok) send.mutate();
        }}
      >
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-sm font-medium">{t("category")}</legend>
          <div className="flex flex-wrap gap-2">
            {INCIDENT_CATEGORIES.map((value) => (
              <label key={value} className={cn("flex min-h-10 cursor-pointer items-center gap-2 rounded-full border px-3 text-sm font-semibold", draft.category === value && "border-primary bg-primary/10")}>
                <input type="radio" name="category" className="size-4 accent-primary" checked={draft.category === value} onChange={() => setDraft({ ...draft, category: value })} data-testid={`concern-category-${value}`} />
                {t(`categories.${value}`)}
              </label>
            ))}
          </div>
          {problem === "categoryMissing" ? <p role="alert" className="text-sm font-medium text-destructive">{t("categoryRequired")}</p> : null}
        </fieldset>
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-sm font-medium">{t("severity")}</legend>
          <div className="flex flex-wrap gap-2">
            {INCIDENT_SEVERITIES.map((value) => (
              <label key={value} className={cn("flex min-h-10 cursor-pointer items-center gap-2 rounded-full border px-3 text-sm font-semibold", draft.severity === value && "border-primary bg-primary/10")}>
                <input type="radio" name="severity" className="size-4 accent-primary" checked={draft.severity === value} onChange={() => setDraft({ ...draft, severity: value })} data-testid={`concern-severity-${value}`} />
                {t(`severities.${value}`)}
              </label>
            ))}
          </div>
          {problem === "severityMissing" ? <p role="alert" className="text-sm font-medium text-destructive">{t("severityRequired")}</p> : null}
        </fieldset>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="concern-narrative" className="text-sm font-medium">
            {t("narrative")}
          </label>
          <Textarea
            id="concern-narrative"
            rows={8}
            value={draft.narrative}
            onChange={(event) => setDraft({ ...draft, narrative: event.target.value })}
            className="[field-sizing:fixed] [overflow-wrap:anywhere]"
            data-testid="concern-narrative"
          />
          <p className={problem === "short" || problem === "long" || length > NOTE_MAX ? "text-sm font-medium text-destructive" : "text-sm text-muted-foreground"}>
            {problem === "short" ? t("narrativeShort", { min: NARRATIVE_MIN }) : problem === "long" || length > NOTE_MAX ? t("narrativeLong", { max: NOTE_MAX }) : t("narrativeHint", { min: NARRATIVE_MIN, max: NOTE_MAX })}
          </p>
        </div>
        {send.isError ? (
          <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="concern-error">
            {errorMessage(send.error, t("failed"))}
          </p>
        ) : null}
        <div>
          <Button type="submit" disabled={send.isPending} data-testid="concern-submit">
            {t("submit")}
          </Button>
        </div>
      </form>
    </div>
  );
}
