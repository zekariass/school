"use client";

import { useQueryClient } from "@tanstack/react-query";
import { ChevronLeftIcon, ChevronRightIcon, EyeIcon, PauseIcon, PlayIcon, Trash2Icon, XIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { toast } from "sonner";
import { MediaImage } from "@/components/media/media-image";
import { ConfirmDialog } from "@/components/school/confirm-dialog";
import { Button } from "@/components/ui/button";
import { errorMessage } from "@/lib/console/errors";
import { patchById } from "@/lib/feed/cache";
import { deleteStory, fk, markStoryViewed, type Story } from "@/lib/feed/queries";
import { inViewingOrder, progressOf, startIndex, stepBack, stepForward, storyDuration, timeLeft } from "@/lib/feed/story-timing";
import { AuthorAvatar, AuthorName } from "./author";
import { WithheldPlaceholder } from "./media-grid";
import { cn } from "@/lib/utils";

/** Which stories to open and where to begin. */
export function openingIndex(stories: readonly Story[], storyId?: string): number {
  const ordered = inViewingOrder(stories);
  if (storyId) {
    const found = ordered.findIndex((s) => s.id === storyId);
    if (found >= 0) return found;
  }
  return startIndex(ordered);
}

function subscribeReducedMotion(onChange: () => void): () => void {
  const query = window.matchMedia("(prefers-reduced-motion: reduce)");
  query.addEventListener("change", onChange);
  return () => query.removeEventListener("change", onChange);
}

function usePrefersReducedMotion(): boolean {
  return useSyncExternalStore(
    subscribeReducedMotion,
    () => window.matchMedia("(prefers-reduced-motion: reduce)").matches,
    () => false,
  );
}

/**
 * The full-screen story viewer: a progress bar per story, tap or arrow keys to move, Space pauses, Escape closes.
 * It records one view per story (the API ignores the author's own), shows when the story expires, and with
 * "reduce motion" nothing advances by itself. Photos use the medium rendition, fetched only here.
 */
export function StoryViewer({ stories, startAt, onClose }: { stories: readonly Story[]; startAt: number; onClose: () => void }) {
  const t = useTranslations("school.feed.stories");
  const queryClient = useQueryClient();
  const ordered = inViewingOrder(stories);
  const [index, setIndex] = useState(Math.min(startAt, Math.max(0, ordered.length - 1)));
  const [elapsed, setElapsed] = useState(0);
  const [paused, setPaused] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  const [removing, setRemoving] = useState(false);
  const [removeError, setRemoveError] = useState<string | null>(null);
  const reduced = usePrefersReducedMotion();
  const root = useRef<HTMLDivElement>(null);
  const viewed = useRef(new Set<string>());
  const story = ordered[index];
  const duration = story ? storyDuration(story) : 0;

  function go(next: number) {
    setIndex(next);
    setElapsed(0);
  }
  function forward() {
    const step = stepForward(index, ordered.length);
    if (step.done) onClose();
    else go(step.index);
  }
  // The timer below runs outside React's render: it calls whatever `forward` is current.
  const advance = useRef(forward);
  useEffect(() => {
    advance.current = forward;
  });

  // Focus the viewer, keep the page behind it still, give focus back on close.
  useEffect(() => {
    const before = document.activeElement as HTMLElement | null;
    root.current?.focus();
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = overflow;
      before?.focus?.();
    };
  }, []);

  // One view per story: remember it locally at once (the ring turns off) and tell the API.
  const storyId = story?.id;
  const storySchool = story?.schoolId;
  const alreadyViewed = story?.viewedByMe;
  useEffect(() => {
    if (!storyId || !storySchool || alreadyViewed || viewed.current.has(storyId)) return;
    viewed.current.add(storyId);
    queryClient.setQueriesData({ queryKey: fk.all }, (data: unknown) => patchById<Story>(data, storyId, (s) => ({ ...s, viewedByMe: true })));
    void markStoryViewed(storySchool, storyId).catch(() => undefined);
  }, [storyId, storySchool, alreadyViewed, queryClient]);

  // The clock for the bars; the story moves on when its time is up (never with "reduce motion", or while paused).
  const running = !paused && !reduced && !removing && !!story;
  useEffect(() => {
    if (!running || duration <= 0) return;
    let frame = 0;
    let last = performance.now();
    let total = 0;
    const tick = (time: number) => {
      const dt = time - last;
      last = time;
      total += dt;
      setElapsed((value) => value + dt);
      if (total >= duration) {
        advance.current();
        return;
      }
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [running, duration, storyId]);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(timer);
  }, []);

  if (!story) return null;
  const left = timeLeft(story.expiresAt, now);

  async function remove() {
    if (!story) return;
    try {
      await deleteStory(story.schoolId, story.id);
      await queryClient.invalidateQueries({ queryKey: fk.all });
      toast.success(t("deleted"));
      setRemoving(false);
      if (ordered.length <= 1) onClose();
      else go(Math.min(index, ordered.length - 2));
    } catch (error) {
      setRemoveError(errorMessage(error, t("deleteFailed")));
    }
  }

  return (
    <div
      ref={root}
      role="dialog"
      aria-modal="true"
      aria-label={t("viewer")}
      tabIndex={-1}
      className="fixed inset-0 z-40 flex flex-col bg-foreground text-background outline-none"
      data-testid="story-viewer"
      onKeyDown={(event) => {
        if (removing) return;
        if (event.key === "Escape") onClose();
        else if (event.key === "ArrowRight") {
          if (document.dir === "rtl") go(stepBack(index));
          else forward();
        } else if (event.key === "ArrowLeft") {
          if (document.dir === "rtl") forward();
          else go(stepBack(index));
        }
        else if (event.key === " " && event.target === event.currentTarget) {
          event.preventDefault();
          setPaused((value) => !value);
        }
      }}
    >
      <div className="mx-auto flex h-full w-full max-w-md flex-col gap-3 p-3 sm:py-6">
        <ol className="flex gap-1" aria-label={t("progress", { number: index + 1, total: ordered.length })}>
          {ordered.map((s, i) => (
            <li key={s.id} className="h-1 flex-1 overflow-hidden rounded-full bg-background/30">
              <span
                className="block h-full bg-background"
                style={{ width: `${i < index ? 100 : i === index ? (reduced ? 100 : progressOf(elapsed, duration) * 100) : 0}%` }}
              />
            </li>
          ))}
        </ol>

        <header className="flex items-center gap-3">
          <AuthorAvatar author={story.author} size={36} />
          <div className="min-w-0 flex-1">
            <AuthorName author={story.author} className="block font-semibold" />
            <p className="text-xs opacity-80" data-testid="story-expiry">
              {left.expired ? t("expired") : t("expiresIn", { hours: left.hours, minutes: left.minutes })}
            </p>
          </div>
          {!reduced ? (
            <Button type="button" variant="ghost" size="icon" className="text-background hover:bg-background/20 hover:text-background" aria-label={paused ? t("play") : t("pause")} onClick={() => setPaused((value) => !value)}>
              {paused ? <PlayIcon aria-hidden /> : <PauseIcon aria-hidden />}
            </Button>
          ) : null}
          {story.canDelete ? (
            <Button type="button" variant="ghost" size="icon" className="text-background hover:bg-background/20 hover:text-background" aria-label={t("delete")} onClick={() => setRemoving(true)} data-testid="story-delete">
              <Trash2Icon aria-hidden />
            </Button>
          ) : null}
          <Button type="button" variant="ghost" size="icon" className="text-background hover:bg-background/20 hover:text-background" aria-label={t("close")} onClick={onClose} data-testid="story-close">
            <XIcon aria-hidden />
          </Button>
        </header>

        <div className="relative flex min-h-0 flex-1 items-center justify-center overflow-hidden rounded-2xl bg-background/10" aria-live="polite">
          {story.mediaWithheld && !story.media ? (
            <div className="p-4 text-foreground">
              <WithheldPlaceholder />
            </div>
          ) : story.media ? (
            <MediaImage
              mediaId={story.media.mediaId}
              variant="MEDIUM"
              alt={story.media.caption?.trim() || story.caption?.trim() || t("photoAlt")}
              width={story.media.width ?? 1280}
              height={story.media.height ?? 960}
              className="max-h-full max-w-full object-contain"
            />
          ) : (
            <p className="p-6 text-center text-2xl font-semibold break-words whitespace-pre-wrap" data-testid="story-caption">
              {story.caption}
            </p>
          )}
          <button type="button" className="absolute inset-y-0 start-0 w-1/3" aria-label={t("previous")} onClick={() => go(stepBack(index))} />
          <button type="button" className="absolute inset-y-0 end-0 w-1/3" aria-label={t("next")} onClick={forward} />
        </div>

        <footer className="flex flex-col gap-1 text-sm">
          {story.media && story.caption ? (
            <p className="break-words whitespace-pre-wrap" data-testid="story-caption">
              {story.caption}
            </p>
          ) : null}
          {story.tags.length > 0 ? <p className="opacity-80">{t("with", { names: story.tags.map((tag) => tag.displayName).join(", ") })}</p> : null}
          {typeof story.seenCount === "number" ? (
            <p className="inline-flex items-center gap-1 opacity-80" data-testid="story-seen">
              <EyeIcon className="size-4" aria-hidden />
              {t("seen", { count: story.seenCount })}
            </p>
          ) : null}
          <div className="flex items-center justify-between pt-1">
            <Button type="button" variant="ghost" size="sm" className="text-background hover:bg-background/20 hover:text-background" onClick={() => go(stepBack(index))} disabled={index === 0}>
              <ChevronLeftIcon className="rtl:rotate-180" aria-hidden />
              {t("previous")}
            </Button>
            <span className="text-xs opacity-80">{t("progress", { number: index + 1, total: ordered.length })}</span>
            <Button type="button" variant="ghost" size="sm" className={cn("text-background hover:bg-background/20 hover:text-background")} onClick={forward}>
              {index + 1 === ordered.length ? t("done") : t("next")}
              <ChevronRightIcon className="rtl:rotate-180" aria-hidden />
            </Button>
          </div>
        </footer>
      </div>
      {removing ? (
        <ConfirmDialog
          title={t("deleteTitle")}
          description={t("deleteBody")}
          confirmLabel={t("delete")}
          error={removeError}
          onConfirm={() => void remove()}
          onClose={() => {
            setRemoving(false);
            setRemoveError(null);
          }}
        />
      ) : null}
    </div>
  );
}
