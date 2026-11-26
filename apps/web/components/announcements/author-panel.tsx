"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { BarChart3Icon, CalendarClockIcon, Loader2Icon, PencilIcon, SendIcon, Trash2Icon, UndoIcon, UsersIcon, XCircleIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { useFormatInstantIn } from "@/components/attendance/status";
import { ConfirmDialog } from "@/components/school/confirm-dialog";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";
import { Link, useRouter } from "@/i18n/navigation";
import {
  ank,
  deleteAnnouncement,
  fetchPreview,
  unscheduleAnnouncement,
  withdrawAnnouncement,
  type Announcement,
} from "@/lib/announcements/queries";
import { checkSchedule, scheduleBounds } from "@/lib/announcements/schedule";
import { NOTE_MAX } from "@/lib/announcements/view";
import { errorMessage } from "@/lib/console/errors";
import { PublishDialog, type PublishMode } from "./publish-dialog";
import { useAnnouncementCapabilities, useRefreshAnnouncements } from "./use-announcements";

type Audience = NonNullable<Announcement["audiences"]>[number];

/** "2 classes · Parents and guardians": what the author chose, kind by kind (names of chosen people are never shown). */
function audienceLines(audiences: readonly Audience[]) {
  const groups = new Map<string, { type: Audience["type"]; count: number; roles: Set<string> }>();
  for (const a of audiences) {
    const key = a.type;
    const group = groups.get(key) ?? { type: a.type, count: 0, roles: new Set<string>() };
    group.count += 1;
    for (const role of a.roles) group.roles.add(role);
    groups.set(key, group);
  }
  return [...groups.values()];
}

/**
 * What the author and managers can do with an announcement, from the API's own `can` flags: the audience and channels
 * it has, a head count, and edit, publish, schedule, withdraw, delete and the report. Everything else is only shown
 * to readers.
 */
