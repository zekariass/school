"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { InfoIcon, Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useMemo, useRef, useState } from "react";
import { toast } from "sonner";
import { FormField } from "@/components/console/form-field";
import { ErrorNotice, LoadingRows } from "@/components/console/states";
import { CheckboxField, SelectField } from "@/components/school/fields";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/console/errors";
import { newIdempotencyKey } from "@/lib/console/idempotency";
import { schoolScopes, scopeAllowsAllDay, scopeAllowsRecurrence, scopeOffersRsvp, type EventCapabilities, type EventScope } from "@/lib/calendar/access";
import {
  DESCRIPTION_MAX,
  LOCATION_MAX,
  TITLE_MAX,
  buildCreateBody,
  buildUpdateBody,
  formFromEvent,
  formProblems,
  initialForm,
  isEmptyUpdate,
  problemFor,
  repeats,
  startDayOf,
  updateWarnings,
  withAllDay,
  withScope,
  withStart,
  type EventFormState,
  type FormField as Field,
  type ProblemCode,
} from "@/lib/calendar/form";
import { ck, createPersonalEvent, createSchoolEvent, updateEvent, type CalendarEvent } from "@/lib/calendar/queries";
import type { RecurrenceProblem } from "@/lib/calendar/recurrence";
import { isParentMeeting, totalsOf, type EventView } from "@/lib/calendar/rsvp";
import type { SchoolRef } from "./calendar-view";
import { RecurrenceFields } from "./recurrence-fields";
import { ScopeSelect, ScopeTargets, TypeSelect } from "./scope-fields";
import { useCalendarCapabilities } from "./use-calendar";

const PERSONAL = "personal";

type Props =
  | {
      mode: "create";
      /** The schools where the person works: the ones that may offer school events (the API's capabilities decide). */
      schools: readonly SchoolRef[];
      fixedSchool?: SchoolRef;
      /** The day clicked in the calendar. */
      defaultDay: string | null;
      userTimeZone: string | null;
      viewerZone: string | null;
      onClose: () => void;
      onSaved?: (event: CalendarEvent) => void;
    }
  | {
      mode: "edit";
      event: EventView;
      userTimeZone: string | null;
      viewerZone: string | null;
      onClose: () => void;
      onSaved?: (event: CalendarEvent) => void;
    };

/**
 * Creates or edits an event: where (personal or a school), what kind, the picker of its audience, when (timed or all-day,
 * in the event's explicit time zone), how often, and whether to ask for answers. What is offered comes from the API's
 * capabilities; the API checks everything again and its (localized) refusals show inline without losing the form.
 */
export function EventFormDialog(props: Props) {
  const t = useTranslations("school.calendar");
  const editing = props.mode === "edit";
  return (
    <Dialog open onOpenChange={(open) => !open && props.onClose()}>
      <DialogContent className="max-h-[92dvh] overflow-y-auto sm:max-w-2xl" data-testid="event-form">
        <DialogHeader>
          <DialogTitle>{editing ? (isParentMeeting(props.event) ? t("form.rescheduleTitle") : t("form.editTitle")) : t("form.createTitle")}</DialogTitle>
          <DialogDescription>{editing ? t("form.editHelp") : t("form.createHelp")}</DialogDescription>
        </DialogHeader>
        {props.mode === "create" ? <CreateForm {...props} /> : <EditForm {...props} />}
      </DialogContent>
    </Dialog>
  );
}

