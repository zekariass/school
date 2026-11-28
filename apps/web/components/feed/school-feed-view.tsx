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
import { ACHIEVEMENTS_FILTER, DEFAULT_TAB, RECENT_ACHIEVEMENTS, tabFilter, type FeedTab } from "@/lib/feed/tabs";
import { canOpenApprovals, canPostSchoolWide } from "@/lib/school/access";
import { cn } from "@/lib/utils";
import { PostComposer } from "./composer";
import { FeedBanner } from "./feed-banner";
import { FeedColumns } from "./feed-layout";
import { FeedList } from "./feed-list";
import { FeedTabs } from "./feed-tabs";
import { MyPosts } from "./my-posts";
import { PinnedAnnouncement } from "./pinned-announcement";
import { RecentAchievementsCard, UpcomingCard } from "./rail";
import { StoryStrip } from "./story-strip";
import { useCapabilities } from "./use-feed";

/**
 * `/s/{slug}/feed`: the school-wide posts and stories for members (tabs Feed | Photos | Activities | Achievements, each a
 * server-side filter) with a rail of the next events and the latest achievements, and the school-wide composer for
 * feed managers.
 */
export function SchoolFeedView() {
  const t = useTranslations("school.feed.schoolPage");
  const tr = useTranslations("school.feed.redesign");
  const { school, permissions, isTeacher, isStaff } = useSchool();
  const caps = useCapabilities(school.id);
  const [composing, setComposing] = useState(false);
  const [view, setView] = useState<"feed" | "mine">("feed");
  const [tab, setTab] = useState<FeedTab>(DEFAULT_TAB);
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
  const showTab = (next: FeedTab) => {
    setView("feed");
    setTab(next);
  };

  const rail = (
    <>
      <UpcomingCard schoolId={school.id} studentId={null} calendarHref={`/s/${encodeURIComponent(school.slug)}/calendar`} />
      <RecentAchievementsCard
        queryKey={fk.recentSchool(school.id)}
        fetchRecent={async () => (await fetchSchoolFeed(school.id, undefined, ACHIEVEMENTS_FILTER, RECENT_ACHIEVEMENTS)).items}
        onViewAll={() => showTab("achievements")}
      />
    </>
  );

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-4">
      <FeedColumns rail={rail} railLabel={tr("rail.label")}>
        <FeedBanner as="h1" title={t("title")} subtitle={school.name} titleId="school-feed-title" />

        {canOpenApprovals(access) || canPost ? (
          <div className="flex flex-wrap items-center gap-2">
            {canPost ? (
              <div role="group" aria-label={t("view")} className="me-auto flex gap-2">
                {(["feed", "mine"] as const).map((v) => (
                  <button
                    key={v}
                    type="button"
                    aria-pressed={view === v}
                    onClick={() => setView(v)}
                    className={cn("min-h-11 rounded-full border px-4 text-sm font-medium", view === v ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent")}
                  >
                    {t(v)}
                  </button>
                ))}
              </div>
            ) : null}
            {canOpenApprovals(access) ? (
              <Button asChild variant="outline" className="min-h-11 rounded-full">
                <Link href={`/s/${encodeURIComponent(school.slug)}/feed/approvals`} data-testid="approvals-link">
                  <ClipboardCheckIcon aria-hidden />
                  {t("approvals")}
                </Link>
              </Button>
            ) : null}
            {canPost ? (
              <Button onClick={() => setComposing(true)} data-testid="new-school-post" className="min-h-11 rounded-full">
                <PlusIcon aria-hidden />
                {t("newPost")}
              </Button>
            ) : null}
          </div>
        ) : null}

        {view === "feed" ? (
          <FeedTabs value={tab} onValueChange={setTab}>
            {tab === "feed" ? (
              <>
                <PinnedAnnouncement schoolId={school.id} hrefFor={(card) => `/s/${encodeURIComponent(school.slug)}/announcements/${card.id}`} />
                <StoryStrip
                  queryKey={fk.schoolStories(school.id)}
                  fetchStories={() => fetchSchoolStories(school.id)}
                  create={canPost ? { schoolId: school.id } : undefined}
                />
              </>
            ) : null}
            <FeedList
              key={tab}
              queryKey={fk.schoolFeed(school.id, tab)}
              fetchPage={(cursor) => fetchSchoolFeed(school.id, cursor, tabFilter(tab))}
              empty={tab === "feed" ? (canPost ? t("emptyPost") : t("empty")) : tr(`empty.${tab}`)}
            />
          </FeedTabs>
        ) : (
          <MyPosts schoolId={school.id} />
        )}
      </FeedColumns>
      {composing ? <PostComposer schoolId={school.id} onClose={() => setComposing(false)} /> : null}
    </div>
  );
}
