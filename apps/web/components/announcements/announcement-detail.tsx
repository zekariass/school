"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeftIcon, CheckCircle2Icon, Loader2Icon } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { useEffect, useRef, useState } from "react";
import { languageLabel } from "@school/i18n";
import { useFormatInstant } from "@/components/calendar-preference";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { Button } from "@/components/ui/button";
import { Link } from "@/i18n/navigation";
import { acknowledgeAnnouncement, ank, fetchAnnouncement, markAnnouncementRead, type Announcement } from "@/lib/announcements/queries";
import { ackState, canAcknowledge, defaultLanguage, isNewToViewer, isTombstone, isUrgentType, languagesOf, textIn } from "@/lib/announcements/view";
import { errorMessage } from "@/lib/console/errors";
import { cn } from "@/lib/utils";
import { PlainText } from "./announcement-text";
import { AuthorPanel } from "./author-panel";
import { RevisionsPanel } from "./revisions-panel";
import { TypeBadge } from "./type-badge";
import { useRefreshAnnouncements } from "./use-announcements";

/**
 * One announcement for its reader and, through the API's `can` flags, for its author and managers. Opening it marks it
 * read (not for the author or a manager looking at it); an acknowledgement is asked for when the author wants one,
 * and again after a material edit. A withdrawn announcement is a tombstone without the message. Title and body are
 * rendered strictly as text.
 */
export function AnnouncementDetail({
  schoolId,
  announcementId,
  viewerId,
  backHref,
  backLabel,
  workspaceSlug,
  inWorkspace,
}: {
  schoolId: string;
  announcementId: string;
  viewerId: string;
  backHref: string;
  backLabel: string;
  /** The slug of the school's workspace when the viewer has one (authors and managers), else null. */
  workspaceSlug: string | null;
  inWorkspace: boolean;
}) {
  const t = useTranslations("school.announcements");
  const query = useQuery({
    queryKey: ank.one(schoolId, announcementId),
    queryFn: () => fetchAnnouncement(schoolId, announcementId),
    retry: false,
  });
  const queryClient = useQueryClient();

  return (
    <div className="flex flex-col gap-5">
      <Link href={backHref} className="inline-flex min-h-11 items-center gap-2 self-start text-sm font-semibold text-primary hover:underline">
        <ArrowLeftIcon className="size-4 rtl:rotate-180" aria-hidden />
        {backLabel}
      </Link>
      {query.isPending ? (
        <LoadingRows rows={4} />
      ) : query.isError ? (
        <EmptyNotice>{errorMessage(query.error, t("detail.loadFailed"))}</EmptyNotice>
      ) : (
        <DetailBody
          announcement={query.data}
          schoolId={schoolId}
          viewerId={viewerId}
          backHref={backHref}
          workspaceSlug={workspaceSlug}
          inWorkspace={inWorkspace}
          onChanged={(next) => queryClient.setQueryData(ank.one(schoolId, announcementId), next)}
        />
      )}
    </div>
  );
}

