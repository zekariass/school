"use client";

import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCheckIcon, Loader2Icon, LockIcon, RefreshCwIcon, SaveIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { ConfirmDialog } from "@/components/school/confirm-dialog";
import { useSchool } from "@/components/school/school-context";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { classAttendanceAbilities, type ClassAttendanceAbilities } from "@/lib/attendance/access";
import { isIsoDate } from "@/lib/attendance/dates";
import { ak, fetchRoll, putRoll } from "@/lib/attendance/queries";
import { periodRefusal, periodScope, refusalNeedsReload, withCreatedSubject } from "@/lib/attendance/periods";
import { isKnownSlot } from "@/lib/attendance/slots";
import {
  applyResult,
  buildResolve,
  buildWrite,
  conflictsOf,
  isUnsaved,
  keepTheirs,
  pendingEditCount,
  setMinutes,
  setNote,
  setStatus,
  shownMark,
  summarize,
  unsavedStudents,
  type Conflicts,
  type Drafts,
  type RollView,
} from "@/lib/attendance/roll";
import { STATUSES, nextStatus, previousStatus, type AttendanceStatus } from "@/lib/attendance/status";
import { errorMessage } from "@/lib/console/errors";
import type { ClassView } from "@/lib/school/queries";
import { cn } from "@/lib/utils";
import { ConflictPanel } from "./conflict-panel";
import { DateSlotBar } from "./date-slot-bar";
import { PeriodPickers, usePeriodChoices } from "./period-pickers";
import { RollRow } from "./roll-row";
import { STATUS_UI, useFormatInstantIn } from "./status";

/**
 * The quick roll of a class: everyone starts as present, a tap cycles a student's mark, "Mark all present" saves
 * everybody who has no mark yet. Unsaved edits live in memory only (children's data never goes to browser storage).
 * Writes carry the versions the API needs to tell a real conflict from an update; a conflict is resolved per student.
 */
export function ClassAttendanceTab({ cls, initialDate, initialSlot }: { cls: ClassView; initialDate?: string; initialSlot?: string }) {
  const t = useTranslations("school.attendance");
  const { permissions } = useSchool();
  const abilities = classAttendanceAbilities(cls, permissions);
  if (!abilities.canView) return <EmptyNotice>{t("roll.notForYou")}</EmptyNotice>;
  return <Roll cls={cls} abilities={abilities} initialDate={initialDate} initialSlot={initialSlot} />;
}