function CreateForm(props: Extract<Props, { mode: "create" }>) {
  const t = useTranslations("school.calendar");
  const first = props.fixedSchool?.id ?? (props.schools.length === 1 ? props.schools[0]!.id : PERSONAL);
  const [where, setWhere] = useState<string>(first);
  const schoolId = where === PERSONAL ? null : where;
  const caps = useCalendarCapabilities(schoolId);
  const offered = schoolId ? schoolScopes(caps.data) : [];
  const personalZone = props.userTimeZone ?? props.viewerZone ?? "UTC";
  const zone = schoolId ? (caps.data?.timeZone ?? "UTC") : personalZone;
  const options = [{ value: PERSONAL, label: t("where.personal") }, ...props.schools.map((s) => ({ value: s.id, label: s.name }))];

  return (
    <div className="flex flex-col gap-4">
      {!props.fixedSchool && props.schools.length > 0 ? (
        <SelectField label={t("where.label")} value={where} onChange={setWhere} options={options} />
      ) : null}
      {schoolId && caps.isPending ? <LoadingRows rows={2} /> : null}
      {schoolId && caps.isError ? <ErrorNotice message={errorMessage(caps.error, t("form.capsFailed"))} onRetry={() => void caps.refetch()} /> : null}
      {schoolId && caps.data && offered.length === 0 ? <p className="rounded-xl bg-muted p-3 text-sm" data-testid="form-no-access">{t("form.noAccess")}</p> : null}
      {(schoolId === null || (caps.data && offered.length > 0)) ? (
        // Remounted when the place or the time zone behind it changes, so the form starts from the right defaults.
        <FormBody
          key={`${where}:${zone}`}
          mode="create"
          schoolId={schoolId}
          caps={caps.data ?? null}
          scopes={schoolId ? offered : ["PERSONAL"]}
          initial={initialForm({ scope: schoolId ? offered[0]! : "PERSONAL", schoolId, timeZone: zone, now: new Date(), day: props.defaultDay })}
          viewerZone={props.viewerZone}
          schoolZone={schoolId ? zone : null}
          onClose={props.onClose}
          onSaved={props.onSaved}
        />
      ) : null}
    </div>
  );
}

function EditForm(props: Extract<Props, { mode: "edit" }>) {
  const initial = useMemo(() => formFromEvent(props.event), [props.event]);
  return (
    <FormBody
      mode="edit"
      schoolId={props.event.schoolId ?? null}
      caps={null}
      scopes={[initial.scope]}
      initial={initial}
      original={props.event}
      viewerZone={props.viewerZone}
      schoolZone={null}
      onClose={props.onClose}
      onSaved={props.onSaved}
    />
  );
}

type FormBodyProps = {
  mode: "create" | "edit";
  schoolId: string | null;
  caps: EventCapabilities | null;
  scopes: readonly EventScope[];
  initial: EventFormState;
  original?: EventView;
  viewerZone: string | null;
  /** The school's zone (the default of a school event); null for personal events. */
  schoolZone: string | null;
  onClose: () => void;
  onSaved?: (event: CalendarEvent) => void;
};

