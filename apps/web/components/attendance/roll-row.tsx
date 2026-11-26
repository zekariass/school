"use client";

import { ChevronDownIcon, MessageSquareTextIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useId, useState } from "react";
import { useFormatDate } from "@/components/calendar-preference";
import { StudentAvatar } from "@/components/school/student-avatar";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { isReason } from "@/lib/attendance/absence";
import type { Mark, RollStudent } from "@/lib/attendance/roll";
import { MAX_NOTE, STATUSES, isStatus, minutesTextValid, parseMinutes, takesMinutes, type AttendanceStatus } from "@/lib/attendance/status";
import { cn } from "@/lib/utils";
import { STATUS_UI } from "./status";

export interface RollRowProps {
  student: RollStudent;
  mark: Mark;
  /** The shown mark is a default or an edit, not yet written. */
  unsaved: boolean;
  /** Editing is off: locked, read-only, archived or while saving. */
  disabled: boolean;
  expanded: boolean;
  /** This student's mark conflicted with someone else's; resolve it above. */
  conflicted: boolean;
  onToggle: () => void;
  /** A tap: next status; Shift+tap: previous. */
  onCycle: (backwards: boolean) => void;
  onStatus: (status: AttendanceStatus) => void;
  onMinutes: (minutes: number | undefined) => void;
  onNote: (note: string) => void;
}

/**
 * One student of the roll. The big button cycles the mark (Enter and Space work like a tap, Shift reverses); the
 * chevron opens a panel to choose a mark directly, the minutes late and a short note.
 */
export function RollRow({ student, mark, unsaved, disabled, expanded, conflicted, onToggle, onCycle, onStatus, onMinutes, onNote }: RollRowProps) {
  const t = useTranslations("school.attendance");
  const formatDate = useFormatDate();
  const panelId = useId();
  const ui = STATUS_UI[mark.status];
  const Icon = ui.icon;
  const statusLabel = t(`status.${mark.status}`);
  const suggested = student.suggestedStatus && isStatus(student.suggestedStatus) && !student.record ? student.suggestedStatus : null;
  const absence = student.absence;
  const hasNote = !!mark.note;

  return (
    <li
      className={cn("rounded-2xl border bg-card p-3 shadow-xs", conflicted && "border-warning ring-2 ring-warning/30")}
      data-testid={`roll-row-${student.studentId}`}
      data-status={mark.status}
    >
      <div className="flex items-center gap-3">
        <StudentAvatar id={student.studentId} name={student.displayName} mediaId={student.avatar?.id} config={student.avatarConfig} size={44} />
        <div className="min-w-0 flex-1">
          <p className="font-semibold break-words">{student.displayName}</p>
          <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-muted-foreground">
            {unsaved ? <span data-testid="unsaved-mark">{t("roll.notSaved")}</span> : null}
            {suggested ? <span>{t("roll.suggested", { status: t(`status.${suggested}`) })}</span> : null}
            {hasNote ? (
              <span className="inline-flex items-center gap-1">
                <MessageSquareTextIcon className="size-3" aria-hidden />
                {t("roll.hasNote")}
              </span>
            ) : null}
          </div>
          {absence ? (
            <p className="pt-1 text-xs font-medium text-info" data-testid="absence-annotation">
              {absence.reasonCode && isReason(absence.reasonCode)
                ? t("roll.annotation.withReason", { reason: t(`roll.annotation.reasons.${absence.reasonCode}`) })
                : t("roll.annotation.plain")}
              {" · "}
              {absence.fromDate === absence.toDate
                ? t("roll.annotation.day", { date: formatDate(absence.fromDate) })
                : t("roll.annotation.range", { from: formatDate(absence.fromDate), to: formatDate(absence.toDate) })}
              {" · "}
              {absence.status === "ACKNOWLEDGED" ? t("roll.annotation.ACKNOWLEDGED") : t("roll.annotation.SUBMITTED")}
            </p>
          ) : null}
        </div>
        <button
          type="button"
          disabled={disabled}
          aria-label={t("roll.rowLabel", { name: student.displayName, status: statusLabel })}
          onClick={(e) => onCycle(e.shiftKey)}
          data-testid={`status-${student.studentId}`}
          className={cn(
            "flex min-h-11 min-w-28 shrink-0 items-center justify-center gap-1.5 rounded-xl border-2 px-3 text-sm font-bold transition-colors outline-none focus-visible:ring-[3px] focus-visible:ring-ring/60 disabled:opacity-70",
            ui.tile,
            unsaved && "border-dashed",
          )}
        >
          <Icon className={cn("size-4 shrink-0", ui.icon_)} aria-hidden />
          <span>
            {statusLabel}
            {mark.minutesLate ? <span className="font-medium"> · {t("roll.minutesShort", { minutes: mark.minutesLate })}</span> : null}
          </span>
        </button>
        <button
          type="button"
          aria-expanded={expanded}
          aria-controls={panelId}
          aria-label={expanded ? t("roll.hideDetails") : t("roll.detailsFor", { name: student.displayName })}
          onClick={onToggle}
          data-testid={`details-${student.studentId}`}
          className="grid size-11 shrink-0 place-items-center rounded-xl text-muted-foreground outline-none hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring/60"
        >
          <ChevronDownIcon className={cn("size-5 transition-transform", expanded && "rotate-180")} aria-hidden />
        </button>
      </div>

      {expanded ? (
        <div id={panelId} className="mt-3 flex flex-col gap-3 border-t pt-3" data-testid={`panel-${student.studentId}`}>
          <fieldset className="flex flex-col gap-1.5" disabled={disabled}>
            <legend className="text-sm font-medium">{t("roll.pickStatus", { name: student.displayName })}</legend>
            <div role="radiogroup" aria-label={t("roll.pickStatus", { name: student.displayName })} className="flex flex-wrap gap-2">
              {STATUSES.map((status) => {
                const option = STATUS_UI[status];
                const OptionIcon = option.icon;
                const selected = mark.status === status;
                return (
                  <button
                    key={status}
                    type="button"
                    role="radio"
                    aria-checked={selected}
                    onClick={() => onStatus(status)}
                    className={cn(
                      "inline-flex min-h-11 items-center gap-1.5 rounded-xl border-2 px-3 text-sm font-semibold outline-none focus-visible:ring-[3px] focus-visible:ring-ring/60 disabled:opacity-60",
                      selected ? option.tile : "border-transparent bg-muted/60 hover:bg-accent",
                    )}
                  >
                    <OptionIcon className={cn("size-4", option.icon_)} aria-hidden />
                    {t(`status.${status}`)}
                  </button>
                );
              })}
            </div>
          </fieldset>
          {takesMinutes(mark.status) ? <MinutesField key={`${student.studentId}:${mark.minutesLate ?? ""}`} value={mark.minutesLate} disabled={disabled} onChange={onMinutes} /> : null}
          <NoteField value={mark.note ?? ""} disabled={disabled} onChange={onNote} />
          {absence?.note ? (
            <p className="rounded-xl bg-muted p-3 text-sm">
              <span className="font-semibold">{t("roll.annotation.parentNote")}: </span>
              {absence.note}
            </p>
          ) : null}
        </div>
      ) : null}
    </li>
  );
}

