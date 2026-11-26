"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { Loader2Icon, SirenIcon, UsersIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useId, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { ank, fetchPreview, publishAnnouncement, scheduleAnnouncement, type Announcement } from "@/lib/announcements/queries";
import { LARGE_AUDIENCE, isUrgentType, needsPublishConfirmation, type AnnouncementType } from "@/lib/announcements/view";
import { errorMessage } from "@/lib/console/errors";

export type PublishMode = { kind: "publish" } | { kind: "schedule"; instant: string; whenLabel: string };

/**
 * "Ready to send?": counts who would receive the saved announcement (the API's own count, `GET .../preview`), warns
 * about large audiences and urgent types, and then publishes or schedules it. The text is already saved as a draft, so
 * an error (422 no recipients, 429 too many, anything else) leaves it untouched and shows the API's message here.
 */
export function PublishDialog({
  schoolId,
  announcementId,
  type,
  mode,
  onClose,
  onDone,
}: {
  schoolId: string;
  announcementId: string;
  type: AnnouncementType;
  mode: PublishMode;
  onClose: () => void;
  onDone: (announcement: Announcement) => void;
}) {
  const t = useTranslations("school.announcements");
  const checkId = useId();
  const [checked, setChecked] = useState(false);
  const preview = useQuery({
    queryKey: ank.preview(schoolId, announcementId),
    queryFn: () => fetchPreview(schoolId, announcementId),
    staleTime: 0,
    gcTime: 0,
    retry: false,
  });
  const send = useMutation({
    mutationFn: () => (mode.kind === "publish" ? publishAnnouncement(schoolId, announcementId) : scheduleAnnouncement(schoolId, announcementId, mode.instant)),
    onSuccess: (announcement) => {
      toast.success(mode.kind === "publish" ? t("composer.published") : t("composer.scheduledDone"));
      onDone(announcement);
    },
  });

  const recipients = preview.data?.recipients;
  const nobody = preview.isSuccess && recipients === 0;
  const careful = recipients !== undefined && needsPublishConfirmation(recipients, type);
  const ready = preview.isSuccess ? !nobody && (!careful || checked) : preview.isError;

  return (
    <Dialog open onOpenChange={(open) => !open && !send.isPending && onClose()}>
      <DialogContent data-testid="publish-dialog" className="max-h-[92dvh] overflow-y-auto sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{mode.kind === "publish" ? t("publish.title") : t("publish.scheduleTitle")}</DialogTitle>
          <DialogDescription>{t("publish.savedAsDraft")}</DialogDescription>
        </DialogHeader>

        {preview.isPending ? (
          <p className="flex items-center gap-2 text-sm text-muted-foreground" role="status">
            <Loader2Icon className="size-4 animate-spin" aria-hidden />
            {t("publish.counting")}
          </p>
        ) : preview.isError ? (
          <p role="status" className="rounded-xl bg-muted p-3 text-sm">
            {errorMessage(preview.error, t("publish.previewFailed"))}
          </p>
        ) : nobody ? (
          <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="publish-nobody">
            {t("publish.nobody")}
          </p>
        ) : (
          <div className="flex flex-col gap-3">
            <p className="flex items-start gap-2 rounded-xl bg-primary/10 p-3 font-medium" data-testid="publish-summary">
              <UsersIcon className="mt-0.5 size-5 shrink-0" aria-hidden />
              <span>
                {t("publish.summary", {
                  recipients: preview.data.recipients,
                  guardians: preview.data.guardians,
                  students: preview.data.students,
                  staff: preview.data.staff,
                })}
              </span>
            </p>
            {isUrgentType(type) ? (
              <p className="flex items-start gap-2 rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
                <SirenIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
                {t("publish.urgent")}
              </p>
            ) : null}
            {recipients !== undefined && recipients >= LARGE_AUDIENCE ? <p className="rounded-xl bg-warning/20 p-3 text-sm font-medium">{t("publish.large")}</p> : null}
            {careful ? (
              <label htmlFor={checkId} className="flex min-h-11 cursor-pointer items-start gap-3 text-sm font-medium">
                <input id={checkId} type="checkbox" className="mt-0.5 size-5 shrink-0 accent-primary" checked={checked} onChange={(e) => setChecked(e.target.checked)} data-testid="publish-check" />
                <span>{t("publish.checked")}</span>
              </label>
            ) : null}
          </div>
        )}

        {send.isError ? (
          <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="publish-error">
            {errorMessage(send.error, t("actions.failed"))}
          </p>
        ) : null}

        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose} disabled={send.isPending}>
            {t("publish.back")}
          </Button>
          <Button type="button" onClick={() => send.mutate()} disabled={!ready || send.isPending || nobody} data-testid="publish-confirm">
            {send.isPending ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : null}
            {send.isPending ? t("publish.working") : mode.kind === "publish" ? t("publish.confirmNow") : t("publish.confirmSchedule", { when: mode.whenLabel })}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
