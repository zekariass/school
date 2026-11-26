"use client";

import { BanIcon, CheckIcon, CircleHelpIcon, ClockIcon, GlobeIcon, InfoIcon, MapPinIcon, PencilIcon, RepeatIcon, RotateCcwIcon, Trash2Icon, UserIcon, XIcon } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { useState } from "react";
import { useCalendarPreference } from "@/components/calendar-preference";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { ConfirmDialog } from "@/components/school/confirm-dialog";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { errorMessage } from "@/lib/console/errors";
import { isoInZone, type CalendarSystem } from "@/lib/calendar/dates";
import { dayLabel, timeRange } from "@/lib/calendar/format";
import type { OccurrenceValues } from "@/lib/calendar/form";
import { occurrenceState } from "@/lib/calendar/occurrence";
import { ck, answerEvent, cancelEvent, cancelOccurrence, deleteEvent, restoreOccurrence } from "@/lib/calendar/queries";
import { summarizeRule } from "@/lib/calendar/recurrence";
import { RSVP_ANSWERS, actionsOf, isParentMeeting, myAnswer, participantsOf, rsvpMode, totalsOf, type EventView, type RsvpAnswer } from "@/lib/calendar/rsvp";
import { cn } from "@/lib/utils";
import { ItemIcon, ZoneNote } from "./entry";
import { EventFormDialog } from "./event-form";
import { OccurrenceFormDialog } from "./occurrence-form";
import { useRuleText } from "./rule-text";
import { useEvent } from "./use-calendar";

/**
 * One event: what it is, when and where (in its own time zone), how often, who is invited, the caller's answer and what
 * the caller may do. Loaded from `GET /events/{id}`; for a repeating event opened at one occurrence the occurrence's own
 * state (cancelled, moved, changed) is derived from the series and its exceptions. Everything the API leaves out
 * (answers, totals, edit rights) is simply not shown: nothing is filtered here.
 */
export function EventDetail({
  eventId,
  occurrenceId,
  viewerZone,
  userTimeZone,
  onGone,
}: {
  eventId: string;
  occurrenceId: string | null;
  viewerZone: string | null;
  userTimeZone: string | null;
  /** Called after the event was deleted (the dialog closes, the page goes back to the calendar). */
  onGone: () => void;
}) {
  const t = useTranslations("family.calendar");
  const event = useEvent(eventId);
  if (event.isPending) return <LoadingRows rows={4} />;
  if (event.isError) {
    return <ErrorNotice message={errorMessage(event.error, t("detail.loadFailed"))} onRetry={() => void event.refetch()} />;
  }
  return <Loaded event={event.data} occurrenceId={occurrenceId} viewerZone={viewerZone} userTimeZone={userTimeZone} onGone={onGone} />;
}

type Pending = "edit" | "cancel" | "delete" | "choose-edit" | "choose-cancel" | null;

