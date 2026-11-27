"use client";

import { useMutation } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { FormDialog } from "@/components/school/form-dialog";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/console/errors";
import { nextIncidentStatuses, statusNeedsNote } from "@/lib/moderation/access";
import { charCount } from "@/lib/moderation/report";
import { checkNote, checkOptionalNote, checkReferredTo, checkText } from "@/lib/moderation/forms";
import { addIncidentNote, changeIncidentStatus, recordGuardianInformed, recordReferral } from "@/lib/moderation/queries";
import { NOTE_MAX, REASON_MIN, REFERRED_TO_MAX, type IncidentStatus, type IncidentSummary } from "@/lib/moderation/types";

type Dialog = "status" | "guardian" | "referral" | null;

/**
 * What a lead does to an incident once it is open: add a note, move its status, and record (as a human decision, with an
 * optional note) that the child's guardians were told or that it was referred to an authority. The system never does
 * either by itself. All text typed here stays in this component's memory until it is sent.
 */
export function IncidentActions({
  incidentId,
  summary,
  onChanged,
  onSummary,
}: {
  incidentId: string;
  summary: IncidentSummary;
  /** Read the incident again: the API's timeline now holds the change. */
  onChanged: () => void;
  onSummary: (summary: IncidentSummary) => void;
}) {
  const t = useTranslations("safeguarding.incident.actions");
  const [dialog, setDialog] = useState<Dialog>(null);
  const statuses = nextIncidentStatuses(summary.status);
  return (
    <section aria-label={t("title")} className="flex flex-col gap-3 rounded-2xl border bg-card p-4" data-testid="incident-actions">
      <h2 className="text-lg font-bold">{t("title")}</h2>
      <NoteForm incidentId={incidentId} onChanged={onChanged} />
      <div className="flex flex-wrap gap-2">
        {statuses.length > 0 ? (
          <Button type="button" size="sm" variant="outline" onClick={() => setDialog("status")} data-testid="incident-status">
            {t("changeStatus")}
          </Button>
        ) : null}
        <Button type="button" size="sm" variant="outline" onClick={() => setDialog("guardian")} data-testid="incident-guardian">
          {t("guardianInformed")}
        </Button>
        <Button type="button" size="sm" variant="outline" onClick={() => setDialog("referral")} data-testid="incident-referral">
          {t("referral")}
        </Button>
      </div>
      {dialog === "status" ? (
        <StatusDialog
          incidentId={incidentId}
          from={summary.status}
          options={statuses}
          onDone={(next) => {
            onSummary(next);
            setDialog(null);
            onChanged();
          }}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog === "guardian" ? (
        <RecordDialog
          kind="guardian"
          incidentId={incidentId}
          onDone={(next) => {
            onSummary(next);
            setDialog(null);
            onChanged();
          }}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog === "referral" ? (
        <RecordDialog
          kind="referral"
          incidentId={incidentId}
          onDone={(next) => {
            onSummary(next);
            setDialog(null);
            onChanged();
          }}
          onClose={() => setDialog(null)}
        />
      ) : null}
    </section>
  );
}

function NoteForm({ incidentId, onChanged }: { incidentId: string; onChanged: () => void }) {
  const t = useTranslations("safeguarding.incident.actions");
  const [text, setText] = useState("");
  const [problem, setProblem] = useState<"short" | "long" | null>(null);
  const add = useMutation({
    mutationFn: (note: string) => addIncidentNote(incidentId, note),
    onSuccess: () => {
      setText("");
      onChanged();
    },
    gcTime: 0,
  });
  return (
    <form
      className="flex flex-col gap-2"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        const check = checkNote(text);
        setProblem(check.ok ? null : check.problem);
        if (check.ok) add.mutate(check.text);
      }}
    >
      <label htmlFor="incident-note" className="text-sm font-medium">
        {t("noteLabel")}
      </label>
      <Textarea
        id="incident-note"
        rows={3}
        value={text}
        onChange={(event) => setText(event.target.value)}
        disabled={add.isPending}
        aria-invalid={problem ? true : undefined}
        className="[field-sizing:fixed] [overflow-wrap:anywhere]"
        data-testid="incident-note-input"
      />
      <p className={problem || charCount(text.trim()) > NOTE_MAX ? "text-sm font-medium text-destructive" : "text-sm text-muted-foreground"}>
        {problem === "short" ? t("noteRequired") : problem === "long" || charCount(text.trim()) > NOTE_MAX ? t("noteTooLong", { max: NOTE_MAX }) : t("noteHint", { max: NOTE_MAX })}
      </p>
      {add.isError ? (
        <p role="alert" className="text-sm font-medium text-destructive">
          {errorMessage(add.error, t("failed"))}
        </p>
      ) : null}
      <div>
        <Button type="submit" size="sm" disabled={add.isPending || !text.trim()} data-testid="incident-note-add">
          {t("addNote")}
        </Button>
      </div>
    </form>
  );
}

