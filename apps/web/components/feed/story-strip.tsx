"use client";

import { useQuery, type QueryKey } from "@tanstack/react-query";
import { PlusIcon, TypeIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { MediaImage } from "@/components/media/media-image";
import { cn } from "@/lib/utils";
import type { Story } from "@/lib/feed/queries";
import { authorLabel } from "@/lib/feed/view";
import { StoryComposer } from "./story-composer";
import { StoryViewer, openingIndex } from "./story-viewer";

/**
 * The row of stories above a feed: a circle per story, with a ring while it is unseen, and "Add story" for people
 * who may post. Opening one starts the full-screen viewer at the first unseen story.
 */
export function StoryStrip({
  queryKey,
  fetchStories,
  create,
  enabled = true,
}: {
  queryKey: QueryKey;
  fetchStories: () => Promise<Story[]>;
  /** Where "Add story" posts to; omitted when the caller may not post. */
  create?: { schoolId: string; classId?: string };
  enabled?: boolean;
}) {
  const t = useTranslations("school.feed.stories");
  const tr = useTranslations("school.feed.roles");
  const [viewer, setViewer] = useState<{ start: number } | null>(null);
  const [composing, setComposing] = useState(false);
  const stories = useQuery({ queryKey, queryFn: fetchStories, enabled, staleTime: 30_000, retry: false });
  const list = stories.data ?? [];

  if (!create && (stories.isPending || stories.isError || list.length === 0)) return null;

  return (
    <section aria-label={t("title")} data-testid="story-strip">
      <ul className="flex gap-3 overflow-x-auto pb-1">
        {create ? (
          <li className="shrink-0">
            <button type="button" onClick={() => setComposing(true)} className="flex w-20 flex-col items-center gap-1 text-center" data-testid="story-add">
              <span className="grid size-16 place-items-center rounded-full border-2 border-dashed border-primary text-primary">
                <PlusIcon aria-hidden />
              </span>
              <span className="w-full truncate text-xs font-medium">{t("add")}</span>
            </button>
          </li>
        ) : null}
        {list.map((story, index) => {
          const label = authorLabel(story.author);
          const name = label.kind === "name" ? label.name : tr(label.role);
          return (
            <li key={story.id} className="shrink-0">
              <button
                type="button"
                onClick={() => setViewer({ start: openingIndex(list, story.id) })}
                className="flex w-20 flex-col items-center gap-1 text-center"
                aria-label={t("open", { name, state: story.viewedByMe ? t("seenState") : t("newState") })}
                data-testid="story-bubble"
                data-viewed={story.viewedByMe ? "true" : "false"}
                data-index={index}
              >
                <span className={cn("grid size-16 place-items-center overflow-hidden rounded-full p-0.5 ring-2 ring-offset-2 ring-offset-background", story.viewedByMe ? "ring-border" : "ring-primary")}>
                  {story.media && !story.mediaWithheld ? (
                    <MediaImage mediaId={story.media.mediaId} variant="THUMB" alt="" width={64} height={64} className="size-full rounded-full object-cover" />
                  ) : (
                    <span className="grid size-full place-items-center rounded-full bg-primary/10 text-primary">
                      <TypeIcon aria-hidden />
                    </span>
                  )}
                </span>
                <span className="w-full truncate text-xs font-medium">{name}</span>
              </button>
            </li>
          );
        })}
      </ul>
      {viewer ? <StoryViewer stories={list} startAt={viewer.start} onClose={() => setViewer(null)} /> : null}
      {composing && create ? <StoryComposer schoolId={create.schoolId} classId={create.classId} onClose={() => setComposing(false)} /> : null}
    </section>
  );
}