function Loaded({
  event,
  occurrenceId,
  viewerZone,
  userTimeZone,
  onGone,
}: {
  event: EventView;
  occurrenceId: string | null;
  viewerZone: string | null;
  userTimeZone: string | null;
  onGone: () => void;
}) {
  const t = useTranslations("family.calendar");
  const locale = useLocale();
  const { calendar: system } = useCalendarPreference();
  const ruleText = useRuleText();
  const [pending, setPending] = useState<Pending>(null);
  const [target, setTarget] = useState<"occurrence" | "series">("series");

  const recurring = !!event.recurring;
  const occurrence = recurring && occurrenceId ? occurrenceState(event, occurrenceId) : null;
  const values: OccurrenceValues = occurrence ?? {
    title: event.title,
    description: event.description,
    location: event.location,
    allDay: !!event.allDay,
    startAt: event.startAt ?? "",
    endAt: event.endAt ?? "",
    startDate: event.startDate,
    endDate: event.endDate,
    timeZone: event.timeZone,
  };
  const cancelledSeries = event.status === "CANCELLED";
  const cancelledOccurrence = occurrence?.status === "CANCELLED";
  const cancelled = cancelledSeries || cancelledOccurrence;
  const actions = actionsOf(event);
  const meeting = isParentMeeting(event);
  const firstDay = event.allDay ? (event.startDate ?? "") : event.startAt ? isoInZone(event.startAt, event.timeZone) : "";
  const summary = firstDay ? summarizeRule(event.recurrenceRule, firstDay, event.timeZone) : null;

  const restore = useSubmit({
    mutationFn: async () => restoreOccurrence(event.id, occurrenceId!),
    invalidate: [ck.all],
    successMessage: t("detail.restored"),
    errorFallback: t("detail.actionFailed"),
  });

  function startEdit() {
    if (recurring && occurrenceId && occurrence && !cancelledSeries) setPending("choose-edit");
    else {
      setTarget("series");
      setPending("edit");
    }
  }
  function startCancel() {
    if (recurring && occurrenceId && occurrence && !cancelledOccurrence) setPending("choose-cancel");
    else {
      setTarget("series");
      setPending("cancel");
    }
  }

  return (
    <div className="flex flex-col gap-4" data-testid="event-detail" data-status={cancelled ? "cancelled" : "scheduled"}>
      <div className="flex flex-wrap items-center gap-2">
        <span className="inline-flex items-center gap-1.5 rounded-full bg-muted px-3 py-1 text-xs font-bold">
          <ItemIcon item={{ source: event.scope === "PERSONAL" ? "PERSONAL_EVENT" : "EVENT", kind: event.eventType }} className="size-3.5" />
          {event.scope === "PERSONAL" ? t("sources.PERSONAL_EVENT") : t(`kinds.${event.eventType}`)}
        </span>
        <span className="inline-flex items-center gap-1.5 rounded-full bg-muted px-3 py-1 text-xs font-semibold">{t(`scopes.${event.scope}`)}</span>
        {cancelled ? <span className="rounded-full bg-destructive/15 px-3 py-1 text-xs font-bold text-destructive" data-testid="event-cancelled">{t("cancelled")}</span> : null}
        {occurrence?.modified && !cancelled ? <span className="rounded-full bg-warning/20 px-3 py-1 text-xs font-bold">{t("modified")}</span> : null}
      </div>

      <h2 className={cn("text-2xl font-bold break-words", cancelled && "line-through")} data-testid="event-title">
        {values.title}
      </h2>

      {cancelledOccurrence && !cancelledSeries ? <Notice tone="warning">{occurrence?.reason ? t("detail.occurrenceCancelledReason", { reason: occurrence.reason }) : t("detail.occurrenceCancelled")}</Notice> : null}
      {cancelledSeries ? <Notice tone="warning">{event.cancellationReason ? t("detail.cancelledReason", { reason: event.cancellationReason }) : t("detail.cancelled")}</Notice> : null}

      <dl className="flex flex-col gap-3">
        <Row icon={ClockIcon} label={t("detail.when")}>
          <WhenText values={values} system={system} locale={locale} />
        </Row>
        {!values.allDay ? (
          <Row icon={GlobeIcon} label={t("detail.timeZone")}>
            <span data-testid="event-zone">{event.timeZone}</span>
          </Row>
        ) : null}
        {values.location ? (
          <Row icon={MapPinIcon} label={t("detail.where")}>
            <span className="break-words">{values.location}</span>
          </Row>
        ) : null}
        {summary ? (
          <Row icon={RepeatIcon} label={t("detail.repeats")}>
            <span data-testid="event-rule">{ruleText(summary, system)}</span>
          </Row>
        ) : recurring ? (
          <Row icon={RepeatIcon} label={t("detail.repeats")}>
            <span>{t("detail.repeatsCustom")}</span>
          </Row>
        ) : null}
        {event.createdByName && event.scope !== "PERSONAL" ? (
          <Row icon={UserIcon} label={t("detail.organiser")}>
            {event.createdByName}
          </Row>
        ) : null}
      </dl>

      {!values.allDay ? <ZoneNote item={{ allDay: false, timeZone: event.timeZone, startAt: values.startAt, endAt: values.endAt }} viewerZone={viewerZone} /> : null}

      {values.description ? <p className="rounded-xl bg-muted/50 p-3 whitespace-pre-wrap break-words" data-testid="event-description">{values.description}</p> : null}

      {meeting ? <Notice tone="info">{event.capabilities?.canEdit ? t("detail.meetingOrganiserNote") : t("detail.meetingGuardianNote")}</Notice> : null}

      <RsvpPanel event={event} />

      {actions.edit || actions.cancel || actions.delete || (cancelledOccurrence && event.capabilities?.canEdit && !cancelledSeries) ? (
        <div className="flex flex-wrap gap-2 border-t pt-4" data-testid="event-actions">
          {actions.edit ? (
            <Button type="button" variant="outline" onClick={startEdit} data-testid="event-edit">
              <PencilIcon className="size-4" aria-hidden />
              {meeting ? t("detail.reschedule") : t("detail.edit")}
            </Button>
          ) : null}
          {cancelledOccurrence && !cancelledSeries && event.capabilities?.canEdit ? (
            <Button type="button" variant="outline" disabled={restore.isPending} onClick={() => restore.mutate(undefined)} data-testid="event-restore">
              <RotateCcwIcon className="size-4" aria-hidden />
              {t("detail.restore")}
            </Button>
          ) : null}
          {actions.cancel && !cancelledOccurrence ? (
            <Button type="button" variant="outline" onClick={startCancel} data-testid="event-cancel">
              <BanIcon className="size-4" aria-hidden />
              {recurring && occurrenceId ? t("detail.cancelEllipsis") : t("detail.cancel")}
            </Button>
          ) : null}
          {actions.delete ? (
            <Button type="button" variant="destructive" onClick={() => setPending("delete")} data-testid="event-delete">
              <Trash2Icon className="size-4" aria-hidden />
              {t("detail.delete")}
            </Button>
          ) : null}
        </div>
      ) : null}
      {restore.isError ? <p role="alert" className="text-sm font-medium text-destructive">{errorMessage(restore.error, t("detail.actionFailed"))}</p> : null}

      {pending === "choose-edit" || pending === "choose-cancel" ? (
        <ChoiceDialog
          kind={pending === "choose-edit" ? "edit" : "cancel"}
          onChoose={(choice) => {
            setTarget(choice);
            setPending(pending === "choose-edit" ? "edit" : "cancel");
          }}
          onClose={() => setPending(null)}
        />
      ) : null}

      {pending === "edit" && target === "series" ? (
        <EventFormDialog mode="edit" event={event} userTimeZone={userTimeZone} viewerZone={viewerZone} onClose={() => setPending(null)} onSaved={() => setPending(null)} />
      ) : null}
      {pending === "edit" && target === "occurrence" && occurrenceId && occurrence ? (
        <OccurrenceFormDialog eventId={event.id} occurrenceId={occurrenceId} values={occurrence} onClose={() => setPending(null)} />
      ) : null}

      {pending === "cancel" ? (
        <CancelDialog
          event={event}
          occurrenceId={target === "occurrence" ? occurrenceId : null}
          onClose={() => setPending(null)}
          onDone={() => setPending(null)}
        />
      ) : null}

      {pending === "delete" ? <DeleteDialog event={event} onClose={() => setPending(null)} onDone={onGone} /> : null}
    </div>
  );
}

