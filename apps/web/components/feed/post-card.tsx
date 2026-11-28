"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import {
  ArchiveIcon,
  CalendarClockIcon,
  EyeIcon,
  EyeOffIcon,
  FlagIcon,
  Languages,
  MessageCircleIcon,
  MoreHorizontalIcon,
  PaletteIcon,
  PencilIcon,
  PinIcon,
  PinOffIcon,
  SendIcon,
  Trash2Icon,
  TrophyIcon,
  UndoIcon,
} from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import { useFormatInstantIn } from "@/components/attendance/status";
import { ReportDialog } from "@/components/moderation/report-dialog";
import { ConfirmDialog } from "@/components/school/confirm-dialog";
import { FormDialog } from "@/components/school/form-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/console/errors";
import { patchById } from "@/lib/feed/cache";
import { parseGateError, type GateError } from "@/lib/feed/consent-error";
import { instantToLocal, checkSchedule, scheduleBounds, defaultScheduleLocal } from "@/lib/feed/schedule";
import {
  archivePost,
  deletePost,
  fk,
  hidePost,
  markPostSeen,
  publishPost,
  react,
  schedulePost,
  setPinned,
  translatePost,
  unhidePost,
  unschedulePost,
  type Post,
} from "@/lib/feed/queries";
import { REACTIONS, applyReaction, nextReaction, reactionTotal, type ReactionName } from "@/lib/feed/reactions";
import { postVariant, roleChipTone, splitFirstLine } from "@/lib/feed/post-style";
import { hasAnyAction, hasSeenCount, isWithheldPlaceholder, isWithheldWarning, postMenu } from "@/lib/feed/view";
import { cn } from "@/lib/utils";
import { StudentAvatar } from "@/components/school/student-avatar";
import { AuthorAvatar, AuthorName } from "./author";
import { Comments } from "./comments";
import { GateNotice } from "./gate-notice";
import { MediaGrid, WithheldPlaceholder } from "./media-grid";
import { RelativeTime } from "./relative-time";
import { useCapabilities, useRefreshFeed } from "./use-feed";

export const REACTION_EMOJI: Record<ReactionName, string> = { LIKE: "👍", HEART: "❤️", CLAP: "👏", STAR: "⭐" };

const seenThisSession = new Set<string>();

type Dialog =
  | { kind: "delete" | "hide" | "report" | "reportUser" | "schedule" }
  | { kind: "gate"; error: GateError; retry: (confirm: boolean) => Promise<unknown> };

/**
 * One post: author (a name only when the API sent one), time, text, photos (or a placeholder when the child's
 * consent is not granted), tagged children as the API listed them, reactions, comments and the actions the
 * API's `rights` allow. Nothing here decides what the viewer may see: it shows what the API answered.
 */