function FormBody({ mode, schoolId, caps, scopes, initial, original, viewerZone, schoolZone, onClose, onSaved }: FormBodyProps) {
  const t = useTranslations("school.calendar");
  const tp = useTranslations("school.calendar.problems");
  const tc = useTranslations("console");
  const queryClient = useQueryClient();
  const [state, setState] = useState<EventFormState>(initial);
  const [showProblems, setShowProblems] = useState(false);
  const key = useRef<{ fingerprint: string; key: string } | null>(null);
  const editing = mode === "edit";

  const problems = formProblems(state, new Date());
  const shown = (field: Field): ProblemCode | null => (showProblems ? problemFor(problems, field) : null);
  const message = (field: Field): string | null => {
    const code = shown(field);
    return code ? tp(code, { max: code === "titleTooLong" ? TITLE_MAX : code === "descriptionTooLong" ? DESCRIPTION_MAX : LOCATION_MAX, years: 10, interval: 99, count: 1000 }) : null;
  };
  const set = (patch: Partial<EventFormState>) => setState((s) => ({ ...s, ...patch }));

  const warnings = editing && original ? updateWarnings(state, original) : null;
  const meeting = state.scope === "PARENT_MEETING";
  const startIso = startDayOf(state);

  const save = useMutation({
    mutationFn: async (): Promise<CalendarEvent> => {
      if (editing && original) return updateEvent(original.id, buildUpdateBody(state, original));
      const body = buildCreateBody(state);
      // The same submission keeps its key (a request that did reach the API is not applied twice); a changed form gets a new one.
      const fingerprint = JSON.stringify([schoolId, body]);
      if (key.current?.fingerprint !== fingerprint) key.current = { fingerprint, key: newIdempotencyKey() };
      return schoolId ? createSchoolEvent(schoolId, body, key.current.key) : createPersonalEvent(body, key.current.key);
    },
    onSuccess: async (event) => {
      const total = totalsOf(event)?.total;
      toast.success(isParentMeeting(event) && !editing && total !== undefined ? t("form.meetingCreated", { count: total }) : editing ? t("form.saved") : t("form.created"));
      await queryClient.invalidateQueries({ queryKey: ck.all });
      onSaved?.(event);
      onClose();
    },
  });

  function submit() {
    setShowProblems(true);
    if (problems.length > 0) return;
    if (editing && original && isEmptyUpdate(buildUpdateBody(state, original))) {
      onClose();
      return;
    }
    save.mutate();
  }

  return (
    <form
      noValidate
      className="flex flex-col gap-4"
      onSubmit={(event) => {
        event.preventDefault();
        submit();
      }}
      data-testid="event-form-body"
    >
      {!editing && scopes.length > 1 ? <ScopeSelect scopes={scopes} value={state.scope} onChange={(scope) => setState((s) => withScope(s, scope))} /> : null}
      {editing ? <p className="text-sm text-muted-foreground">{t("form.audienceFixed", { scope: t(`scopes.${state.scope}`) })}</p> : null}
      {!editing && schoolId && caps ? (
        <ScopeTargets schoolId={schoolId} caps={caps} state={state} onChange={set} problem={(field) => message(field)} />
      ) : null}
      {!editing ? <TypeSelect scope={state.scope} value={state.eventType} onChange={(type) => set({ eventType: type as EventFormState["eventType"] })} /> : null}

      {meeting && !editing ? (
        <div className="flex items-start gap-2 rounded-xl bg-info/10 p-3 text-sm" data-testid="meeting-privacy">
          <InfoIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
          <div className="flex flex-col gap-1">
            <p>{t("form.meetingWho")}</p>
            <p className="font-semibold">{t("form.meetingPrivacy")}</p>
          </div>
        </div>
      ) : null}

      <FormField label={t("fields.title")} error={message("title")}>
        {(props) => <Input {...props} value={state.title} onChange={(e) => set({ title: e.target.value })} data-testid="event-title-input" autoFocus />}
      </FormField>
      {meeting ? <p className="-mt-2 text-sm text-muted-foreground">{t("form.meetingTitleHint")}</p> : null}
      <FormField label={t("fields.location")} error={message("location")}>
        {(props) => <Input {...props} value={state.location} onChange={(e) => set({ location: e.target.value })} data-testid="event-location-input" />}
      </FormField>
      <FormField label={t("fields.description")} error={message("description")}>
        {(props) => <Textarea {...props} value={state.description} rows={3} onChange={(e) => set({ description: e.target.value })} data-testid="event-description-input" />}
      </FormField>

      {scopeAllowsAllDay(state.scope) ? (
        <CheckboxField label={t("fields.allDay")} checked={state.allDay} onChange={(allDay) => setState((s) => withAllDay(s, allDay))} />
      ) : null}
      {state.allDay ? (
        <div className="grid gap-3 sm:grid-cols-2">
          <FormField label={t("fields.startDate")} error={message("start")}>
            {(props) => <Input {...props} type="date" value={state.startDate} onChange={(e) => set({ startDate: e.target.value, endDate: state.endDate < e.target.value ? e.target.value : state.endDate })} data-testid="event-start-date" />}
          </FormField>
          <FormField label={t("fields.endDate")} error={message("end")} hint={t("fields.endDateHint")}>
            {(props) => <Input {...props} type="date" value={state.endDate} min={state.startDate || undefined} onChange={(e) => set({ endDate: e.target.value })} data-testid="event-end-date" />}
          </FormField>
        </div>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          <FormField label={t("fields.start")} error={message("start")}>
            {(props) => (
              <Input
                {...props}
                type="datetime-local"
                value={state.startLocal}
                // moving the start moves the end with it, keeping the length
                onChange={(e) => setState((s) => withStart(s, e.target.value))}
                data-testid="event-start"
              />
            )}
          </FormField>
          <FormField label={t("fields.end")} error={message("end")}>
            {(props) => <Input {...props} type="datetime-local" value={state.endLocal} onChange={(e) => set({ endLocal: e.target.value })} data-testid="event-end" />}
          </FormField>
        </div>
      )}

      <TimeZoneField value={state.timeZone} schoolZone={schoolZone} viewerZone={viewerZone} onChange={(timeZone) => set({ timeZone })} error={message("timeZone")} allDay={state.allDay} />

      {scopeAllowsRecurrence(state.scope) ? (
        <RecurrenceFields
          draft={state.recurrence}
          startIso={startIso}
          lockedRule={state.lockedRule}
          onChange={(recurrence) => set({ recurrence, rsvpEnabled: recurrence.freq !== "NONE" ? false : state.rsvpEnabled })}
          problem={shown("recurrence") as RecurrenceProblem | null}
        />
      ) : null}

      {scopeOffersRsvp(state.scope) ? (
        <CheckboxField
          label={t("fields.rsvp")}
          checked={state.rsvpEnabled}
          disabled={repeats(state)}
          onChange={(rsvpEnabled) => set({ rsvpEnabled })}
          hint={repeats(state) ? t("fields.rsvpRecurring") : t("fields.rsvpHint")}
        />
      ) : null}
      {message("rsvp") ? <p role="alert" className="text-sm font-medium text-destructive">{message("rsvp")}</p> : null}

      {warnings?.clearsExceptions ? <p className="rounded-xl bg-warning/15 p-3 text-sm font-medium" data-testid="warn-clears">{t("form.warnClears")}</p> : null}
      {warnings?.resetsAnswers ? <p className="rounded-xl bg-warning/15 p-3 text-sm font-medium" data-testid="warn-resets">{meeting ? t("form.warnResetsMeeting") : t("form.warnResets")}</p> : null}
      {editing && !meeting && state.scope !== "PERSONAL" ? <p className="text-sm text-muted-foreground">{t("form.editNotice")}</p> : null}

      {save.isError ? (
        <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="event-form-error">
          {errorMessage(save.error, t("saveFailed"))}
        </p>
      ) : null}
      {showProblems && problems.length > 0 ? <p className="text-sm font-medium text-destructive" role="status">{t("form.fixProblems")}</p> : null}

      <DialogFooter>
        <Button type="button" variant="outline" onClick={onClose} disabled={save.isPending}>
          {tc("cancel")}
        </Button>
        <Button type="submit" disabled={save.isPending} data-testid="event-save">
          {save.isPending ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : null}
          {save.isPending ? tc("saving") : editing ? t("form.save") : meeting ? t("form.createMeeting") : t("form.create")}
        </Button>
      </DialogFooter>
    </form>
  );
}