function Notice({ tone, children }: { tone: "warning" | "info"; children: React.ReactNode }) {
  return (
    <p role="note" className={cn("flex items-start gap-2 rounded-xl p-3 text-sm font-medium", tone === "warning" ? "bg-warning/15" : "bg-info/10")}>
      <InfoIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
      <span>{children}</span>
    </p>
  );
}

function Row({ icon: Icon, label, children }: { icon: typeof ClockIcon; label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-start gap-3">
      <Icon className="mt-1 size-4 shrink-0 text-muted-foreground" aria-hidden />
      <div className="min-w-0">
        <dt className="text-xs font-semibold text-muted-foreground">{label}</dt>
        <dd className="font-medium">{children}</dd>
      </div>
    </div>
  );
}

/** "Monday, December 7, 2026" and "9:00 AM - 10:30 AM" in the event's own zone (all-day events by their dates). */
function WhenText({ values, system, locale }: { values: OccurrenceValues; system: CalendarSystem; locale: string }) {
  const t = useTranslations("family.calendar");
  if (values.allDay && values.startDate) {
    const end = values.endDate ?? values.startDate;
    return (
      <span data-testid="event-when">
        {end === values.startDate ? dayLabel(values.startDate, system, locale) : t("dateRange", { from: dayLabel(values.startDate, system, locale), to: dayLabel(end, system, locale) })}
        <span className="block text-sm font-normal text-muted-foreground">{t("allDay")}</span>
      </span>
    );
  }
  const startDay = isoInZone(values.startAt, values.timeZone);
  const lastMoment = new Date(Math.max(new Date(values.startAt).getTime(), new Date(values.endAt).getTime() - 1));
  const endDay = isoInZone(lastMoment, values.timeZone);
  if (endDay === startDay) {
    return (
      <span data-testid="event-when">
        {dayLabel(startDay, system, locale)}
        <span className="block text-sm font-normal text-muted-foreground">{timeRange(values.startAt, values.endAt, values.timeZone, locale)}</span>
      </span>
    );
  }
  const [start, end] = [values.startAt, values.endAt];
  return (
    <span data-testid="event-when">
      {t("dateRange", {
        from: `${dayLabel(startDay, system, locale)} ${timeRange(start, start, values.timeZone, locale)}`,
        to: `${dayLabel(isoInZone(end, values.timeZone), system, locale)} ${timeRange(end, end, values.timeZone, locale)}`,
      })}
    </span>
  );
}