export function PostCard({ post, onEdit, defaultCommentsOpen = false }: { post: Post; onEdit?: (post: Post) => void; defaultCommentsOpen?: boolean }) {
  const t = useTranslations("school.feed.post");
  const tr = useTranslations("school.feed.redesign.post");
  const variant = postVariant(post);
  const queryClient = useQueryClient();
  const refresh = useRefreshFeed();
  const [commentsOpen, setCommentsOpen] = useState(defaultCommentsOpen);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const menu = postMenu(post);
  const caps = useCapabilities(post.schoolId, post.classId ?? undefined);
  const translationEnabled = !!caps.data?.translationEnabled;
  const ref = useRef<HTMLElement>(null);

  // Count one view per post and session once at least half of the card has been on screen for a second.
  useEffect(() => {
    const node = ref.current;
    if (!node || post.status !== "PUBLISHED" || seenThisSession.has(post.id) || typeof IntersectionObserver === "undefined") return;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry?.isIntersecting) {
          timer = setTimeout(() => {
            seenThisSession.add(post.id);
            observer.disconnect();
            void markPostSeen(post.schoolId, post.id).catch(() => seenThisSession.delete(post.id));
          }, 1000);
        } else if (timer) clearTimeout(timer);
      },
      { threshold: 0.5 },
    );
    observer.observe(node);
    return () => {
      observer.disconnect();
      if (timer) clearTimeout(timer);
    };
  }, [post.id, post.schoolId, post.status]);

  // An action that may hit the consent gate: on 422 the dialog explains it and offers to retry.
  const act = useMutation({
    mutationFn: async ({ run }: { run: (confirm: boolean) => Promise<unknown>; success?: string; }) => {
      await run(false);
    },
    onSuccess: async (_data, vars) => {
      if (vars.success) toast.success(vars.success);
      await refresh();
    },
    onError: (error, vars) => {
      const gate = parseGateError(error);
      if (gate) setDialog({ kind: "gate", error: gate, retry: vars.run });
      else toast.error(errorMessage(error, t("actionFailed")));
    },
  });

  const reactMutation = useMutation({
    mutationFn: (next: ReactionName | null) => react(post.schoolId, post.id, next),
    onMutate: async (next) => {
      await queryClient.cancelQueries({ queryKey: fk.all });
      const snapshot = queryClient.getQueriesData({ queryKey: fk.all });
      queryClient.setQueriesData({ queryKey: fk.all }, (data: unknown) => patchById<Post>(data, post.id, (p) => applyReaction(p, next)));
      return { snapshot };
    },
    onError: (error, _next, context) => {
      for (const [key, data] of context?.snapshot ?? []) queryClient.setQueryData(key, data);
      toast.error(errorMessage(error, t("reactFailed")));
    },
    onSuccess: (summary) => {
      queryClient.setQueriesData({ queryKey: fk.all }, (data: unknown) =>
        patchById<Post>(data, post.id, (p) => ({ ...p, reactionCounts: summary.counts, myReaction: summary.mine ?? undefined })),
      );
    },
  });

  const total = reactionTotal(post.reactionCounts);
  const mine = post.myReaction ?? null;
  const withheld = isWithheldPlaceholder(post);
  const warn = isWithheldWarning(post);

  return (
    <Card className={cn("gap-0 py-5 shadow-sm", variant === "achievement" && "border-primary/30")} data-testid="post-card" data-status={post.status} data-variant={variant}>
      <article ref={ref} aria-label={t("articleLabel")}>
        <CardContent className="flex flex-col gap-4 px-5">
          <header className="flex items-start gap-3">
            {variant === "achievement" ? (
              <span aria-hidden className="grid size-12 shrink-0 place-items-center rounded-2xl bg-primary/10 text-primary" data-testid="achievement-badge">
                <TrophyIcon className="size-6" />
              </span>
            ) : (
              <AuthorAvatar author={post.author} size={48} />
            )}
            <div className="min-w-0 flex-1">
              {variant === "achievement" ? (
                <p className="text-lg leading-tight font-bold text-primary" data-testid="achievement-title">
                  {tr("achievement")}
                </p>
              ) : null}
              <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-base font-bold">
                <AuthorName author={post.author} />
                <span
                  className={cn(
                    "rounded-full px-2.5 py-0.5 text-xs font-semibold",
                    roleChipTone(post.author.role) === "accent" ? "bg-primary/10 text-primary" : "bg-muted text-muted-foreground",
                  )}
                  data-testid="role-chip"
                >
                  {t(`roleOn.${post.author.role}`)}
                </span>
                {variant === "activity" ? (
                  <span className="inline-flex items-center gap-1 rounded-full bg-secondary px-2.5 py-0.5 text-xs font-semibold text-secondary-foreground" data-testid="activity-chip">
                    <PaletteIcon className="size-3.5" aria-hidden />
                    {tr("activity")}
                  </span>
                ) : null}
              </p>
              <p className="flex flex-wrap items-center gap-x-2 text-sm text-muted-foreground">
                <RelativeTime instant={post.publishedAt ?? post.createdAt} />
                {post.editedAt ? <span>{t("edited")}</span> : null}
              </p>
            </div>
            <StatusBadges post={post} />
            {hasAnyAction(menu) ? (
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button type="button" variant="ghost" size="icon" aria-label={t("menu")} data-testid="post-menu">
                    <MoreHorizontalIcon aria-hidden />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                  {menu.edit && onEdit ? (
                    <DropdownMenuItem onSelect={() => onEdit(post)}>
                      <PencilIcon aria-hidden /> {t("edit")}
                    </DropdownMenuItem>
                  ) : null}
                  {menu.publish ? (
                    <DropdownMenuItem
                      onSelect={() =>
                        act.mutate({
                          run: (confirm) => publishPost(post.schoolId, post.id, confirm || undefined),
                          success: t("publishedDone"),
                        })
                      }
                    >
                      <SendIcon aria-hidden /> {t("publish")}
                    </DropdownMenuItem>
                  ) : null}
                  {menu.schedule ? (
                    <DropdownMenuItem onSelect={() => setDialog({ kind: "schedule" })}>
                      <CalendarClockIcon aria-hidden /> {post.status === "SCHEDULED" ? t("reschedule") : t("schedule")}
                    </DropdownMenuItem>
                  ) : null}
                  {menu.unschedule ? (
                    <DropdownMenuItem onSelect={() => act.mutate({ run: () => unschedulePost(post.schoolId, post.id), success: t("unscheduledDone") })}>
                      <UndoIcon aria-hidden /> {t("unschedule")}
                    </DropdownMenuItem>
                  ) : null}
                  {menu.pin ? (
                    <DropdownMenuItem onSelect={() => act.mutate({ run: () => setPinned(post.schoolId, post.id, true) })}>
                      <PinIcon aria-hidden /> {t("pin")}
                    </DropdownMenuItem>
                  ) : null}
                  {menu.unpin ? (
                    <DropdownMenuItem onSelect={() => act.mutate({ run: () => setPinned(post.schoolId, post.id, false) })}>
                      <PinOffIcon aria-hidden /> {t("unpin")}
                    </DropdownMenuItem>
                  ) : null}
                  {menu.archive ? (
                    <DropdownMenuItem onSelect={() => act.mutate({ run: () => archivePost(post.schoolId, post.id), success: t("archivedDone") })}>
                      <ArchiveIcon aria-hidden /> {t("archive")}
                    </DropdownMenuItem>
                  ) : null}
                  {menu.hide ? (
                    <DropdownMenuItem onSelect={() => setDialog({ kind: "hide" })}>
                      <EyeOffIcon aria-hidden /> {t("hide")}
                    </DropdownMenuItem>
                  ) : null}
                  {menu.unhide ? (
                    <DropdownMenuItem
                      onSelect={() =>
                        act.mutate({ run: (confirm) => unhidePost(post.schoolId, post.id, confirm || undefined), success: t("unhiddenDone") })
                      }
                    >
                      <EyeIcon aria-hidden /> {t("unhide")}
                    </DropdownMenuItem>
                  ) : null}
                  {menu.report ? (
                    <DropdownMenuItem data-testid="post-report" onSelect={() => setDialog({ kind: "report" })}>
                      <FlagIcon aria-hidden /> {t("report")}
                    </DropdownMenuItem>
                  ) : null}
                  {menu.reportAuthor ? (
                    <DropdownMenuItem data-testid="post-report-author" onSelect={() => setDialog({ kind: "reportUser" })}>
                      <FlagIcon aria-hidden /> {t("reportAuthor")}
                    </DropdownMenuItem>
                  ) : null}
                  {menu.remove ? (
                    <>
                      <DropdownMenuSeparator />
                      <DropdownMenuItem variant="destructive" onSelect={() => setDialog({ kind: "delete" })}>
                        <Trash2Icon aria-hidden /> {t("delete")}
                      </DropdownMenuItem>
                    </>
                  ) : null}
                </DropdownMenuContent>
              </DropdownMenu>
            ) : null}
          </header>

          <PostNotes post={post} />

          {post.body ? <Body post={post} translationEnabled={translationEnabled} /> : null}

          {withheld ? <WithheldPlaceholder /> : <MediaGrid items={post.media} />}
          {warn ? (
            <p role="note" className="flex items-start gap-2 rounded-xl bg-warning/10 p-3 text-sm" data-testid="media-withheld-warning">
              <EyeOffIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
              {t("withheldWarning")}
            </p>
          ) : null}

          {post.tags.length > 0 && variant === "achievement" ? (
            <ul aria-label={tr("tagged")} className="flex flex-wrap gap-x-4 gap-y-3" data-testid="post-tags">
              {post.tags.map((tag) => (
                <li key={tag.studentId} className="flex flex-col items-center gap-1.5">
                  <StudentAvatar id={tag.studentId} name={tag.displayName} mediaId={tag.avatar?.id} size={56} className="ring-2 ring-primary/40 ring-offset-2 ring-offset-card" />
                  <span className="max-w-28 truncate rounded-full bg-primary/10 px-3 py-0.5 text-sm font-semibold text-primary" data-testid="post-tag">
                    {tag.displayName}
                  </span>
                </li>
              ))}
            </ul>
          ) : post.tags.length > 0 ? (
            <p className="text-sm text-muted-foreground" data-testid="post-tags">
              <span className="font-medium">{t("with")}</span> {post.tags.map((tag) => tag.displayName).join(", ")}
            </p>
          ) : null}

          <footer className="flex flex-col gap-2">
            {post.status === "PUBLISHED" || post.status === "ARCHIVED" || post.status === "HIDDEN" ? (
              <div className="flex flex-wrap items-center gap-2">
                <ReactionBar post={post} mine={mine} onReact={(r) => reactMutation.mutate(nextReaction(mine, r))} disabled={post.status !== "PUBLISHED"} />
                <Button
                  type="button"
                  variant="outline"
                  aria-expanded={commentsOpen}
                  onClick={() => setCommentsOpen((open) => !open)}
                  data-testid="comments-toggle"
                  className="min-h-11 rounded-full bg-card px-4"
                >
                  <MessageCircleIcon aria-hidden />
                  {t("comments", { count: post.commentCount ?? 0 })}
                </Button>
                {hasSeenCount(post) ? (
                  <span className="ms-auto inline-flex items-center gap-1 text-sm text-muted-foreground" data-testid="seen-count">
                    <EyeIcon className="size-4" aria-hidden />
                    {t("seen", { count: post.seenCount ?? 0 })}
                  </span>
                ) : null}
              </div>
            ) : null}
            {total > 0 ? <span className="sr-only">{t("reactionsTotal", { count: total })}</span> : null}
            {commentsOpen ? <Comments post={post} translationEnabled={translationEnabled} /> : null}
          </footer>
        </CardContent>
      </article>

      {dialog?.kind === "delete" ? (
        <ConfirmDialog
          title={t("deleteTitle")}
          description={t("deleteBody")}
          confirmLabel={t("delete")}
          pending={act.isPending}
          onConfirm={() =>
            act.mutate(
              { run: () => deletePost(post.schoolId, post.id), success: t("deletedDone") },
              { onSuccess: () => setDialog(null) },
            )
          }
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog?.kind === "hide" ? <HideDialog post={post} onClose={() => setDialog(null)} /> : null}
      {dialog?.kind === "report" ? <ReportDialog target={{ type: "POST", id: post.id }} onClose={() => setDialog(null)} /> : null}
      {dialog?.kind === "reportUser" && post.author.userId ? (
        <ReportDialog target={{ type: "USER", id: post.author.userId }} onClose={() => setDialog(null)} />
      ) : null}
      {dialog?.kind === "schedule" ? <ScheduleDialog post={post} onClose={() => setDialog(null)} /> : null}
      {dialog?.kind === "gate" ? <GateDialog error={dialog.error} retry={dialog.retry} onClose={() => setDialog(null)} /> : null}
    </Card>
  );
}