function DetailBody({
  announcement: a,
  schoolId,
  viewerId,
  backHref,
  workspaceSlug,
  inWorkspace,
  onChanged,
}: {
  announcement: Announcement;
  schoolId: string;
  viewerId: string;
  backHref: string;
  workspaceSlug: string | null;
  inWorkspace: boolean;
  onChanged: (a: Announcement) => void;
}) {
  const t = useTranslations("school.announcements");
  const locale = useLocale();
  const format = useFormatInstant();
  const queryClient = useQueryClient();
  const refresh = useRefreshAnnouncements();
  const tombstone = isTombstone(a);
  const urgent = isUrgentType(a.type) && !tombstone;
  const isAuthor = a.authorId === viewerId;
  // The author and managers who open it to review it are not its readers.
  const reviewing = isAuthor || a.can.viewReport || a.can.edit;
  const [language, setLanguage] = useState(() => defaultLanguage(a, locale));
  const shown = textIn(a, language);
  const languages = languagesOf(a);
  const [wasNew] = useState(() => isNewToViewer(a));

  // Opening a published announcement marks it read. Marking it read does not touch its notification: the notification
  // is marked read where it is opened from (the bell and the notifications page), which knows its id.
  const marked = useRef(false);
  useEffect(() => {
    if (marked.current || reviewing || a.status !== "PUBLISHED" || a.mine.read) return;
    marked.current = true;
    markAnnouncementRead(schoolId, a.id)
      .then(() => {
        queryClient.setQueryData<Announcement>(ank.one(schoolId, a.id), (current) =>
          current ? { ...current, mine: { ...current.mine, read: true, readAt: current.mine.readAt ?? new Date().toISOString() } } : current,
        );
        return refresh();
      })
      .catch(() => {
        marked.current = false;
      });
  }, [a.id, a.mine.read, a.status, reviewing, queryClient, refresh, schoolId]);

  return (
    <article className={cn("flex flex-col gap-4 rounded-2xl border bg-card p-5", urgent && "border-destructive border-s-4")} data-testid="announcement-detail" data-status={a.status}>
      <header className="flex flex-col gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <TypeBadge type={a.type} showNormal={false} />
          {wasNew && !tombstone ? <span className="rounded-full bg-primary px-2.5 py-0.5 text-xs font-bold text-primary-foreground">{t("detail.new")}</span> : null}
          {(a.can.edit || a.can.viewReport || isAuthor) ? <span className="rounded-full border px-2.5 py-0.5 text-xs font-semibold">{t(`status.${a.status}`)}</span> : null}
        </div>
        <h1 className="text-2xl leading-tight font-bold [overflow-wrap:anywhere]" dir="auto" data-testid="announcement-title">
          {shown.title}
        </h1>
        <p className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
          <span className="font-medium">{a.schoolName}</span>
          {a.authorName ? <span>{t("detail.by", { name: a.authorName })}</span> : null}
          {a.publishedAt ? <span>{t("detail.publishedAt", { when: format(a.publishedAt) })}</span> : null}
          {a.editedAt && !tombstone ? <span>{t("detail.editedAt", { when: format(a.editedAt) })}</span> : null}
          {a.revision > 1 ? <span>{t("detail.version", { revision: a.revision })}</span> : null}
        </p>
      </header>

      {tombstone ? (
        <div role="status" className="flex flex-col gap-1 rounded-xl border border-dashed bg-muted/50 p-4" data-testid="tombstone">
          <p className="font-bold">{t("detail.withdrawnTitle")}</p>
          {a.withdrawnAt ? <p className="text-sm">{t("detail.withdrawnAt", { when: format(a.withdrawnAt) })}</p> : null}
          <p className="text-sm [overflow-wrap:anywhere]">{a.withdrawalReason ? t("detail.withdrawnReason", { reason: a.withdrawalReason }) : t("detail.withdrawnNoReason")}</p>
          <p className="text-sm text-muted-foreground">{t("detail.withdrawnBody")}</p>
        </div>
      ) : (
        <>
          {languages.length > 1 ? (
            <div role="group" aria-label={t("detail.readIn")} className="flex flex-wrap items-center gap-2">
              <span className="text-sm font-medium text-muted-foreground">{t("detail.readIn")}</span>
              {languages.map((code) => (
                <button
                  key={code}
                  type="button"
                  aria-pressed={shown.language === code}
                  onClick={() => setLanguage(code)}
                  data-testid={`language-${code}`}
                  className={cn("min-h-9 rounded-full border px-3 text-sm font-medium", shown.language === code ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent")}
                >
                  {code === "und" ? "—" : languageLabel(code, locale)}
                  {code === (a.bodyLanguageCode ?? "und") ? " ·" : ""}
                </button>
              ))}
            </div>
          ) : null}
          {shown.body ? <PlainText text={shown.body} className="text-base leading-relaxed" /> : null}
        </>
      )}

      <AckPanel announcement={a} schoolId={schoolId} viewerId={viewerId} onChanged={onChanged} />

      <AuthorPanel announcement={a} schoolId={schoolId} workspaceSlug={workspaceSlug} inWorkspace={inWorkspace} backHref={backHref} onChanged={onChanged} />

      {(a.can.edit || a.can.viewReport) && (a.status === "PUBLISHED" || a.status === "WITHDRAWN") && a.revision > 1 ? (
        <RevisionsPanel schoolId={schoolId} announcementId={a.id} revision={a.revision} />
      ) : null}
    </article>
  );
}

/** "I have read this": asked for by the author, and again after a material edit. Idempotent on the API's side. */
function AckPanel({ announcement: a, schoolId, viewerId, onChanged }: { announcement: Announcement; schoolId: string; viewerId: string; onChanged: (a: Announcement) => void }) {
  const t = useTranslations("school.announcements.ack");
  const format = useFormatInstant();
  const refresh = useRefreshAnnouncements();
  const state = ackState(a, viewerId);
  const ack = useMutation({
    mutationFn: () => acknowledgeAnnouncement(schoolId, a.id),
    onSuccess: async (next) => {
      onChanged(next);
      await refresh();
    },
  });
  if (state === "none") return null;
  if (state === "done") {
    return (
      <p className="flex items-center gap-2 rounded-xl bg-success/15 p-3 text-sm font-semibold" data-testid="ack-done">
        <CheckCircle2Icon className="size-5 shrink-0" aria-hidden />
        {a.mine.acknowledgedAt ? t("done", { when: format(a.mine.acknowledgedAt) }) : t("button")}
      </p>
    );
  }
  return (
    <div className="flex flex-col gap-3 rounded-xl border-2 border-warning bg-warning/10 p-4" data-testid="ack-needed" data-ack-state={state}>
      <p className="font-semibold">{state === "again" ? t("again") : t("needs")}</p>
      {canAcknowledge(a, viewerId) ? (
        <div>
          <Button type="button" onClick={() => ack.mutate()} disabled={ack.isPending} data-testid="ack-button">
            {ack.isPending ? <Loader2Icon className="animate-spin" aria-hidden /> : <CheckCircle2Icon aria-hidden />}
            {ack.isPending ? t("saving") : state === "again" ? t("buttonAgain") : t("button")}
          </Button>
        </div>
      ) : null}
      {ack.isError ? (
        <ErrorNotice message={errorMessage(ack.error, t("failed"))} />
      ) : null}
    </div>
  );
}
