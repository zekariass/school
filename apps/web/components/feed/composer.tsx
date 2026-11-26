"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useRef, useState } from "react";
import { toast } from "sonner";
import { useFormatInstantIn } from "@/components/attendance/status";
import { ErrorNotice, LoadingRows } from "@/components/console/states";
import { StudentPicker } from "@/components/school/student-picker";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/console/errors";
import {
  MAX_BODY,
  MAX_TAGS,
  buildCreateBody,
  buildUpdateBody,
  composerFromPost,
  composerProblems,
  draftProblems,
  initialComposer,
  needsConfirmation,
  submissionFingerprint,
  withPhotos,
  withStudents,
  withoutStudents,
  type ComposerProblem,
  type ComposerState,
  type PostType,
  type PublishTiming,
} from "@/lib/feed/composer";
import { parseGateError, type GateError } from "@/lib/feed/consent-error";
import { createClassPost, createSchoolPost, updatePost, type FeedCapabilities, type Post } from "@/lib/feed/queries";
import { checkSchedule, scheduleBounds } from "@/lib/feed/schedule";
import { newIdempotencyKey } from "@/lib/console/idempotency";
import { fetchRoster, sk } from "@/lib/school/queries";
import { cn } from "@/lib/utils";
import { GateNotice } from "./gate-notice";
import { PhotoField } from "./photo-field";
import { StudentTags } from "./student-tags";
import { useCapabilities, useRefreshFeed } from "./use-feed";

interface ComposerProps {
  schoolId: string;
  /** The class to post to; absent for a school-wide post. */
  classId?: string;
  /** Edit this post instead of making a new one. */
  post?: Post;
  onClose: () => void;
}

/**
 * The post composer: text, photos (each with its description), tagged children, comments on or off, and when to
 * publish: now, at a time on the school's clock, or as a draft. When the consent gate refuses, nothing is lost:
 * the text and photos stay and the dialog explains what to change.
 */
export function PostComposer(props: ComposerProps) {
  const t = useTranslations("school.feed.composer");
  const caps = useCapabilities(props.schoolId, props.classId);
  return (
    <Dialog open onOpenChange={(open) => !open && props.onClose()}>
      <DialogContent className="max-h-[92dvh] overflow-y-auto sm:max-w-xl" data-testid="composer">
        {caps.isPending ? (
          <>
            <DialogTitle>{t("loading")}</DialogTitle>
            <LoadingRows rows={3} />
          </>
        ) : caps.isError ? (
          <>
            <DialogTitle>{t("unavailable")}</DialogTitle>
            <ErrorNotice message={errorMessage(caps.error, t("loadFailed"))} onRetry={() => void caps.refetch()} />
          </>
        ) : (
          <ComposerForm {...props} caps={caps.data} />
        )}
      </DialogContent>
    </Dialog>
  );
}