function Roll({ cls, abilities, initialDate, initialSlot }: { cls: ClassView; abilities: ClassAttendanceAbilities; initialDate?: string; initialSlot?: string }) {
  const t = useTranslations("school.attendance");
  const { school } = useSchool();
  const queryClient = useQueryClient();
  const formatInstantIn = useFormatInstantIn();

  // `date` and `slot` null = the API's defaults (today in the school's zone, the school's first roll call).
  const [date, setDate] = useState<string | null>(isIsoDate(initialDate) ? initialDate : null);
  const [slot, setSlot] = useState<string | null>(initialSlot && isKnownSlot(initialSlot) ? initialSlot : null);
  // A period roll's optional subject (chosen before the roll exists) and the saved group of an elective; null = whole class.
  const [subjectId, setSubjectId] = useState<string | null>(null);
  const [groupId, setGroupId] = useState<string | null>(null);
  const choices = usePeriodChoices(cls);
  // `base` is the roll the edits are measured against and the versions come from: it only follows the server
  // while there are no unsaved edits, so a refresh behind the teacher's back can never turn an edit into a silent overwrite.
  const [base, setBase] = useState<RollView | null>(null);
  const [drafts, setDrafts] = useState<Drafts>({});
  const [conflicts, setConflicts] = useState<Conflicts>({});
  const [expanded, setExpanded] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [reasonMissing, setReasonMissing] = useState(false);
  const [announcement, setAnnouncement] = useState("");
  const [saveError, setSaveError] = useState<string | null>(null);
  const [discard, setDiscard] = useState<null | (() => void)>(null);

  const roll = useQuery({
    queryKey: ak.roll(school.id, cls.id, date, slot, groupId),
    queryFn: () => fetchRoll(school.id, cls.id, date, slot, groupId),
    placeholderData: keepPreviousData,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    retry: false,
  });

  const edits = pendingEditCount(drafts);
  const fetched = roll.isPlaceholderData ? undefined : roll.data;
  const [seen, setSeen] = useState<RollView | undefined>(undefined);
  if (fetched && fetched !== seen) {
    // A different day, roll call or group replaces the roll; the same one is refreshed only while nothing is unsaved.
    setSeen(fetched);
    if (!base || base.date !== fetched.date || base.slot !== fetched.slot || (base.groupId ?? null) !== (fetched.groupId ?? null) || edits === 0) setBase(fetched);
  }

  // A page with unsaved marks asks before it is closed.
  useEffect(() => {
    if (edits === 0) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [edits]);

  const save = useMutation({ mutationFn: (request: Parameters<typeof putRoll>[2]) => putRoll(school.id, cls.id, request) });
  const saving = save.isPending;
  const switching = roll.isFetching && roll.isPlaceholderData;

  const lockedOut = !!base?.locked && !base.mayEditLocked;
  const readOnly = !abilities.canTake || lockedOut;
  const busy = saving || switching;
  const needsReason = !!base?.locked && !!base.mayEditLocked;

  function guarded(action: () => void) {
    if (edits > 0) setDiscard(() => action);
    else action();
  }

  function resetEdits() {
    setDrafts({});
    setConflicts({});
    setExpanded(null);
    setSaveError(null);
    setReasonMissing(false);
  }

  function changeDay(next: string | null) {
    guarded(() => {
      resetEdits();
      setDate(next);
    });
  }

  // A roll is one per class, date and slot: another slot loads its own roll (never touching this one), for the whole
  // class and without the subject chosen here, which belonged to the lesson that was open.
  function changeSlot(next: string) {
    guarded(() => {
      resetEdits();
      setSubjectId(null);
      setGroupId(null);
      setSlot(next);
    });
  }

  function changeGroup(next: string | null) {
    guarded(() => {
      resetEdits();
      setGroupId(next);
    });
  }

  const scope = base ? periodScope(base, subjectId, choices.subjects) : undefined;

  function refresh() {
    guarded(() => {
      resetEdits();
      void roll.refetch().then((result) => {
        if (result.data) setBase(result.data);
      });
    });
  }

  async function send(request: Parameters<typeof putRoll>[2], current: RollView) {
    setSaveError(null);
    if (needsReason && !reason.trim()) {
      setReasonMissing(true);
      return;
    }
    setReasonMissing(false);
    try {
      const result = await save.mutateAsync(request);
      const applied = applyResult(current, drafts, conflicts, result);
      // The write answer does not repeat the subject: a roll that was just created for one keeps it on screen.
      const nextRoll = withCreatedSubject(applied.roll, !current.sessionId, scope?.subjectId, choices.subjects);
      setBase(nextRoll);
      setDrafts(applied.drafts);
      setConflicts(applied.conflicts);
      queryClient.setQueryData(ak.roll(school.id, cls.id, date, slot, groupId), nextRoll);
      void queryClient.invalidateQueries({ queryKey: ak.allDashboards(school.id) });
      const conflicted = conflictsOf(result).length;
      if (conflicted > 0) toast.warning(t("roll.savedConflicts", { count: conflicted }));
      else {
        toast.success(t("roll.saved"));
        setAnnouncement(t("roll.saved"));
      }
    } catch (error) {
      const refusal = periodRefusal(error);
      const detail = errorMessage(error, t("roll.loadFailed"));
      // The roll has another subject or the group is gone: what is on screen is stale, so a refresh is offered.
      if (refusalNeedsReload(refusal)) void queryClient.invalidateQueries({ queryKey: ak.allRolls(school.id, cls.id) });
      setSaveError(refusalNeedsReload(refusal) ? `${detail} ${t("periods.reloadHint")}` : detail);
    }
  }

  function saveEdits(markAllPresent: boolean) {
    if (!base) return;
    void send(buildWrite({ roll: base, drafts, markAllPresent, overrideReason: reason, scope }), base);
  }

  function resolveMine(studentIds: string[]) {
    if (!base) return;
    void send(buildResolve(base, conflicts, studentIds, reason, scope), base);
  }

  function resolveTheirs(studentIds: string[]) {
    let next = { drafts, conflicts };
    for (const id of studentIds) next = keepTheirs(next.drafts, next.conflicts, id);
    setDrafts(next.drafts);
    setConflicts(next.conflicts);
  }

  function announce(name: string, status: AttendanceStatus) {
    setAnnouncement(t("roll.announce", { name, status: t(`status.${status}`) }));
  }

  if (!base) {
    if (roll.isError) {
      return <ErrorNotice message={errorMessage(roll.error, t("roll.loadFailed"))} onRetry={() => void roll.refetch()} />;
    }
    return <LoadingRows rows={5} />;
  }

  const students = [...base.students].sort((a, b) => a.displayName.localeCompare(b.displayName));
  const counts = summarize(base.students, drafts);
  const unsaved = unsavedStudents(base).length;
  const conflictIds = new Set(Object.keys(conflicts));
  const rowsDisabled = readOnly || busy;

  return (
    <div className="flex flex-col gap-4" data-testid="class-roll" aria-busy={busy}>
      <DateSlotBar
        date={base.date}
        timeZone={base.timeZone}
        slot={base.slot}
        slots={base.slots}
        busy={busy}
        onDate={(next) => changeDay(next)}
        onToday={() => changeDay(null)}
        onSlot={changeSlot}
      />

      <PeriodPickers
        slot={base.slot}
        sessionId={base.sessionId}
        rollSubjectName={base.subjectName}
        choices={choices}
        subjectId={subjectId}
        groupId={groupId}
        disabled={busy}
        onSubject={setSubjectId}
        onGroup={changeGroup}
      />

      <div className="flex flex-wrap items-center gap-2">
        <p className="me-auto text-sm text-muted-foreground">{readOnly ? "" : t("roll.hint")}</p>
        <Button variant="outline" size="sm" onClick={refresh} disabled={busy} data-testid="roll-refresh">
          <RefreshCwIcon className={cn("size-4", roll.isFetching && "animate-spin")} aria-hidden />
          {t("roll.refresh")}
        </Button>
      </div>

      {roll.isError ? <ErrorNotice message={errorMessage(roll.error, t("roll.loadFailed"))} onRetry={() => void roll.refetch()} /> : null}

      {cls.status === "ARCHIVED" ? (
        <p role="status" className="rounded-xl bg-muted p-3 text-sm font-medium">
          {t("roll.archived")}
        </p>
      ) : !abilities.canTake ? (
        <p role="status" className="rounded-xl bg-muted p-3 text-sm font-medium">
          {t("roll.readOnly")}
        </p>
      ) : null}

      {base.locked ? (
        <section className="flex flex-col gap-3 rounded-2xl border border-warning bg-warning/10 p-4" data-testid="locked-roll" aria-labelledby="locked-title">
          <div className="flex items-start gap-3">
            <LockIcon className="mt-0.5 size-5 shrink-0 text-warning" aria-hidden />
            <div className="min-w-0">
              <h2 id="locked-title" className="font-bold">
                {t("roll.locked.title")}
              </h2>
              <p className="text-sm">
                {t(base.mayEditLocked && abilities.canTake ? "roll.locked.bodyMayEdit" : "roll.locked.body", {
                  when: formatInstantIn(base.locksAt, base.timeZone),
                })}
              </p>
            </div>
          </div>
          {needsReason && abilities.canTake ? (
            <div className="flex flex-col gap-1.5">
              <label htmlFor="override-reason" className="text-sm font-medium">
                {t("roll.locked.reason")}
              </label>
              <Textarea
                id="override-reason"
                rows={2}
                maxLength={200}
                value={reason}
                onChange={(e) => {
                  setReason(e.target.value);
                  setReasonMissing(false);
                }}
                aria-invalid={reasonMissing || undefined}
                data-testid="override-reason"
              />
              <p className="text-sm text-muted-foreground">{t("roll.locked.reasonHint")}</p>
              {reasonMissing ? (
                <p role="alert" className="text-sm font-medium text-destructive">
                  {t("roll.locked.reasonRequired")}
                </p>
              ) : null}
            </div>
          ) : null}
        </section>
      ) : base.sessionId ? (
        <p className="text-sm text-muted-foreground">{t("roll.opensUntil", { when: formatInstantIn(base.locksAt, base.timeZone) })}</p>
      ) : null}

      <ConflictPanel students={base.students} conflicts={conflicts} pending={saving} disabled={readOnly} onKeepTheirs={resolveTheirs} onUseMine={resolveMine} />

      <section aria-label={t("roll.summaryLabel")} className="flex flex-wrap items-center gap-2" data-testid="roll-summary">
        {STATUSES.map((status) => {
          const ui = STATUS_UI[status];
          const Icon = ui.icon;
          const count = counts[status === "LEFT_EARLY" ? "leftEarly" : (status.toLowerCase() as "present" | "absent" | "late" | "excused")];
          return (
            <span key={status} className={cn("inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-sm font-semibold", ui.tile)} data-testid={`count-${status}`}>
              <Icon className={cn("size-4", ui.icon_)} aria-hidden />
              {t(`status.${status}`)}
              <span className="tabular-nums">{count}</span>
            </span>
          );
        })}
        {counts.unsaved > 0 ? (
          <span className="text-sm text-muted-foreground" data-testid="unsaved-count">
            {t("roll.unsaved", { count: counts.unsaved })}
          </span>
        ) : (
          <span className="text-sm text-muted-foreground">{t("roll.allSaved")}</span>
        )}
      </section>

      {students.length === 0 ? (
        <EmptyNotice>{t("roll.noStudents")}</EmptyNotice>
      ) : (
        <ul className="flex flex-col gap-2" aria-label={t("roll.listLabel")}>
          {students.map((student) => (
            <RollRow
              key={student.studentId}
              student={student}
              mark={shownMark(student, drafts)}
              unsaved={isUnsaved(student, drafts)}
              disabled={rowsDisabled}
              expanded={expanded === student.studentId}
              conflicted={conflictIds.has(student.studentId)}
              onToggle={() => setExpanded((open) => (open === student.studentId ? null : student.studentId))}
              onCycle={(backwards) => {
                const current = shownMark(student, drafts).status;
                const next = backwards ? previousStatus(current) : nextStatus(current);
                setDrafts((d) => setStatus(d, student, next));
                announce(student.displayName, next);
              }}
              onStatus={(status) => {
                setDrafts((d) => setStatus(d, student, status));
                announce(student.displayName, status);
              }}
              onMinutes={(minutes) => setDrafts((d) => setMinutes(d, student, minutes))}
              onNote={(note) => setDrafts((d) => setNote(d, student, note))}
            />
          ))}
        </ul>
      )}

      <p className="sr-only" role="status" aria-live="polite">
        {announcement}
      </p>

      {saveError ? (
        <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="save-error">
          {saveError}
        </p>
      ) : null}

      {!readOnly ? (
        <div className="sticky bottom-3 z-10 flex flex-wrap items-center gap-2 rounded-2xl border bg-card/95 p-3 shadow-lg backdrop-blur" data-testid="roll-actions">
          <p className="me-auto text-sm font-medium" role="status">
            {edits > 0 ? t("roll.pending", { count: edits }) : unsaved > 0 ? t("roll.unsaved", { count: unsaved }) : t("roll.allSaved")}
          </p>
          <Button
            variant={edits > 0 ? "outline" : "default"}
            className="min-h-11"
            onClick={() => saveEdits(true)}
            disabled={busy || unsaved === 0}
            title={t("roll.markAllHelp")}
            data-testid="mark-all-present"
          >
            {saving ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : <CheckCheckIcon className="size-4" aria-hidden />}
            {t("roll.markAllPresent")}
          </Button>
          <Button className="min-h-11" onClick={() => saveEdits(false)} disabled={busy || edits === 0} data-testid="save-roll">
            {saving ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : <SaveIcon className="size-4" aria-hidden />}
            {saving ? t("roll.saving") : t("roll.saveChanges")}
          </Button>
        </div>
      ) : null}

      {discard ? (
        <ConfirmDialog
          title={t("roll.discardTitle")}
          description={t("roll.discardBody")}
          confirmLabel={t("roll.discardConfirm")}
          onConfirm={() => {
            const action = discard;
            setDiscard(null);
            action();
          }}
          onClose={() => setDiscard(null)}
        />
      ) : null}
    </div>
  );
}
