"use client";

import { keepPreviousData, useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { CheckCheckIcon, ChevronLeftIcon, ChevronRightIcon, HistoryIcon, Loader2Icon, PencilIcon, SaveIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { useFormatDate } from "@/components/calendar-preference";
import { useSubmit } from "@/components/console/use-submit";
import { EmptyNotice, ErrorNotice, LoadingRows, LoadMore } from "@/components/console/states";
import { useSchool } from "@/components/school/school-context";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { attendancePageAccess } from "@/lib/attendance/access";
import { addDays, isIsoDate, localDateIn } from "@/lib/attendance/dates";
import { ak, fetchStaffDay, fetchStaffRecord, markStaffDay } from "@/lib/attendance/queries";
import {
  MAX_STAFF_NOTE,
  STAFF_DAYS_BACK,
  STAFF_STATUSES,
  STATUS_COUNT_KEY,
  buildDayRequest,
  canMark,
  clearStaffDraft,
  dayCounts,
  draftCount,
  setStaffMinutes,
  setStaffNote,
  setStaffStatus,
  staffFailure,
  staffMarkValid,
  staffTakesMinutes,
  type StaffDayEntry,
  type StaffDrafts,
  type StaffFailure,
  type StaffRecord,
  type StaffStatus,
} from "@/lib/attendance/staff";
import { parseMinutes, minutesTextValid } from "@/lib/attendance/status";
import { errorMessage } from "@/lib/console/errors";
import { cn } from "@/lib/utils";
import { StaffCorrectionDialog } from "./staff-correction-dialog";
import { StaffReports } from "./staff-reports";
import { STAFF_STATUS_UI, StaffStatusPill, useStaffTypeLabel } from "./staff-status";
import { useFormatInstantIn } from "./status";

/** Staff data is private: kept in memory for a short time only (never persisted, never in a URL). */
export const STAFF_GC_MS = 5 * 60_000;

/**
 * The Staff tab (M7d): a manager marks a school day for staff, sees each person's record, corrects it with a reason
 * and reads the append-only history; readers only look. Everything follows the API: a school with the setting off,
 * a plan without attendance or a caller who may not read gets an explanation, never data. There is no teacher view.
 */
export function StaffAttendanceTab() {
  const t = useTranslations("school.attendance.staff");
  const { permissions, isTeacher } = useSchool();
  const access = attendancePageAccess(permissions, isTeacher);
  if (!access.canSeeStaff) return <EmptyNotice>{t("notForYou")}</EmptyNotice>;
  return (
    <div className="flex flex-col gap-8">
      <StaffDay canManage={access.canManageStaff} />
      <StaffReports />
    </div>
  );
}

function StaffDay({ canManage }: { canManage: boolean }) {
  const t = useTranslations("school.attendance.staff");
  const { school } = useSchool();
  const formatDate = useFormatDate();
  const [chosen, setChosen] = useState<string | null>(null);
  const [drafts, setDrafts] = useState<StaffDrafts>({});
  const [correcting, setCorrecting] = useState<StaffRecord | null>(null);
  const [historyOf, setHistoryOf] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);

  const date = chosen ?? localDateIn(school.timezone, new Date());
  const day = useInfiniteQuery({
    queryKey: ak.staffDay(school.id, date),
    queryFn: ({ pageParam }) => fetchStaffDay(school.id, date, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.staff.nextCursor ?? undefined,
    placeholderData: keepPreviousData,
    gcTime: STAFF_GC_MS,
    refetchOnWindowFocus: false,
    retry: false,
  });

  const save = useSubmit({
    mutationFn: (request: NonNullable<ReturnType<typeof buildDayRequest>>) => markStaffDay(school.id, date, request),
    invalidate: [ak.allStaffDays(school.id)],
    errorFallback: t("saveFailed"),
    toastOnError: false,
    onSuccess: (result) => {
      setDrafts({});
      setSaveError(null);
      toast.success(t("saved", { created: result.created ?? 0, existing: result.existing ?? 0 }));
    },
  });

  if (day.isPending) return <LoadingRows rows={5} />;
  if (day.isError) return <StaffNotice failure={staffFailure(day.error)} message={errorMessage(day.error, t("loadFailed"))} onRetry={() => void day.refetch()} />;

  const first = day.data.pages[0]!;
  const zone = first.timeZone;
  const today = localDateIn(zone, new Date());
  const earliest = addDays(today, -STAFF_DAYS_BACK);
  const switching = day.isFetching && day.isPlaceholderData;
  const entries = day.data.pages.flatMap((p) => p.staff.items);
  const counts = dayCounts(entries);
  const pending = draftCount(drafts);
  const allValid = Object.values(drafts).every(staffMarkValid);
  const busy = save.isPending || switching;

  function pick(next: string | null) {
    if (next !== null && (!isIsoDate(next) || next > today || next < earliest)) return;
    setDrafts({});
    setSaveError(null);
    setHistoryOf(null);
    setChosen(next === today ? null : next);
  }

  function send(markRest: boolean) {
    const request = buildDayRequest(entries, drafts, markRest);
    if (!request) return;
    setSaveError(null);
    save.mutate(request, { onError: (error) => setSaveError(errorMessage(error, t("saveFailed"))) });
  }

  return (
    <section className="flex flex-col gap-4" aria-busy={busy} data-testid="staff-day">
      <div className="flex flex-wrap items-end gap-3">
        <div className="flex items-end gap-1">
          <Button variant="outline" size="icon" className="size-11" aria-label={t("date.prev")} disabled={busy || date <= earliest} onClick={() => pick(addDays(date, -1))} data-testid="staff-prev-day">
            <ChevronLeftIcon className="size-5 rtl:rotate-180" aria-hidden />
          </Button>
          <label className="flex flex-col gap-1.5 text-sm font-medium">
            {t("date.label")}
            <Input
              type="date"
              value={first.date}
              min={earliest}
              max={today}
              disabled={busy}
              onChange={(e) => e.target.value && pick(e.target.value)}
              className="h-11 w-44"
              data-testid="staff-date"
            />
          </label>
          <Button variant="outline" size="icon" className="size-11" aria-label={t("date.next")} disabled={busy || date >= today} onClick={() => pick(addDays(date, 1))} data-testid="staff-next-day">
            <ChevronRightIcon className="size-5 rtl:rotate-180" aria-hidden />
          </Button>
        </div>
        <Button variant={date >= today ? "secondary" : "outline"} className="h-11" disabled={busy || date >= today} onClick={() => pick(null)}>
          {t("date.today")}
        </Button>
        <p className="basis-full text-sm text-muted-foreground">
          {formatDate(first.date)} · {t("date.zone", { zone })}
        </p>
      </div>

      <p className="text-sm text-muted-foreground">{canManage ? t("hint") : t("readOnly")}</p>

      <ul className="flex flex-wrap gap-2" aria-label={t("summaryLabel")} data-testid="staff-summary">
        <Chip label={t("count.staff")} value={counts.staff} />
        {STAFF_STATUSES.map((s) => (
          <Chip key={s} label={t(`status.${s}`)} value={counts[STATUS_COUNT_KEY[s]]} />
        ))}
        <Chip label={t("count.unmarked")} value={counts.unmarked} />
      </ul>

      {entries.length === 0 ? (
        <EmptyNotice>{t("none")}</EmptyNotice>
      ) : (
        <ul className="flex flex-col gap-2" aria-label={t("listLabel")}>
          {entries.map((entry) => (
            <StaffRow
              key={entry.staffId}
              entry={entry}
              draft={drafts[entry.staffId]}
              canManage={canManage}
              disabled={busy}
              timeZone={zone}
              historyOpen={historyOf === entry.record?.id}
              onHistory={() => setHistoryOf((open) => (open === entry.record?.id ? null : (entry.record?.id ?? null)))}
              onCorrect={() => entry.record && setCorrecting(entry.record)}
              onStatus={(status) => setDrafts((d) => setStaffStatus(d, entry.staffId, status))}
              onMinutes={(minutes) => setDrafts((d) => setStaffMinutes(d, entry.staffId, minutes))}
              onNote={(note) => setDrafts((d) => setStaffNote(d, entry.staffId, note))}
              onClear={() => setDrafts((d) => clearStaffDraft(d, entry.staffId))}
            />
          ))}
        </ul>
      )}
      <LoadMore hasMore={!!day.hasNextPage} loading={day.isFetchingNextPage} onClick={() => void day.fetchNextPage()} />

      {saveError ? (
        <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="staff-save-error">
          {saveError}
        </p>
      ) : null}

      {canManage ? (
        <div className="sticky bottom-3 z-10 flex flex-wrap items-center gap-2 rounded-2xl border bg-card/95 p-3 shadow-lg backdrop-blur" data-testid="staff-actions">
          <p className="me-auto text-sm font-medium" role="status">
            {pending > 0 ? t("pending", { count: pending }) : counts.unmarked > 0 ? t("unmarked", { count: counts.unmarked }) : t("allRecorded")}
          </p>
          <Button variant="outline" className="min-h-11" onClick={() => send(true)} disabled={busy || counts.unmarked === 0 || !allValid} title={t("markRestHelp")} data-testid="staff-mark-rest">
            {save.isPending ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : <CheckCheckIcon className="size-4" aria-hidden />}
            {t("markRest")}
          </Button>
          <Button className="min-h-11" onClick={() => send(false)} disabled={busy || pending === 0 || !allValid} data-testid="staff-save">
            {save.isPending ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : <SaveIcon className="size-4" aria-hidden />}
            {save.isPending ? t("saving") : t("save")}
          </Button>
        </div>
      ) : null}

      {correcting ? <StaffCorrectionDialog record={correcting} onClose={() => setCorrecting(null)} /> : null}
    </section>
  );
}

function Chip({ label, value }: { label: string; value: number }) {
  return (
    <li className="inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-sm font-semibold">
      {label}
      <span className="tabular-nums">{value}</span>
    </li>
  );
}

/** What the API's refusal means for the manager. Nothing but the explanation is shown: no data came with it. */
function StaffNotice({ failure, message, onRetry }: { failure: StaffFailure; message: string; onRetry: () => void }) {
  const t = useTranslations("school.attendance.staff");
  if (failure === "other") return <ErrorNotice message={message} onRetry={onRetry} />;
  return (
    <section role="status" className="flex flex-col gap-2 rounded-2xl border border-dashed p-8 text-center" data-testid={`staff-${failure}`}>
      <h2 className="text-lg font-bold">{t(`${failure}.title`)}</h2>
      <p className="text-muted-foreground">{t(`${failure}.body`)}</p>
    </section>
  );
}

function StaffRow({
  entry,
  draft,
  canManage,
  disabled,
  timeZone,
  historyOpen,
  onHistory,
  onCorrect,
  onStatus,
  onMinutes,
  onNote,
  onClear,
}: {
  entry: StaffDayEntry;
  draft: StaffDrafts[string] | undefined;
  canManage: boolean;
  disabled: boolean;
  timeZone: string;
  historyOpen: boolean;
  onHistory: () => void;
  onCorrect: () => void;
  onStatus: (status: StaffStatus) => void;
  onMinutes: (minutes: number | undefined) => void;
  onNote: (note: string) => void;
  onClear: () => void;
}) {
  const t = useTranslations("school.attendance.staff");
  const typeLabel = useStaffTypeLabel();
  const record = entry.record;
  const marking = canManage && canMark(entry);
  return (
    <li className={cn("rounded-2xl border bg-card p-3", draft && "border-primary/60")} data-testid={`staff-row-${entry.staffId}`}>
      <div className="flex flex-wrap items-center gap-3">
        <div className="min-w-0 me-auto">
          <p className="truncate font-semibold">{entry.displayName}</p>
          <p className="truncate text-sm text-muted-foreground">{[typeLabel(entry.staffType), entry.jobTitle].filter(Boolean).join(" · ")}</p>
        </div>
        {record ? (
          <>
            <StaffStatusPill status={record.status} minutesLate={record.minutesLate} />
            <Button variant="ghost" size="sm" className="min-h-11" onClick={onHistory} aria-expanded={historyOpen} data-testid={`staff-history-${entry.staffId}`}>
              <HistoryIcon className="size-4" aria-hidden />
              {t("history.button")}
            </Button>
            {canManage ? (
              <Button variant="outline" size="sm" className="min-h-11" onClick={onCorrect} disabled={disabled} data-testid={`staff-correct-${entry.staffId}`}>
                <PencilIcon className="size-4" aria-hidden />
                {t("correct.button")}
              </Button>
            ) : null}
          </>
        ) : !canManage ? (
          <span className="text-sm text-muted-foreground">{t("notRecorded")}</span>
        ) : null}
      </div>
      {record?.note ? <p className="mt-2 text-sm text-muted-foreground">{record.note}</p> : null}
      {record && (record.corrections ?? 0) > 0 ? <p className="mt-1 text-xs text-muted-foreground">{t("history.corrected", { count: record.corrections ?? 0 })}</p> : null}

      {marking ? (
        <div className="mt-3 flex flex-col gap-3 border-t pt-3">
          <div role="radiogroup" aria-label={t("pickStatus", { name: entry.displayName })} className="flex flex-wrap gap-2">
            {STAFF_STATUSES.map((status) => {
              const ui = STAFF_STATUS_UI[status];
              const Icon = ui.icon;
              const selected = draft?.status === status;
              return (
                <button
                  key={status}
                  type="button"
                  role="radio"
                  aria-checked={selected}
                  disabled={disabled}
                  onClick={() => (selected ? onClear() : onStatus(status))}
                  className={cn(
                    "inline-flex min-h-11 items-center gap-1.5 rounded-xl border-2 px-3 text-sm font-semibold outline-none focus-visible:ring-[3px] focus-visible:ring-ring/60 disabled:opacity-60",
                    selected ? ui.tile : "border-transparent bg-muted/60 hover:bg-accent",
                  )}
                  data-testid={`staff-${status}-${entry.staffId}`}
                >
                  <Icon className={cn("size-4", ui.iconClass)} aria-hidden />
                  {t(`status.${status}`)}
                </button>
              );
            })}
          </div>
          {draft && staffTakesMinutes(draft.status) ? <MinutesInput key={`${entry.staffId}:${draft.minutesLate ?? ""}`} value={draft.minutesLate} disabled={disabled} onChange={onMinutes} /> : null}
          {draft ? <NoteInput value={draft.note ?? ""} disabled={disabled} onChange={onNote} /> : null}
        </div>
      ) : null}

      {historyOpen && record ? <StaffHistory record={record} timeZone={timeZone} /> : null}
    </li>
  );
}

function MinutesInput({ value, disabled, onChange }: { value: number | undefined; disabled: boolean; onChange: (minutes: number | undefined) => void }) {
  const t = useTranslations("school.attendance.staff");
  const [text, setText] = useState(value === undefined ? "" : String(value));
  const valid = minutesTextValid(text);
  return (
    <label className="flex flex-col gap-1.5 text-sm font-medium">
      {t("minutesLate")}
      <Input
        type="text"
        inputMode="numeric"
        autoComplete="off"
        value={text}
        disabled={disabled}
        aria-invalid={!valid || undefined}
        onChange={(e) => {
          setText(e.target.value);
          if (minutesTextValid(e.target.value)) onChange(parseMinutes(e.target.value));
        }}
        className="h-11 max-w-32"
        data-testid="staff-minutes"
      />
      {valid ? null : (
        <span role="alert" className="text-sm font-medium text-destructive">
          {t("minutesInvalid")}
        </span>
      )}
    </label>
  );
}

function NoteInput({ value, disabled, onChange }: { value: string; disabled: boolean; onChange: (note: string) => void }) {
  const t = useTranslations("school.attendance.staff");
  const [text, setText] = useState(value);
  return (
    <label className="flex flex-col gap-1.5 text-sm font-medium">
      {t("note")}
      <Textarea
        rows={2}
        maxLength={MAX_STAFF_NOTE}
        value={text}
        disabled={disabled}
        placeholder={t("notePlaceholder")}
        onChange={(e) => {
          setText(e.target.value);
          onChange(e.target.value);
        }}
        data-testid="staff-note"
      />
    </label>
  );
}

/** The record's append-only history, as the API returns it (`GET /records/{id}`): oldest first, nothing edited here. */
function StaffHistory({ record, timeZone }: { record: StaffRecord; timeZone: string }) {
  const t = useTranslations("school.attendance.staff.history");
  const tStatus = useTranslations("school.attendance.staff.status");
  const { school } = useSchool();
  const formatInstantIn = useFormatInstantIn();
  const detail = useQuery({
    queryKey: ak.staffRecord(school.id, record.id),
    queryFn: () => fetchStaffRecord(school.id, record.id),
    gcTime: STAFF_GC_MS,
    retry: false,
  });
  return (
    <div className="mt-3 flex flex-col gap-2 border-t pt-3 text-sm" data-testid={`staff-history-panel-${record.staffId}`}>
      <p className="text-muted-foreground">{t("recorded", { when: formatInstantIn(record.recordedAt, timeZone) })}</p>
      {detail.isPending ? (
        <LoadingRows rows={1} />
      ) : detail.isError ? (
        <ErrorNotice message={errorMessage(detail.error, t("loadFailed"))} onRetry={() => void detail.refetch()} />
      ) : detail.data.corrections.length === 0 ? (
        <p>{t("none")}</p>
      ) : (
        <ol className="flex flex-col gap-2">
          {detail.data.corrections.map((c) => (
            <li key={c.id} className="rounded-xl bg-muted/50 p-3">
              <p className="font-medium">
                {t("change", { from: tStatus(c.oldStatus), to: tStatus(c.newStatus) })}
                {c.newMinutesLate != null ? ` · ${t("minutes", { minutes: c.newMinutesLate })}` : ""}
              </p>
              <p>{c.reason}</p>
              <p className="text-xs text-muted-foreground">{formatInstantIn(c.correctedAt, timeZone)}</p>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