function ComposerForm({ schoolId, classId, post, onClose, caps }: ComposerProps & { caps: FeedCapabilities }) {
  const t = useTranslations("school.feed.composer");
  const tp = useTranslations("school.feed.problems");
  const refresh = useRefreshFeed();
  const formatIn = useFormatInstantIn();
  const editing = !!post;
  const zone = caps.timeZone;
  const [state, setState] = useState<ComposerState>(() =>
    post ? composerFromPost(post) : initialComposer({ commentsEnabled: caps.commentsDefaultEnabled ?? true, timeZone: zone, now: new Date() }),
  );
  const [gate, setGate] = useState<GateError | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showProblems, setShowProblems] = useState(false);
  const key = useRef<{ fingerprint: string; key: string } | null>(null);
  const bounds = scheduleBounds(zone, new Date());

  const roster = useQuery({
    queryKey: sk.classRoster(schoolId, classId ?? "none"),
    queryFn: () => fetchRoster(schoolId, classId!),
    enabled: !!classId,
    staleTime: 60_000,
  });

  const submit = useMutation({
    mutationFn: async () => {
      if (post) return updatePost(schoolId, post.id, buildUpdateBody(state));
      const body = buildCreateBody(state, zone, new Date());
      if (!body) throw new Error("schedule");
      // The same body keeps its key (a retry never posts twice); a changed body (an untagged child) gets a new one.
      const fingerprint = submissionFingerprint(body);
      if (key.current?.fingerprint !== fingerprint) key.current = { fingerprint, key: newIdempotencyKey() };
      return classId ? createClassPost(schoolId, classId, body, key.current.key) : createSchoolPost(schoolId, body, key.current.key);
    },
    onSuccess: async (saved) => {
      await refresh();
      toast.success(
        editing ? t("saved") : state.timing === "draft" ? t("draftSaved") : state.timing === "schedule" ? t("scheduledDone") : saved.status === "PUBLISHED" ? t("published") : t("saved"),
      );
      onClose();
    },
    onError: (caught) => {
      const refused = parseGateError(caught);
      setGate(refused);
      setError(refused ? null : errorMessage(caught, t("failed")));
    },
  });

  const blocking: ComposerProblem[] = (editing || state.timing === "draft" ? draftProblems(state) : composerProblems(state, zone, new Date())).filter(
    (p) => p !== "confirmRequired",
  );
  // The checkbox is the author's confirmation for untagged photos; a draft or an edit lets the API decide (it answers 422).
  const confirmNeeded = !editing && state.timing !== "draft" && needsConfirmation(state) && !state.confirmNoUnconsented;

  function send() {
    setGate(null);
    setError(null);
    setShowProblems(true);
    if (blocking.length > 0 || confirmNeeded) return;
    submit.mutate();
  }

  const timings: PublishTiming[] = ["now", "schedule", "draft"];
  const photoTagged = state.photos.length > 0;
  const schedulePick = state.timing === "schedule" ? checkSchedule(state.scheduledLocal, zone, new Date()) : null;

  return (
    <form
      noValidate
      className="flex flex-col gap-4"
      onSubmit={(event) => {
        event.preventDefault();
        send();
      }}
    >
      <DialogHeader>
        <DialogTitle>{editing ? t("editTitle") : classId ? t("newTitle") : t("newSchoolTitle")}</DialogTitle>
        <DialogDescription>{classId ? t("classHelp") : t("schoolHelp")}</DialogDescription>
      </DialogHeader>

      <div className="flex flex-col gap-2">
        <label htmlFor="post-body" className="text-sm font-medium">
          {t("body")}
        </label>
        <Textarea
          id="post-body"
          rows={4}
          value={state.body}
          maxLength={MAX_BODY}
          onChange={(event) => setState({ ...state, body: event.target.value })}
          placeholder={t("bodyPlaceholder")}
          data-testid="post-body"
        />
        <p className="text-xs text-muted-foreground">{t("bodyCount", { count: state.body.length, max: MAX_BODY })}</p>
      </div>

      <PhotoField
        photos={state.photos}
        onChange={(photos) => {
          setState(withPhotos(state, photos));
          setGate(null);
        }}
        context={{ type: classId ? "CLASS" : "SCHOOL", id: classId ?? schoolId }}
        disabled={submit.isPending}
      />

      {classId ? (
        roster.isError ? (
          <ErrorNotice message={errorMessage(roster.error, t("rosterFailed"))} onRetry={() => void roster.refetch()} />
        ) : (
          <StudentTags
            students={roster.data ?? []}
            value={state.studentIds}
            onChange={(ids) => setState(withStudents(state, ids))}
            max={MAX_TAGS}
            label={t("tags")}
            hint={t("tagsHelp")}
          />
        )
      ) : (
        <StudentPicker value={state.studentIds} onChange={(ids) => setState(withStudents(state, ids))} label={t("tags")} />
      )}

      {photoTagged && state.studentIds.length === 0 ? (
        <label className="flex cursor-pointer items-start gap-3 rounded-xl border bg-muted/40 p-3 text-sm" htmlFor="post-confirm">
          <input
            id="post-confirm"
            type="checkbox"
            className="mt-0.5 size-5 shrink-0 accent-primary"
            checked={state.confirmNoUnconsented}
            onChange={(event) => setState({ ...state, confirmNoUnconsented: event.target.checked })}
            data-testid="post-confirm"
          />
          <span>
            <span className="block font-medium">{t("confirmLabel")}</span>
            <span className="text-muted-foreground">{t("confirmHelp")}</span>
          </span>
        </label>
      ) : null}

      {gate ? (
        <GateNotice
          error={gate}
          disabled={submit.isPending}
          onUntag={(ids) => {
            setState(withoutStudents(state, ids));
            setGate(null);
          }}
          confirmed={state.confirmNoUnconsented}
          onConfirmChange={(value) => setState({ ...state, confirmNoUnconsented: value })}
        />
      ) : null}

      <div className="flex flex-col gap-2">
        <span className="text-sm font-medium">{t("kind")}</span>
        <div role="group" aria-label={t("kind")} className="flex flex-wrap gap-2">
          {(["POST", "ACTIVITY", "ACHIEVEMENT"] as PostType[]).map((kind) => (
            <button
              key={kind}
              type="button"
              aria-pressed={state.postType === kind}
              onClick={() => setState({ ...state, postType: kind })}
              className={cn(
                "min-h-10 rounded-full border px-4 text-sm font-medium",
                state.postType === kind ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent",
              )}
            >
              {t(`kinds.${kind}`)}
            </button>
          ))}
        </div>
      </div>

      <div className="flex items-center justify-between gap-3 rounded-xl border p-3">
        <label htmlFor="post-comments" className="min-w-0 text-sm font-medium">
          {t("comments")}
          <span className="block text-xs font-normal text-muted-foreground">{t("commentsHelp")}</span>
        </label>
        <Switch id="post-comments" checked={state.commentsEnabled} onCheckedChange={(value) => setState({ ...state, commentsEnabled: value })} />
      </div>

      {!editing ? (
        <fieldset className="flex flex-col gap-2">
          <legend className="text-sm font-medium">{t("when")}</legend>
          <div className="flex flex-wrap gap-2">
            {timings.map((timing) => (
              <label
                key={timing}
                className={cn(
                  "flex min-h-10 cursor-pointer items-center gap-2 rounded-full border px-4 text-sm font-medium",
                  state.timing === timing ? "border-primary bg-primary/10" : "hover:bg-accent",
                )}
              >
                <input
                  type="radio"
                  name="post-timing"
                  className="accent-primary"
                  checked={state.timing === timing}
                  onChange={() => setState({ ...state, timing })}
                  data-testid={`timing-${timing}`}
                />
                {t(`timing.${timing}`)}
              </label>
            ))}
          </div>
          {state.timing === "schedule" ? (
            <div className="flex flex-col gap-2 rounded-xl bg-muted/50 p-3">
              <label htmlFor="post-schedule" className="text-sm font-medium">
                {t("scheduleAt", { zone })}
              </label>
              <input
                id="post-schedule"
                type="datetime-local"
                className="h-11 w-full max-w-xs rounded-md border border-input bg-background px-3"
                value={state.scheduledLocal}
                min={bounds?.min}
                max={bounds?.max}
                onChange={(event) => setState({ ...state, scheduledLocal: event.target.value })}
                data-testid="post-schedule"
              />
              {schedulePick?.ok ? <p className="text-sm">{t("willPublish", { when: formatIn(schedulePick.instant, zone) })}</p> : null}
              <p className="text-sm text-muted-foreground">{t("scheduleNote")}</p>
            </div>
          ) : null}
          {state.timing === "draft" ? <p className="text-sm text-muted-foreground">{t("draftNote")}</p> : null}
        </fieldset>
      ) : null}

      {showProblems && blocking.length > 0 ? (
        <ul role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
          {blocking.map((problem) => (
            <li key={problem}>{tp(problem)}</li>
          ))}
        </ul>
      ) : null}
      {showProblems && confirmNeeded && blocking.length === 0 ? (
        <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
          {tp("confirmRequired")}
        </p>
      ) : null}
      {error ? (
        <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="composer-error">
          {error}
        </p>
      ) : null}

      <DialogFooter>
        <Button type="button" variant="outline" onClick={onClose} disabled={submit.isPending}>
          {t("cancel")}
        </Button>
        <Button type="submit" disabled={submit.isPending} data-testid="composer-submit">
          {submit.isPending ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : null}
          {editing ? t("save") : t(`submit.${state.timing}`)}
        </Button>
      </DialogFooter>
    </form>
  );
}
