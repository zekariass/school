"use client";

import { ClipboardCheckIcon, PlusIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { EmptyNotice, ErrorNotice } from "@/components/console/states";
import { useSchool } from "@/components/school/school-context";
import { Button } from "@/components/ui/button";
import { Link } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { fetchClassFeed, fetchClassStories, fk } from "@/lib/feed/queries";
import { canOpenApprovals } from "@/lib/school/access";
import type { ClassView } from "@/lib/school/queries";
import { cn } from "@/lib/utils";
import { PostComposer } from "./composer";
import { FeedList } from "./feed-list";
import { MyPosts } from "./my-posts";
import { StoryStrip } from "./story-strip";
import { useCapabilities } from "./use-feed";
import { ApiRequestError } from "@/lib/api-error";

/**
 * The class feed: stories on top, then the posts (pinned first, newest first). Teachers and managers post; the API
 * decides, and the buttons follow `feed/capabilities`. A school that switched the feed off gets one explanation.
 */
export function ClassFeedTab({ cls }: { cls: ClassView }) {
  const t = useTranslations("school.feed.classTab");
  const { school, permissions, isTeacher, isStaff } = useSchool();
  const caps = useCapabilities(school.id, cls.id);
  const [composing, setComposing] = useState(false);
  const [view, setView] = useState<"feed" | "mine">("feed");

  if (caps.isError) {
    const disabled = caps.error instanceof ApiRequestError && caps.error.problem?.code === "feed.feature_disabled";
    return disabled ? <EmptyNotice>{errorMessage(caps.error, t("disabled"))}</EmptyNotice> : <ErrorNotice message={errorMessage(caps.error, t("loadFailed"))} onRetry={() => void caps.refetch()} />;
  }
  const canPost = !!caps.data?.canPost && cls.status === "ACTIVE";
  const approvals = canOpenApprovals({ permissions, isTeacher, isStaff, isStudent: false });

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <div role="group" aria-label={t("view")} className="me-auto flex gap-2">
          {(["feed", "mine"] as const).map((v) =>
            v === "mine" && !canPost ? null : (
              <button
                key={v}
                type="button"
                aria-pressed={view === v}
                onClick={() => setView(v)}
                className={cn("min-h-10 rounded-full border px-4 text-sm font-medium", view === v ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent")}
                data-testid={`feed-view-${v}`}
              >
                {t(v)}
              </button>
            ),
          )}
        </div>
        {approvals ? (
          <Button asChild variant="outline">
            <Link href={`/s/${encodeURIComponent(school.slug)}/feed/approvals`} data-testid="approvals-link">
              <ClipboardCheckIcon aria-hidden />
              {t("approvals")}
            </Link>
          </Button>
        ) : null}
        {canPost ? (
          <Button onClick={() => setComposing(true)} data-testid="new-post">
            <PlusIcon aria-hidden />
            {t("newPost")}
          </Button>
        ) : null}
      </div>

      {view === "feed" ? (
        <>
          <StoryStrip
            queryKey={fk.classStories(school.id, cls.id)}
            fetchStories={() => fetchClassStories(school.id, cls.id)}
            create={canPost ? { schoolId: school.id, classId: cls.id } : undefined}
          />
          <FeedList
            queryKey={fk.classFeed(school.id, cls.id)}
            fetchPage={(cursor) => fetchClassFeed(school.id, cls.id, cursor)}
            empty={canPost ? t("emptyPost") : t("empty")}
          />
        </>
      ) : (
        <MyPosts schoolId={school.id} classId={cls.id} />
      )}
      {composing ? <PostComposer schoolId={school.id} classId={cls.id} onClose={() => setComposing(false)} /> : null}
    </div>
  );
}