// --- answers ---------------------------------------------------------------------------------

const ANSWER_ICON = { YES: CheckIcon, NO: XIcon, MAYBE: CircleHelpIcon } as const;

/**
 * The caller's answer (when they were asked), totals (managers) and, for a parent meeting's organiser, who answered what:
 * each part appears only when the API sent it. Repeating events take no answers.
 */
function RsvpPanel({ event }: { event: EventView }) {
  const t = useTranslations("family.calendar");
  const locale = useLocale();
  const mode = rsvpMode(event);
  const mine = myAnswer(event);
  const totals = totalsOf(event);
  const participants = participantsOf(event);
  const answer = useSubmit({
    mutationFn: async (status: RsvpAnswer) => answerEvent(event.id, status),
    invalidate: [ck.all],
    errorFallback: t("rsvp.failed"),
    toastOnError: false,
  });

  if (mode === "none" && !totals && !participants) return null;
  if (mode === "recurring") {
    return (
      <p className="flex items-start gap-2 rounded-xl bg-muted/60 p-3 text-sm text-muted-foreground" data-testid="rsvp-recurring">
        <InfoIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
        {t("rsvp.recurring")}
      </p>
    );
  }
  return (
    <section aria-labelledby="rsvp-heading" className="flex flex-col gap-3 rounded-2xl border p-4" data-testid="rsvp-panel">
      <h3 id="rsvp-heading" className="font-bold">
        {t("rsvp.title")}
      </h3>
      {mode === "answerable" ? (
        <>
          <p className="text-sm text-muted-foreground">{mine ? t("rsvp.yourAnswer", { answer: t(`rsvp.answers.${mine}`) }) : t("rsvp.pending")}</p>
          <div role="group" aria-label={t("rsvp.question")} className="flex flex-wrap gap-2">
            {RSVP_ANSWERS.map((status) => {
              const AnswerIcon = ANSWER_ICON[status];
              return (
                <Button
                  key={status}
                  type="button"
                  variant={mine === status ? "default" : "outline"}
                  aria-pressed={mine === status}
                  disabled={answer.isPending}
                  onClick={() => answer.mutate(status)}
                  data-testid={`rsvp-${status}`}
                >
                  <AnswerIcon className="size-4" aria-hidden />
                  {t(`rsvp.answers.${status}`)}
                </Button>
              );
            })}
          </div>
        </>
      ) : null}
      {mode === "closed" ? (
        <p className="text-sm text-muted-foreground" data-testid="rsvp-closed">
          {mine ? t("rsvp.closedWithAnswer", { answer: t(`rsvp.answers.${mine}`) }) : t("rsvp.closed")}
        </p>
      ) : null}
      {answer.isError ? <p role="alert" className="text-sm font-medium text-destructive">{errorMessage(answer.error, t("rsvp.failed"))}</p> : null}

      {totals ? (
        <ul className="flex flex-wrap gap-2 text-sm" aria-label={t("rsvp.totals")} data-testid="rsvp-totals">
          {(["yes", "maybe", "no", "pending"] as const).map((key) => (
            <li key={key} className="rounded-full bg-muted px-3 py-1 font-semibold">
              {t(`rsvp.count.${key}`, { count: totals[key] })}
            </li>
          ))}
        </ul>
      ) : null}

      {participants ? (
        <div data-testid="rsvp-participants">
          <h4 className="mb-2 text-sm font-bold">{t("rsvp.participants")}</h4>
          {participants.length === 0 ? (
            <EmptyNotice>{t("rsvp.noParticipants")}</EmptyNotice>
          ) : (
            <ul className="flex flex-col divide-y rounded-xl border">
              {participants.map((p, i) => (
                <li key={`${p.name}:${i}`} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2 text-sm">
                  <span className="font-medium">{p.name}</span>
                  <span className="font-semibold">
                    {p.status === "UNKNOWN" ? t("rsvp.answers.PENDING") : t(`rsvp.answers.${p.status}`)}
                    {p.respondedAt ? <span className="ms-2 font-normal text-muted-foreground">{new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }).format(new Date(p.respondedAt))}</span> : null}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : null}
    </section>
  );
}

// --- "only this event" or "all events" ------------------------------------------------------

function ChoiceDialog({ kind, onChoose, onClose }: { kind: "edit" | "cancel"; onChoose: (choice: "occurrence" | "series") => void; onClose: () => void }) {
  const t = useTranslations("family.calendar");
  const tc = useTranslations("console");
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent data-testid="occurrence-choice">
        <DialogHeader>
          <DialogTitle>{kind === "edit" ? t("choice.editTitle") : t("choice.cancelTitle")}</DialogTitle>
          <DialogDescription>{t("choice.description")}</DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-3">
          <Button type="button" variant="outline" className="h-auto min-h-14 flex-col items-start gap-0.5 py-3 text-start" onClick={() => onChoose("occurrence")} data-testid="choice-occurrence">
            <span className="font-bold">{t("choice.occurrence")}</span>
            <span className="text-sm font-normal text-muted-foreground">{kind === "edit" ? t("choice.occurrenceEditHelp") : t("choice.occurrenceCancelHelp")}</span>
          </Button>
          <Button type="button" variant="outline" className="h-auto min-h-14 flex-col items-start gap-0.5 py-3 text-start" onClick={() => onChoose("series")} data-testid="choice-series">
            <span className="font-bold">{t("choice.series")}</span>
            <span className="text-sm font-normal text-muted-foreground">{kind === "edit" ? t("choice.seriesEditHelp") : t("choice.seriesCancelHelp")}</span>
          </Button>
        </div>
        <DialogFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            {tc("cancel")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function CancelDialog({ event, occurrenceId, onClose, onDone }: { event: EventView; occurrenceId: string | null; onClose: () => void; onDone: () => void }) {
  const t = useTranslations("family.calendar");
  const tc = useTranslations("console");
  const [reason, setReason] = useState("");
  const meeting = isParentMeeting(event);
  const cancel = useSubmit({
    mutationFn: async () => (occurrenceId ? cancelOccurrence(event.id, occurrenceId, reason) : cancelEvent(event.id, reason)),
    invalidate: [ck.all],
    successMessage: occurrenceId ? t("cancelDialog.doneOccurrence") : t("cancelDialog.done"),
    errorFallback: t("detail.actionFailed"),
    toastOnError: false,
    onSuccess: onDone,
  });
  return (
    <Dialog open onOpenChange={(open) => !open && !cancel.isPending && onClose()}>
      <DialogContent data-testid="cancel-dialog">
        <DialogHeader>
          <DialogTitle>{occurrenceId ? t("cancelDialog.titleOccurrence") : event.recurring ? t("cancelDialog.titleSeries") : t("cancelDialog.title")}</DialogTitle>
          <DialogDescription>{occurrenceId ? t("cancelDialog.helpOccurrence") : event.recurring ? t("cancelDialog.helpSeries") : t("cancelDialog.help")}</DialogDescription>
        </DialogHeader>
        {event.scope !== "PERSONAL" ? <p className="rounded-xl bg-muted/60 p-3 text-sm">{meeting ? t("cancelDialog.meetingNotice") : t("cancelDialog.notice")}</p> : null}
        <label className="flex flex-col gap-1.5 text-sm font-medium">
          {t("cancelDialog.reason")}
          <Textarea value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} rows={3} data-testid="cancel-reason" />
        </label>
        {cancel.isError ? <p role="alert" className="text-sm font-medium text-destructive">{errorMessage(cancel.error, t("detail.actionFailed"))}</p> : null}
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose} disabled={cancel.isPending}>
            {tc("cancel")}
          </Button>
          <Button type="button" variant="destructive" onClick={() => cancel.mutate(undefined)} disabled={cancel.isPending} data-testid="cancel-confirm">
            {occurrenceId ? t("cancelDialog.confirmOccurrence") : event.recurring ? t("cancelDialog.confirmSeries") : t("cancelDialog.confirm")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function DeleteDialog({ event, onClose, onDone }: { event: EventView; onClose: () => void; onDone: () => void }) {
  const t = useTranslations("family.calendar");
  const queryClient = useQueryClient();
  const remove = useSubmit({
    mutationFn: async () => deleteEvent(event.id),
    // The deleted event's own query is dropped, not refetched (that would be a 404 on a screen that is closing).
    invalidate: [ck.windows],
    successMessage: t("deleteDialog.done"),
    errorFallback: t("detail.actionFailed"),
    toastOnError: false,
    onSuccess: () => {
      queryClient.removeQueries({ queryKey: ck.event(event.id) });
      onDone();
    },
  });
  return (
    <ConfirmDialog
      title={t("deleteDialog.title")}
      description={event.scope === "PERSONAL" ? t("deleteDialog.helpPersonal") : t("deleteDialog.help")}
      confirmLabel={t("deleteDialog.confirm")}
      pending={remove.isPending}
      error={remove.isError ? errorMessage(remove.error, t("detail.actionFailed")) : null}
      onConfirm={() => remove.mutate(undefined)}
      onClose={onClose}
    />
  );
}