function StatusBadges({ post }: { post: Post }) {
  const t = useTranslations("school.feed.status");
  return (
    <div className="flex flex-wrap justify-end gap-1">
      {post.pinned ? (
        <Badge variant="secondary" data-testid="pinned-badge">
          <PinIcon aria-hidden /> {t("pinned")}
        </Badge>
      ) : null}
      {post.contribution && post.status === "PUBLISHED" ? <Badge variant="outline">{t("contribution")}</Badge> : null}
      {post.status !== "PUBLISHED" ? (
        <Badge variant={post.status === "REJECTED" || post.status === "HIDDEN" ? "destructive" : "outline"} data-testid="status-badge">
          {t(post.status)}
        </Badge>
      ) : null}
    </div>
  );
}

/** Scheduled time, rejection reason, a publish that was blocked: what the author needs to know about this post. */
function PostNotes({ post }: { post: Post }) {
  const t = useTranslations("school.feed.notes");
  const formatIn = useFormatInstantIn();
  const caps = useCapabilities(post.schoolId, post.classId ?? undefined, post.status === "SCHEDULED");
  return (
    <>
      {post.status === "SCHEDULED" && post.scheduledFor ? (
        <p className="rounded-xl bg-muted/60 p-3 text-sm" data-testid="scheduled-note">
          <span className="font-medium">
            {t("scheduledFor", { when: formatIn(post.scheduledFor, caps.data?.timeZone ?? "UTC"), zone: caps.data?.timeZone ?? "UTC" })}
          </span>{" "}
          {t("scheduledWarning")}
        </p>
      ) : null}
      {post.publishErrorCode ? (
        <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="publish-blocked">
          {t("blocked")}
        </p>
      ) : null}
      {post.status === "PENDING_APPROVAL" ? <p className="rounded-xl bg-muted/60 p-3 text-sm">{t("waiting")}</p> : null}
      {post.status === "REJECTED" ? (
        <p role="note" className="rounded-xl bg-destructive/10 p-3 text-sm" data-testid="rejection">
          <span className="font-medium">{t("rejected")}</span> {post.rejectionReason}
        </p>
      ) : null}
      {post.status === "HIDDEN" && post.rejectionReason ? (
        <p role="note" className="rounded-xl bg-destructive/10 p-3 text-sm">
          <span className="font-medium">{t("hiddenWhy")}</span> {post.rejectionReason}
        </p>
      ) : null}
    </>
  );
}

