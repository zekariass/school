"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2Icon, Undo2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { createContext, useCallback, useContext, useEffect, useMemo, useState, useSyncExternalStore } from "react";
import { Button } from "@/components/ui/button";
import { useSchool } from "@/components/school/school-context";
import { errorMessage } from "@/lib/console/errors";
import { useLocalStorageValue } from "@/lib/use-local-storage";
import { applyAwardToOverview } from "@/lib/points/optimistic";
import { pk, reverseBatch, type ClassPoints } from "@/lib/points/queries";
import { MUTE_KEY, parseMuted, playSound, type SoundName } from "@/lib/points/sound";
import { addEntry, clock, pruneExpired, removeEntry, secondsLeft, type UndoEntry } from "@/lib/points/undo";

interface PointsFeedback {
  /** The per-viewer sound preference (localStorage: the only thing this feature stores in the browser). */
  muted: boolean;
  setMuted: (muted: boolean) => void;
  /** `prefers-reduced-motion: reduce`: no movement, only static feedback. */
  reducedMotion: boolean;
  /** Plays an original synthesized sound unless muted. */
  play: (name: SoundName) => void;
  /** Says something to screen readers (`aria-live="polite"`). */
  announce: (message: string) => void;
  /** Starts the undo countdown of an award; it survives navigation inside the workspace. */
  addUndo: (entry: UndoEntry) => void;
}

const Context = createContext<PointsFeedback | null>(null);

export function usePointsFeedback(): PointsFeedback {
  const value = useContext(Context);
  if (!value) throw new Error("usePointsFeedback needs a PointsProvider");
  return value;
}

const reducedMotionQuery = "(prefers-reduced-motion: reduce)";

/** `prefers-reduced-motion`, live: `false` on the server and while hydrating. */
export function usePrefersReducedMotion(): boolean {
  return useSyncExternalStore(
    (onChange) => {
      const query = window.matchMedia(reducedMotionQuery);
      query.addEventListener("change", onChange);
      return () => query.removeEventListener("change", onChange);
    },
    () => window.matchMedia(reducedMotionQuery).matches,
    () => false,
  );
}

/**
 * Sound, motion preference, the screen-reader announcements and the undo countdowns of the points screens.
 * It sits in the school workspace layout, so a countdown goes on while the teacher moves between pages.
 * Undo entries live only in memory (no child data in storage); the mute switch is a localStorage preference.
 */
export function PointsProvider({ children }: { children: React.ReactNode }) {
  const [mutedRaw, setMutedRaw] = useLocalStorageValue(MUTE_KEY);
  const reducedMotion = usePrefersReducedMotion();
  const [message, setMessage] = useState("");
  const [entries, setEntries] = useState<UndoEntry[]>([]);
  const muted = parseMuted(mutedRaw);

  const play = useCallback((name: SoundName) => playSound(name, muted), [muted]);
  const setMuted = useCallback((next: boolean) => setMutedRaw(next ? "1" : "0"), [setMutedRaw]);
  const announce = useCallback((text: string) => {
    // A changing text node is what a live region announces; the zero-width space makes repeats count as changes.
    setMessage((previous) => (previous === text ? `${text}​` : text));
  }, []);
  const addUndo = useCallback((entry: UndoEntry) => setEntries((list) => addEntry(list, entry, Date.now())), []);

  const value = useMemo<PointsFeedback>(
    () => ({ muted, setMuted, reducedMotion, play, announce, addUndo }),
    [muted, setMuted, reducedMotion, play, announce, addUndo],
  );

  return (
    <Context.Provider value={value}>
      {children}
      <div aria-live="polite" role="status" className="sr-only" data-testid="points-live">
        {message}
      </div>
      <UndoToasts entries={entries} setEntries={setEntries} />
    </Context.Provider>
  );
}

function UndoToasts({
  entries,
  setEntries,
}: {
  entries: UndoEntry[];
  setEntries: React.Dispatch<React.SetStateAction<UndoEntry[]>>;
}) {
  const t = useTranslations("school.points.undo");
  const [now, setNow] = useState(() => Date.now());
  const active = entries.length > 0;

  useEffect(() => {
    if (!active) return;
    const tick = () => {
      const current = Date.now();
      setNow(current);
      setEntries((list) => {
        const kept = pruneExpired(list, current);
        return kept.length === list.length ? list : kept;
      });
    };
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [active, setEntries]);

  if (entries.length === 0) return null;
  return (
    <section
      aria-label={t("region")}
      className="fixed inset-x-4 bottom-4 z-40 flex flex-col gap-2 sm:start-auto sm:w-96"
      data-testid="undo-region"
    >
      {entries.map((entry) => (
        <UndoToast
          key={entry.id}
          entry={entry}
          seconds={secondsLeft(entry.undoableUntil, now)}
          onDone={() => setEntries((list) => removeEntry(list, entry.id))}
        />
      ))}
    </section>
  );
}

function UndoToast({ entry, seconds, onDone }: { entry: UndoEntry; seconds: number; onDone: () => void }) {
  const t = useTranslations("school.points.undo");
  const { school } = useSchool();
  const queryClient = useQueryClient();
  const { play, announce } = usePointsFeedback();

  const undo = useMutation({
    mutationFn: () => reverseBatch(school.id, entry.batchId),
    onSuccess: () => {
      queryClient.setQueryData<ClassPoints>(pk.overview(school.id, entry.classId), (old) =>
        old ? applyAwardToOverview(old, entry.studentIds, -entry.delta) : old,
      );
      play("undo");
      announce(t("undone", { label: entry.label }));
      // The projection catches up within moments; ask again a little later and keep the optimistic numbers until then.
      setTimeout(() => void queryClient.invalidateQueries({ queryKey: pk.overview(school.id, entry.classId) }), 3000);
      onDone();
    },
  });

  // A refused undo (window over, already undone) is final: the API's message is shown, then the toast goes.
  useEffect(() => {
    if (!undo.isError) return;
    const id = setTimeout(onDone, 6000);
    return () => clearTimeout(id);
  }, [undo.isError, onDone]);

  return (
    <div
      className="flex items-center gap-3 rounded-2xl border bg-card p-3 shadow-lg"
      data-testid="undo-toast"
    >
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-semibold">{entry.label}</p>
        <p className="truncate text-xs text-muted-foreground">
          {undo.isError ? (
            <span role="alert" className="font-medium text-destructive">
              {errorMessage(undo.error, t("failed"))}
            </span>
          ) : (
            t("timeLeft", { class: entry.className, time: clock(seconds) })
          )}
        </p>
      </div>
      {undo.isError ? null : (
        <Button size="sm" variant="outline" onClick={() => undo.mutate()} disabled={undo.isPending || seconds === 0}>
          {undo.isPending ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : <Undo2Icon className="size-4" aria-hidden />}
          {t("button")}
        </Button>
      )}
    </div>
  );
}
