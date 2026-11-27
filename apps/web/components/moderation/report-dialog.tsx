"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { CheckCircle2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { useDisplayPlace } from "@/components/display-context";
import { FormDialog } from "@/components/school/form-dialog";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";
import { Link } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { newIdempotencyKey } from "@/lib/console/idempotency";
import { modk } from "@/lib/moderation/keys";
import { createReport, fetchEmergencyInfo } from "@/lib/moderation/queries";
import { checkReport, detailsRemaining, reasonsFor, showsEmergencyInfo, type ReportDraft } from "@/lib/moderation/report";
import { DETAILS_MAX, type ReasonCode, type ReportableTarget } from "@/lib/moderation/types";
import { cn } from "@/lib/utils";
import { EmergencyText } from "./emergency-text";

/** What is being reported: only its kind and id (never its text) leave this component. */
export interface ReportTargetRef {
  type: ReportableTarget;
  id: string;
}

/**
 * The fields of the report form, without a dialog around them (so they can be rendered and tested on their own).
 * Controlled: the dialog owns the draft. The details box is plain text up to 1,000 characters and is said to be
 * for people, never shown to the person reported.
 */
export function ReportFields({
  target,
  draft,
  onChange,
  problem,
  emergency,
}: {
  target: ReportableTarget;
  draft: ReportDraft;
  onChange: (draft: ReportDraft) => void;
  /** Set after a submit attempt that did not pass the form's own checks. */
  problem: "reasonMissing" | "detailsLong" | null;
  /** Shown while a reason that may mean somebody is unsafe is selected: the API's per-country text, never composed here. */
  emergency?: React.ReactNode;
}) {
  const t = useTranslations("moderation.report");
  const remaining = detailsRemaining(draft.details);
  return (
    <>
      <fieldset className="flex flex-col gap-2" data-testid="report-reasons">
        <legend className="mb-1 text-sm font-medium">{t("reasonLabel")}</legend>
        {reasonsFor(target).map((value: ReasonCode) => (
          <label
            key={value}
            className={cn("flex min-h-11 cursor-pointer items-center gap-3 rounded-xl border px-3", draft.reason === value && "border-primary bg-primary/5")}
          >
            <input
              type="radio"
              name="report-reason"
              value={value}
              checked={draft.reason === value}
              onChange={() => onChange({ ...draft, reason: value })}
              className="size-4 accent-primary"
              data-testid={`report-reason-${value}`}
            />
            <span>{t(`reasons.${value}`)}</span>
          </label>
        ))}
        {problem === "reasonMissing" ? (
          <p role="alert" className="text-sm font-medium text-destructive" data-testid="report-reason-required">
            {t("reasonRequired")}
          </p>
        ) : null}
      </fieldset>
      {emergency ? <div data-testid="report-emergency">{emergency}</div> : null}
      <div className="flex flex-col gap-1.5">
        <label htmlFor="report-details" className="text-sm font-medium">
          {t("detailsLabel")}
        </label>
        <Textarea
          id="report-details"
          rows={3}
          value={draft.details}
          onChange={(event) => onChange({ ...draft, details: event.target.value })}
          aria-describedby="report-details-hint"
          className="[field-sizing:fixed] [overflow-wrap:anywhere]"
          data-testid="report-details"
        />
        <p id="report-details-hint" className={cn("text-sm", remaining < 0 ? "font-medium text-destructive" : "text-muted-foreground")}>
          {problem === "detailsLong" || remaining < 0 ? t("detailsTooLong", { max: DETAILS_MAX }) : t("detailsHint", { max: DETAILS_MAX })}
        </p>
      </div>
    </>
  );
}

/** The thank-you. It says only that the report arrived: never what happens next, to whom, or when. */
export function ReportSent({ emergency, onClose }: { emergency?: React.ReactNode; onClose: () => void }) {
  const t = useTranslations("moderation.report");
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <CheckCircle2Icon className="size-5 text-primary" aria-hidden />
            {t("sentTitle")}
          </DialogTitle>
          <DialogDescription data-testid="report-sent">{t("sentBody")}</DialogDescription>
        </DialogHeader>
        {emergency}
        <p className="text-sm">
          <Link href="/safety" className="font-medium text-primary underline underline-offset-2" data-testid="report-safety-link">
            {t("safetyLink")}
          </Link>
        </p>
        <DialogFooter>
          <Button type="button" onClick={onClose} data-testid="report-done">
            {t("done")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * The emergency information for the country of the page (the school's in a workspace, the person's elsewhere; never a
 * constant). It is looked up only when a reason that may mean somebody is unsafe is chosen, and a country without text
 * simply shows nothing.
 */
export function useEmergency(wanted: boolean): React.ReactNode {
  const country = useDisplayPlace()?.countryCode;
  const info = useQuery({
    queryKey: modk.emergency(country ?? ""),
    queryFn: () => fetchEmergencyInfo(country!),
    enabled: wanted && !!country,
    staleTime: 10 * 60_000,
    retry: false,
  });
  return wanted && info.data?.text ? <EmergencyText info={info.data} /> : null;
}

/**
 * "Report" on a post, comment, story, message or person: a reason, optional details, then the generic thank-you.
 * The draft lives in this component's memory only. The same `Idempotency-Key` is kept while the submit is retried.
 */
export function ReportDialog({ target, onClose }: { target: ReportTargetRef; onClose: () => void }) {
  const t = useTranslations("moderation.report");
  const [draft, setDraft] = useState<ReportDraft>({ reason: null, details: "" });
  const [problem, setProblem] = useState<"reasonMissing" | "detailsLong" | null>(null);
  const [sent, setSent] = useState(false);
  const [sentWithSafety, setSentWithSafety] = useState(false);
  const wantsEmergency = showsEmergencyInfo(draft.reason) || (sent && sentWithSafety);
  const emergency = useEmergency(wantsEmergency);

  // One key for this report: a retry after a lost answer is the same report, never a second one. The request (with the
  // person's words) is forgotten with the dialog: the mutation is not kept (`gcTime` 0).
  const [key] = useState(newIdempotencyKey);
  const submit = useMutation({
    mutationFn: (request: Parameters<typeof createReport>[0]) => createReport(request, key),
    onSuccess: () => {
      setSentWithSafety(showsEmergencyInfo(draft.reason));
      setSent(true);
    },
    gcTime: 0,
  });

  if (sent) return <ReportSent emergency={emergency} onClose={onClose} />;

  return (
    <FormDialog
      title={t(`titles.${target.type}`)}
      description={t("description")}
      submitLabel={t("submit")}
      pending={submit.isPending}
      error={submit.isError ? errorMessage(submit.error, t("failed")) : null}
      onClose={onClose}
      onSubmit={() => {
        const check = checkReport(target, draft);
        if (!check.ok) {
          setProblem(check.problem);
          return;
        }
        setProblem(null);
        submit.mutate(check.request);
      }}
    >
      <ReportFields target={target.type} draft={draft} onChange={setDraft} problem={problem} emergency={emergency} />
    </FormDialog>
  );
}