function Body({ post, translationEnabled }: { post: Post; translationEnabled: boolean }) {
  const t = useTranslations("school.feed.post");
  const locale = useLocale();
  const [shown, setShown] = useState<"original" | "translated">("original");
  const [unavailable, setUnavailable] = useState(false);
  const translate = useMutation({
    mutationFn: () => translatePost(post.schoolId, post.id, locale),
    onSuccess: () => setShown("translated"),
    onError: (error) => {
      // 403 (not enabled) and 503 (no provider yet) are normal states: hide the control, no alarm.
      setUnavailable(true);
      toast.message(errorMessage(error, t("translateUnavailable")));
    },
  });
  // The first line is bold by CSS only: the text itself is exactly what the API sent (or the translation).
  const text = splitFirstLine(shown === "translated" && translate.data ? translate.data.text : (post.body ?? ""));
  const sameLanguage = !!post.bodyLanguageCode && post.bodyLanguageCode.toLowerCase().startsWith(locale.toLowerCase());
  const canOffer = translationEnabled && !unavailable && !sameLanguage && post.status === "PUBLISHED";
  return (
    <div className="flex flex-col gap-2">
      <p className="text-base break-words whitespace-pre-wrap" lang={post.bodyLanguageCode ?? undefined} data-testid="post-body-text">
        {text.first ? (
          <span className="font-semibold" data-testid="post-first-line">
            {text.first}
          </span>
        ) : null}
        {text.rest}
      </p>
      {shown === "translated" && translate.data ? <p className="text-xs font-medium text-muted-foreground">{t("machineTranslated")}</p> : null}
      {canOffer ? (
        <Button
          type="button"
          variant="link"
          size="sm"
          className="self-start px-0"
          disabled={translate.isPending}
          onClick={() => (shown === "translated" ? setShown("original") : translate.data ? setShown("translated") : translate.mutate())}
        >
          <Languages aria-hidden />
          {shown === "translated" ? t("showOriginal") : t("translate")}
        </Button>
      ) : null}
    </div>
  );
}

