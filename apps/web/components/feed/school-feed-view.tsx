"use client";

import { ClipboardCheckIcon, PlusIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { EmptyNotice, ErrorNotice } from "@/components/console/states";
import { PageHeader } from "@/components/school/page-header";
import { useSchool } from "@/components/school/school-context";
import { Button } from "@/components/ui/button";
import { Link } from "@/i18n/navigation";
import { ApiRequestError } from "@/lib/api-error";
import { errorMessage } from "@/lib/console/errors";
import { fetchSchoolFeed, fetchSchoolStories, fk } from "@/lib/feed/queries";
import { canOpenApprovals, canPostSchoolWide } from "@/lib/school/access";
import { cn } from "@/lib/utils";
import { PostComposer } from "./composer";
import { FeedList } from "./feed-list";
import { MyPosts } from "./my-posts";
import { StoryStrip } from "./story-strip";
import { useCapabilities } from "./use-feed";

/** `/s/{slug}/feed`: the school-wide posts and stories for members, and the school-wide composer for feed managers. */
export function SchoolFeedView() {
  const t = useTranslations("school.feed.schoolPage");
  const { school, permissions, isTeacher, isStaff } = useSchool();
  const caps = useCapabilities(school.id);
  const [composing, setComposing] = useState(false);
  const [view, setView] = useState<"feed" | "mine">("feed");
  const access = { permissions, isTeacher, isStaff, isStudent: false };

  if (caps.isError) {
    const disabled = caps.error instanceof ApiRequestError && caps.error.problem?.code === "feed.feature_disabled";
    return (
      <div className="mx-auto flex max-w-3xl flex-col gap-6">
        <PageHeader title={t("title")} description={t("subtitle")} />
        {disabled ? <EmptyNotice>{errorMessage(caps.error, t("disabled"))}</EmptyNotice> : <ErrorNotice message={errorMessage(caps.error, t("loadFailed"))} onRetry={() => void caps.refetch()} />}
      </div>
    );
  }
  const canPost = canPostSchoolWide(access) && !!caps.data?.canPostSchoolWide;

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <PageHeader
        title={t("title")}
        description={t("subtitle")}
        actions={
          <>
            {canOpenApprovals(access) ? (
              <Button asChild variant="outline">
                <Link href={`/s/${encodeURIComponent(school.slug)}/feed/approvals`} data-testid="approvals-link">
                  <ClipboardCheckIcon aria-hidden />
                  {t("approvals")}
                </Link>
              </Button>
            ) : null}
            {canPost ? (
              <Button onClick={() => setComposing(true)} data-testid="new-school-post">
                <PlusIcon aria-hidden />
                {t("newPost")}
              </Button>
            ) : null}
          </>
        }
      />
      {canPost ? (
        <div role="group" aria-label={t("view")} className="flex gap-2">
          {(["feed", "mine"] as const).map((v) => (
            <button
              key={v}
              type="button"
              aria-pressed={view === v}
              onClick={() => setView(v)}
              className={cn("min-h-10 rounded-full border px-4 text-sm font-medium", view === v ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent")}
            >
              {t(v)}
            </button>
          ))}
        </div>
      ) : null}
      {view === "feed" ? (
        <>
          <StoryStrip
            queryKey={fk.schoolStories(school.id)}
            fetchStories={() => fetchSchoolStories(school.id)}
            create={canPost ? { schoolId: school.id } : undefined}
          />
          <FeedList
            queryKey={fk.schoolFeed(school.id)}
            fetchPage={(cursor) => fetchSchoolFeed(school.id, cursor)}
            empty={canPost ? t("emptyPost") : t("empty")}
          />
        </>
      ) : (
        <MyPosts schoolId={school.id} />
      )}
      {composing ? <PostComposer schoolId={school.id} onClose={() => setComposing(false)} /> : null}
    </div>
  );
}