function StatusDialog({
  incidentId,
  from,
  options,
  onDone,
  onClose,
}: {
  incidentId: string;
  from: string | undefined;
  options: readonly IncidentStatus[];
  onDone: (summary: IncidentSummary) => void;
  onClose: () => void;
}) {
  const t = useTranslations("safeguarding.incident.status");
  const ts = useTranslations("safeguarding.incidents.status");
  const [to, setTo] = useState<IncidentStatus | null>(null);
  const [note, setNote] = useState("");
  const [problem, setProblem] = useState<"toMissing" | "short" | "long" | null>(null);
  const change = useMutation({
    mutationFn: (vars: { status: IncidentStatus; note?: string }) => changeIncidentStatus(incidentId, vars.status, vars.note),
    onSuccess: onDone,
    gcTime: 0,
  });
  const needsNote = !!to && statusNeedsNote(from, to);
  return (
    <FormDialog
      title={t("title")}
      description={t("description")}
      submitLabel={t("submit")}
      pending={change.isPending}
      error={change.isError ? errorMessage(change.error, t("failed")) : null}
      onClose={onClose}
      onSubmit={() => {
        if (!to) return setProblem("toMissing");
        const check = needsNote ? checkText(note, REASON_MIN, NOTE_MAX) : checkOptionalNote(note);
        if (!check.ok) return setProblem(check.problem);
        setProblem(null);
        change.mutate({ status: to, ...(check.text ? { note: check.text } : {}) });
      }}
    >
      <fieldset className="flex flex-col gap-2" data-testid="status-choices">
        <legend className="mb-1 text-sm font-medium">{t("choose")}</legend>
        {options.map((value) => (
          <label key={value} className="flex min-h-10 cursor-pointer items-center gap-3 rounded-xl border px-3">
            <input type="radio" name="status" className="size-4 accent-primary" checked={to === value} onChange={() => setTo(value)} data-testid={`status-${value}`} />
            <span>{ts(value)}</span>
          </label>
        ))}
        {problem === "toMissing" ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {t("chooseRequired")}
          </p>
        ) : null}
      </fieldset>
      <div className="flex flex-col gap-1.5">
        <label htmlFor="status-note" className="text-sm font-medium">
          {needsNote ? (to === "CLOSED" ? t("closingNote") : t("reopenReason") ) : t("noteOptional")}
        </label>
        <Textarea id="status-note" rows={3} value={note} onChange={(event) => setNote(event.target.value)} className="[field-sizing:fixed] [overflow-wrap:anywhere]" data-testid="status-note" />
        <p className={problem === "short" || problem === "long" ? "text-sm font-medium text-destructive" : "text-sm text-muted-foreground"}>
          {problem === "short" ? t("tooShort", { min: REASON_MIN }) : problem === "long" ? t("tooLong", { max: NOTE_MAX }) : needsNote ? t("needsNote", { min: REASON_MIN }) : t("noteHint", { max: NOTE_MAX })}
        </p>
      </div>
    </FormDialog>
  );
}

/** The two records a human makes: "the guardians were told" (date optional, note optional) and "referred to" an authority. */
function RecordDialog({ kind, incidentId, onDone, onClose }: { kind: "guardian" | "referral"; incidentId: string; onDone: (summary: IncidentSummary) => void; onClose: () => void }) {
  const t = useTranslations("safeguarding.incident.record");
  const [referredTo, setReferredTo] = useState("");
  const [note, setNote] = useState("");
  const [problem, setProblem] = useState<"short" | "long" | "noteLong" | null>(null);
  const record = useMutation({
    mutationFn: (vars: { referredTo?: string; note?: string }) =>
      kind === "guardian" ? recordGuardianInformed(incidentId, { ...(vars.note ? { note: vars.note } : {}) }) : recordReferral(incidentId, { referredTo: vars.referredTo ?? "", ...(vars.note ? { note: vars.note } : {}) }),
    onSuccess: onDone,
    gcTime: 0,
  });
  return (
    <FormDialog
      title={t(`${kind}.title`)}
      description={t(`${kind}.description`)}
      submitLabel={t(`${kind}.submit`)}
      pending={record.isPending}
      error={record.isError ? errorMessage(record.error, t("failed")) : null}
      onClose={onClose}
      onSubmit={() => {
        const optional = checkOptionalNote(note);
        if (!optional.ok) return setProblem("noteLong");
        if (kind === "referral") {
          const to = checkReferredTo(referredTo);
          if (!to.ok) return setProblem(to.problem);
          setProblem(null);
          record.mutate({ referredTo: to.text, note: optional.text });
          return;
        }
        setProblem(null);
        record.mutate({ note: optional.text });
      }}
    >
      {kind === "referral" ? (
        <div className="flex flex-col gap-1.5">
          <label htmlFor="referred-to" className="text-sm font-medium">
            {t("referredTo")}
          </label>
          <Textarea id="referred-to" rows={2} value={referredTo} onChange={(event) => setReferredTo(event.target.value)} className="[field-sizing:fixed] [overflow-wrap:anywhere]" data-testid="referred-to" />
          <p className={problem === "short" || problem === "long" ? "text-sm font-medium text-destructive" : "text-sm text-muted-foreground"}>
            {problem === "short" ? t("referredToRequired") : problem === "long" ? t("referredToTooLong", { max: REFERRED_TO_MAX }) : t("referredToHint", { max: REFERRED_TO_MAX })}
          </p>
        </div>
      ) : null}
      <div className="flex flex-col gap-1.5">
        <label htmlFor="record-note" className="text-sm font-medium">
          {t("note")}
        </label>
        <Textarea id="record-note" rows={3} value={note} onChange={(event) => setNote(event.target.value)} className="[field-sizing:fixed] [overflow-wrap:anywhere]" data-testid="record-note" />
        <p className={problem === "noteLong" ? "text-sm font-medium text-destructive" : "text-sm text-muted-foreground"}>
          {problem === "noteLong" ? t("noteTooLong", { max: NOTE_MAX }) : t("noteHint", { max: NOTE_MAX })}
        </p>
      </div>
    </FormDialog>
  );
}