function ReactionBar({
  post,
  mine,
  onReact,
  disabled,
}: {
  post: Post;
  mine: ReactionName | null;
  onReact: (reaction: ReactionName) => void;
  disabled?: boolean;
}) {
  const t = useTranslations("school.feed.reactions");
  return (
    <div role="group" aria-label={t("label")} className="flex flex-wrap gap-2">
      {REACTIONS.map((reaction) => {
        const count = post.reactionCounts[reaction] ?? 0;
        return (
          <Button
            key={reaction}
            type="button"
            variant="outline"
            aria-pressed={mine === reaction}
            aria-label={t(`names.${reaction}`)}
            disabled={disabled}
            onClick={() => onReact(reaction)}
            data-testid={`react-${reaction}`}
            className={cn("min-h-11 min-w-11 rounded-full bg-card px-3.5", mine === reaction && "border-primary bg-primary/10 text-primary hover:bg-primary/15")}
          >
            <span aria-hidden>{REACTION_EMOJI[reaction]}</span>
            {count > 0 ? <span data-testid={`react-count-${reaction}`}>{count}</span> : null}
          </Button>
        );
      })}
    </div>
  );
}

function HideDialog({ post, onClose }: { post: Post; onClose: () => void }) {
  const t = useTranslations("school.feed.post");
  const refresh = useRefreshFeed();
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const hide = useMutation({
    mutationFn: () => hidePost(post.schoolId, post.id, reason),
    onSuccess: async () => {
      await refresh();
      toast.success(t("hiddenDone"));
      onClose();
    },
    onError: (e) => setError(errorMessage(e, t("actionFailed"))),
  });
  return (
    <FormDialog title={t("hideTitle")} description={t("hideBody")} submitLabel={t("hide")} pending={hide.isPending} error={error} onSubmit={() => hide.mutate()} onClose={onClose}>
      <div className="flex flex-col gap-2">
        <label htmlFor="hide-reason" className="text-sm font-medium">
          {t("hideReason")}
        </label>
        <Textarea id="hide-reason" rows={3} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      </div>
    </FormDialog>
  );
}