export function AuthorPanel({
  announcement: a,
  schoolId,
  workspaceSlug,
  inWorkspace,
  backHref,
  onChanged,
}: {
  announcement: Announcement;
  schoolId: string;
  workspaceSlug: string | null;
  inWorkspace: boolean;
  backHref: string;
  onChanged: (announcement: Announcement) => void;
}) {
  const t = useTranslations("school.announcements");
  const router = useRouter();
  const queryClient = useQueryClient();
  const refresh = useRefreshAnnouncements();
  const formatIn = useFormatInstantIn();
  const caps = useAnnouncementCapabilities(schoolId, a.can.schedule || a.can.publish);
  const zone = caps.data?.timeZone ?? "UTC";
  const [publishing, setPublishing] = useState<PublishMode | null>(null);
  const [scheduling, setScheduling] = useState(false);
  const [scheduledLocal, setScheduledLocal] = useState("");
  const [withdrawing, setWithdrawing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [showPreview, setShowPreview] = useState(false);
  const base = workspaceSlug ? `/s/${encodeURIComponent(workspaceSlug)}/announcements/${a.id}` : null;
  const draftLike = a.status === "DRAFT" || a.status === "SCHEDULED";
  const lines = audienceLines(a.audiences ?? []);

  const preview = useQuery({
    queryKey: ank.preview(schoolId, a.id),
    queryFn: () => fetchPreview(schoolId, a.id),
    enabled: showPreview && draftLike,
    staleTime: 0,
    retry: false,
  });
  const unschedule = useMutation({
    mutationFn: () => unscheduleAnnouncement(schoolId, a.id),
    onSuccess: async (next) => {
      toast.success(t("actions.unscheduled"));
      queryClient.setQueryData(ank.one(schoolId, a.id), next);
      await refresh();
      onChanged(next);
    },
    onError: (error) => toast.error(errorMessage(error, t("actions.failed"))),
  });

  const bounds = scheduleBounds(zone, new Date());
  const pick = scheduling ? checkSchedule(scheduledLocal, zone, new Date()) : null;
  const canAny = a.can.edit || a.can.publish || a.can.schedule || a.can.withdraw || a.can.delete || a.can.viewReport;
  if (!canAny && lines.length === 0) return null;

  return (
    <section className="flex flex-col gap-4 rounded-2xl border bg-card p-4" aria-label={t("detail.audience")} data-testid="author-panel">
      {lines.length > 0 ? (
        <dl className="grid gap-3 text-sm sm:grid-cols-2">
          <div className="flex flex-col gap-1">
            <dt className="font-semibold text-muted-foreground">{t("detail.audience")}</dt>
            <dd className="flex flex-col gap-0.5">
              {lines.map((line) => (
                <span key={line.type}>
                  {t(`composer.audience.summary.${line.type}`, { count: line.count })}
                  {" · "}
                  {[...line.roles].map((role) => t(`detail.roleNames.${role as "GUARDIAN" | "STUDENT" | "STAFF"}`)).join(", ")}
                </span>
              ))}
            </dd>
          </div>
          <div className="flex flex-col gap-1">
            <dt className="font-semibold text-muted-foreground">{t("detail.channels")}</dt>
            <dd>{(a.channels ?? []).map((c) => t(`detail.channelNames.${c}`)).join(", ")}</dd>
            {typeof a.recipientCount === "number" && a.status !== "DRAFT" ? <dd className="font-medium">{t("detail.recipientCount", { count: a.recipientCount })}</dd> : null}
            {a.requiresAcknowledgement ? <dd className="text-muted-foreground">{t("detail.asksAck")}</dd> : null}
          </div>
        </dl>
      ) : null}

      {a.scheduleErrorCode && a.status === "DRAFT" ? (
        <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="schedule-failed">
          {t("detail.scheduleFailed")}
        </p>
      ) : null}
      {a.status === "SCHEDULED" && a.scheduledFor ? (
        <p className="flex items-center gap-2 text-sm font-semibold" data-testid="scheduled-for">
          <CalendarClockIcon className="size-4" aria-hidden />
          {t("detail.scheduledFor", { when: formatIn(a.scheduledFor, zone) })}
        </p>
      ) : null}

      {showPreview && draftLike ? (
        <p className="rounded-xl bg-muted p-3 text-sm" role="status" data-testid="preview-result">
          {preview.isPending
            ? t("publish.counting")
            : preview.isError
              ? errorMessage(preview.error, t("detail.previewFailed"))
              : t("detail.previewResult", { recipients: preview.data.recipients, guardians: preview.data.guardians, students: preview.data.students, staff: preview.data.staff })}
        </p>
      ) : null}

      <div className="flex flex-wrap gap-2">
        {a.can.edit && base ? (
          <Button asChild variant="outline">
            <Link href={`${base}/edit`} data-testid="action-edit">
              <PencilIcon aria-hidden />
              {t("actions.edit")}
            </Link>
          </Button>
        ) : null}
        {draftLike && a.can.edit ? (
          <Button type="button" variant="outline" onClick={() => setShowPreview(true)} data-testid="action-preview">
            <UsersIcon aria-hidden />
            {t("detail.preview")}
          </Button>
        ) : null}
        {a.status === "DRAFT" && a.can.publish ? (
          <Button type="button" onClick={() => setPublishing({ kind: "publish" })} data-testid="action-publish">
            <SendIcon aria-hidden />
            {t("actions.publish")}
          </Button>
        ) : null}
        {a.status === "DRAFT" && a.can.schedule ? (
          <Button
            type="button"
            variant="outline"
            onClick={() => setScheduling(!scheduling)}
            aria-expanded={scheduling}
            disabled={!caps.data}
            data-testid="action-schedule"
          >
            <CalendarClockIcon aria-hidden />
            {t("actions.schedule")}
          </Button>
        ) : null}
        {a.status === "SCHEDULED" && a.can.schedule ? (
          <Button type="button" variant="outline" onClick={() => unschedule.mutate()} disabled={unschedule.isPending} data-testid="action-unschedule">
            {unschedule.isPending ? <Loader2Icon className="animate-spin" aria-hidden /> : <UndoIcon aria-hidden />}
            {t("actions.unschedule")}
          </Button>
        ) : null}
        {a.can.viewReport && (a.status === "PUBLISHED" || a.status === "WITHDRAWN") && base ? (
          <Button asChild variant="outline">
            <Link href={`${base}/report`} data-testid="action-report">
              <BarChart3Icon aria-hidden />
              {t("actions.report")}
            </Link>
          </Button>
        ) : null}
        {a.can.withdraw && a.status === "PUBLISHED" ? (
          <Button type="button" variant="outline" onClick={() => setWithdrawing(true)} data-testid="action-withdraw">
            <XCircleIcon aria-hidden />
            {t("actions.withdraw")}
          </Button>
        ) : null}
        {a.can.delete && draftLike ? (
          <Button type="button" variant="destructive" onClick={() => setDeleting(true)} data-testid="action-delete">
            <Trash2Icon aria-hidden />
            {t("actions.delete")}
          </Button>
        ) : null}
        {!inWorkspace && workspaceSlug && canAny ? (
          <Button asChild variant="ghost">
            <Link href={`/s/${encodeURIComponent(workspaceSlug)}/announcements/${a.id}`} data-testid="action-manage">
              {t("detail.manage")}
            </Link>
          </Button>
        ) : null}
      </div>

      {scheduling && a.status === "DRAFT" ? (
        <div className="flex flex-col gap-2 rounded-xl bg-muted/50 p-3">
          <label htmlFor="schedule-at" className="text-sm font-medium">
            {t("actions.scheduleAt", { zone })}
          </label>
          <input
            id="schedule-at"
            type="datetime-local"
            className="h-11 w-full max-w-xs rounded-md border border-input bg-background px-3"
            value={scheduledLocal}
            min={bounds?.min}
            max={bounds?.max}
            onChange={(event) => setScheduledLocal(event.target.value)}
            data-testid="schedule-at"
          />
          {pick?.ok ? <p className="text-sm">{t("composer.when.willSend", { when: formatIn(pick.instant, zone) })}</p> : scheduledLocal ? <p className="text-sm text-destructive">{t(`composer.problems.${pick && !pick.ok ? (pick.reason === "invalid" ? "scheduleInvalid" : pick.reason === "past" ? "schedulePast" : "scheduleTooFar") : "scheduleInvalid"}`)}</p> : null}
          <div>
            <Button
              type="button"
              disabled={!pick?.ok}
              onClick={() => pick?.ok && setPublishing({ kind: "schedule", instant: pick.instant, whenLabel: formatIn(pick.instant, zone) })}
              data-testid="schedule-review"
            >
              {t("composer.submit.schedule")}
            </Button>
          </div>
        </div>
      ) : null}

      {publishing ? (
        <PublishDialog
          schoolId={schoolId}
          announcementId={a.id}
          type={a.type}
          mode={publishing}
          onClose={() => setPublishing(null)}
          onDone={async (next) => {
            setPublishing(null);
            setScheduling(false);
            queryClient.setQueryData(ank.one(schoolId, a.id), next);
            await refresh();
            onChanged(next);
          }}
        />
      ) : null}
      {withdrawing ? (
        <WithdrawDialog
          schoolId={schoolId}
          announcementId={a.id}
          onClose={() => setWithdrawing(false)}
          onDone={async (next) => {
            setWithdrawing(false);
            queryClient.setQueryData(ank.one(schoolId, a.id), next);
            await refresh();
            onChanged(next);
          }}
        />
      ) : null}
      {deleting ? (
        <DeleteDraft
          schoolId={schoolId}
          announcementId={a.id}
          onClose={() => setDeleting(false)}
          onDone={async () => {
            setDeleting(false);
            await refresh();
            router.push(backHref);
          }}
        />
      ) : null}
    </section>
  );
}

function WithdrawDialog({ schoolId, announcementId, onClose, onDone }: { schoolId: string; announcementId: string; onClose: () => void; onDone: (next: Announcement) => void }) {
  const t = useTranslations("school.announcements");
  const tc = useTranslations("console");
  const [reason, setReason] = useState("");
  const withdraw = useMutation({
    mutationFn: () => withdrawAnnouncement(schoolId, announcementId, reason),
    onSuccess: (next) => {
      toast.success(t("withdrawDialog.done"));
      onDone(next);
    },
  });
  return (
    <Dialog open onOpenChange={(open) => !open && !withdraw.isPending && onClose()}>
      <DialogContent data-testid="withdraw-dialog" className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{t("withdrawDialog.title")}</DialogTitle>
          <DialogDescription>{t("withdrawDialog.description")}</DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="withdraw-reason" className="text-sm font-medium">
            {t("withdrawDialog.reason")}
          </label>
          <Textarea
            id="withdraw-reason"
            rows={3}
            maxLength={NOTE_MAX}
            value={reason}
            placeholder={t("withdrawDialog.reasonPlaceholder")}
            onChange={(event) => setReason(event.target.value)}
            data-testid="withdraw-reason"
          />
        </div>
        {withdraw.isError ? (
          <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
            {errorMessage(withdraw.error, t("actions.failed"))}
          </p>
        ) : null}
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose} disabled={withdraw.isPending}>
            {tc("cancel")}
          </Button>
          <Button type="button" variant="destructive" onClick={() => withdraw.mutate()} disabled={withdraw.isPending} data-testid="withdraw-confirm">
            {withdraw.isPending ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : null}
            {t("withdrawDialog.confirm")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function DeleteDraft({ schoolId, announcementId, onClose, onDone }: { schoolId: string; announcementId: string; onClose: () => void; onDone: () => void }) {
  const t = useTranslations("school.announcements");
  const remove = useMutation({
    mutationFn: () => deleteAnnouncement(schoolId, announcementId),
    onSuccess: () => {
      toast.success(t("deleteDialog.done"));
      onDone();
    },
  });
  return (
    <ConfirmDialog
      title={t("deleteDialog.title")}
      description={t("deleteDialog.description")}
      confirmLabel={t("deleteDialog.confirm")}
      pending={remove.isPending}
      error={remove.isError ? errorMessage(remove.error, t("actions.failed")) : null}
      onConfirm={() => remove.mutate()}
      onClose={onClose}
    />
  );
}