function MinutesField({ value, disabled, onChange }: { value: number | undefined; disabled: boolean; onChange: (minutes: number | undefined) => void }) {
  const t = useTranslations("school.attendance");
  const id = useId();
  const [text, setText] = useState(value === undefined ? "" : String(value));
  const valid = minutesTextValid(text);
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-medium">
        {t("roll.minutesLate")}
      </label>
      <Input
        id={id}
        type="text"
        inputMode="numeric"
        autoComplete="off"
        value={text}
        disabled={disabled}
        aria-invalid={!valid || undefined}
        aria-describedby={valid ? undefined : `${id}-error`}
        onChange={(e) => {
          const next = e.target.value;
          setText(next);
          if (minutesTextValid(next)) onChange(parseMinutes(next));
        }}
        className="h-11 max-w-32"
        data-testid="minutes-late"
      />
      {valid ? null : (
        <p id={`${id}-error`} role="alert" className="text-sm font-medium text-destructive">
          {t("roll.minutesInvalid")}
        </p>
      )}
    </div>
  );
}

function NoteField({ value, disabled, onChange }: { value: string; disabled: boolean; onChange: (note: string) => void }) {
  const t = useTranslations("school.attendance");
  const id = useId();
  // The text being typed is local (the roll keeps it trimmed), so spaces between words survive typing.
  const [text, setText] = useState(value);
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-medium">
        {t("roll.note")}
      </label>
      <Textarea
        id={id}
        rows={2}
        maxLength={MAX_NOTE}
        value={text}
        disabled={disabled}
        placeholder={t("roll.notePlaceholder")}
        onChange={(e) => {
          setText(e.target.value);
          onChange(e.target.value);
        }}
        data-testid="roll-note"
      />
    </div>
  );
}