function ScheduleDialog({ post, onClose }: { post: Post; onClose: () => void }) {
  const t = useTranslations("school.feed.composer");
  const refresh = useRefreshFeed();
  const caps = useCapabilities(post.schoolId, post.classId ?? undefined);
  const zone = caps.data?.timeZone ?? "UTC";
  const [local, setLocal] = useState(() => (post.scheduledFor ? instantToLocal(post.scheduledFor, zone) : null) ?? defaultScheduleLocal(zone, new Date()));
  const [confirm, setConfirm] = useState(false);
  const [gate, setGate] = useState<GateError | null>(null);
  const [error, setError] = useState<string | null>(null);
  const bounds = scheduleBounds(zone, new Date());
  const tp = useTranslations("school.feed.problems");
  const save = useMutation({
    mutationFn: async () => {
      const check = checkSchedule(local, zone, new Date());
      if (!check.ok) throw new Error(check.reason);
      return schedulePost(post.schoolId, post.id, check.instant, confirm || undefined);
    },
    onSuccess: async () => {
      await refresh();
      toast.success(t("scheduledDone"));
      onClose();
    },
    onError: (e) => {
      const refused = parseGateError(e);
      setGate(refused);
      if (e instanceof Error && ["invalid", "past", "tooFar"].includes(e.message)) {
        setError(tp(e.message === "invalid" ? "scheduleInvalid" : e.message === "past" ? "schedulePast" : "scheduleTooFar"));
      } else setError(refused ? null : errorMessage(e, t("failed")));
    },
  });
  return (
    <FormDialog title={t("scheduleTitle")} submitLabel={t("scheduleSubmit")} pending={save.isPending} error={error} onSubmit={() => save.mutate()} onClose={onClose}>
      <div className="flex flex-col gap-2">
        <label htmlFor="reschedule" className="text-sm font-medium">
          {t("scheduleAt", { zone })}
        </label>
        <input
          id="reschedule"
          type="datetime-local"
          className="h-11 w-full max-w-xs rounded-md border border-input bg-background px-3"
          value={local}
          min={bounds?.min}
          max={bounds?.max}
          onChange={(e) => setLocal(e.target.value)}
        />
        <p className="text-sm text-muted-foreground">{t("scheduleNote")}</p>
      </div>
      {gate ? <GateNotice error={gate} confirmed={confirm} onConfirmChange={setConfirm} /> : null}
    </FormDialog>
  );
}

/** A publish, un-hide or approval the gate refused: explain it, and retry when the author confirms. */
function GateDialog({ error, retry, onClose }: { error: GateError; retry: (confirm: boolean) => Promise<unknown>; onClose: () => void }) {
  const t = useTranslations("school.feed.gate");
  const refresh = useRefreshFeed();
  const [confirm, setConfirm] = useState(false);
  const [gate, setGate] = useState<GateError>(error);
  const [message, setMessage] = useState<string | null>(null);
  const run = useMutation({
    mutationFn: () => retry(confirm),
    onSuccess: async () => {
      await refresh();
      onClose();
    },
    onError: (e) => {
      const refused = parseGateError(e);
      if (refused) setGate(refused);
      else setMessage(errorMessage(e, t("failed")));
    },
  });
  return (
    <FormDialog
      title={t("dialogTitle")}
      submitLabel={gate.kind === "confirmation_required" ? t("retry") : t("retryAgain")}
      pending={run.isPending}
      error={message}
      onSubmit={() => (gate.kind === "confirmation_required" && !confirm ? setMessage(t("confirmFirst")) : run.mutate())}
      onClose={onClose}
    >
      <GateNotice error={gate} confirmed={confirm} onConfirmChange={setConfirm} />
    </FormDialog>
  );
}