/** Zones the person can pick: the event's own first, then the school's and the viewer's, then every zone the browser knows. */
function zoneChoices(current: string, schoolZone: string | null, viewerZone: string | null): string[] {
  let all: string[] = [];
  try {
    all = (Intl as unknown as { supportedValuesOf?: (key: string) => string[] }).supportedValuesOf?.("timeZone") ?? [];
  } catch {
    all = [];
  }
  const head = [current, schoolZone, viewerZone, "UTC"].filter((z): z is string => !!z);
  return [...new Set([...head, ...all])];
}

/**
 * The time zone the times are read in. Always visible and always sent: never silently the browser's. A note says which zone
 * is the school's and which is the viewer's.
 */
function TimeZoneField({ value, schoolZone, viewerZone, onChange, error, allDay }: { value: string; schoolZone: string | null; viewerZone: string | null; onChange: (zone: string) => void; error: string | null; allDay: boolean }) {
  const t = useTranslations("school.calendar");
  const choices = useMemo(() => zoneChoices(value, schoolZone, viewerZone), [value, schoolZone, viewerZone]);
  return (
    <FormField label={t("fields.timeZone")} error={error} hint={allDay ? t("fields.timeZoneAllDayHint") : t("fields.timeZoneHint")}>
      {(props) => (
        <select {...props} value={value} onChange={(e) => onChange(e.target.value)} className="h-11 w-full rounded-md border border-input bg-transparent px-3 text-base" data-testid="event-zone-select">
          {choices.map((zone) => (
            <option key={zone} value={zone}>
              {zone === schoolZone ? `${zone} (${t("fields.schoolZone")})` : zone === viewerZone && zone !== schoolZone ? `${zone} (${t("fields.yourZone")})` : zone}
            </option>
          ))}
        </select>
      )}
    </FormField>
  );
}
