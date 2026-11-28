"use client";

import { useQuery } from "@tanstack/react-query";
import { ClipboardCheckIcon, PlusIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { EmptyNotice, ErrorNotice } from "@/components/console/states";
import { useSchool } from "@/components/school/school-context";
import { Button } from "@/components/ui/button";
import { Link } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { fetchClassFeed, fetchClassStories, fk } from "@/lib/feed/queries";
import { ACHIEVEMENTS_FILTER, DEFAULT_TAB, RECENT_ACHIEVEMENTS, tabFilter, type FeedTab } from "@/lib/feed/tabs";
import { canOpenApprovals } from "@/lib/school/access";
import { fetchClassTeachers, sk, type ClassView } from "@/lib/school/queries";
import { cn } from "@/lib/utils";
import { PostComposer } from "./composer";
import { FeedColumns } from "./feed-layout";
import { FeedList } from "./feed-list";
import { FeedTabs } from "./feed-tabs";
import { MyPosts } from "./my-posts";
import { PinnedAnnouncement } from "./pinned-announcement";
import { ClassInfoCard, RecentAchievementsCard, UpcomingCard } from "./rail";
import { StoryStrip } from "./story-strip";
import { useCapabilities } from "./use-feed";
import { ApiRequestError } from "@/lib/api-error";

/**
 * The class feed: the tabs Feed | Photos | Activities | Achievements (server-side filters), the pinned announcement and
 * the stories on top of the Feed tab, then the posts (pinned first, newest first), and a rail with the class, the next
 * events and the latest achievements. Teachers and managers post; the API decides, and the buttons follow
 * `feed/capabilities`. A school that switched the feed off gets one explanation.
 */
export function ClassFeedTab({ cls }: { cls: ClassView }) {
  const t = useTranslations("school.feed.classTab");
  const tr = useTranslations("school.feed.redesign");
  const { school, permissions, isTeacher, isStaff } = useSchool();
  const caps = useCapabilities(school.id, cls.id);
  const [composing, setComposing] = useState(false);
  const [view, setView] = useState<"feed" | "mine">("feed");
  const [tab, setTab] = useState<FeedTab>(DEFAULT_TAB);

  if (caps.isError) {
    const disabled = caps.error instanceof ApiRequestError && caps.error.problem?.code === "feed.feature_disabled";
    return disabled ? <EmptyNotice>{errorMessage(caps.error, t("disabled"))}</EmptyNotice> : <ErrorNotice message={errorMessage(caps.error, t("loadFailed"))} onRetry={() => void caps.refetch()} />;
  }
  const canPost = !!caps.data?.canPost && cls.status === "ACTIVE";
  const approvals = canOpenApprovals({ permissions, isTeacher, isStaff, isStudent: false });
  const showTab = (next: FeedTab) => {
    setView("feed");
    setTab(next);
  };

  const rail = (
    <>
      <TeacherClassInfo cls={cls} />
      <UpcomingCard schoolId={school.id} studentId={null} calendarHref={`/s/${encodeURIComponent(school.slug)}/calendar`} />
      <RecentAchievementsCard
        queryKey={fk.recentClass(school.id, cls.id)}
        fetchRecent={async () => (await fetchClassFeed(school.id, cls.id, undefined, ACHIEVEMENTS_FILTER, RECENT_ACHIEVEMENTS)).items}
        onViewAll={() => showTab("achievements")}
      />
    </>
  );

  return (
    <FeedColumns rail={rail} railLabel={tr("rail.label")}>
      <div className="flex flex-wrap items-center gap-2">
        <div role="group" aria-label={t("view")} className="me-auto flex gap-2">
          {(["feed", "mine"] as const).map((v) =>
            v === "mine" && !canPost ? null : (
              <button
                key={v}
                type="button"
                aria-pressed={view === v}
                onClick={() => setView(v)}
                className={cn("min-h-11 rounded-full border px-4 text-sm font-medium", view === v ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent")}
                data-testid={`feed-view-${v}`}
              >
                {t(v)}
              </button>
            ),
          )}
        </div>
        {approvals ? (
          <Button asChild variant="outline" className="min-h-11 rounded-full">
            <Link href={`/s/${encodeURIComponent(school.slug)}/feed/approvals`} data-testid="approvals-link">
              <ClipboardCheckIcon aria-hidden />
              {t("approvals")}
            </Link>
          </Button>
        ) : null}
        {canPost ? (
          <Button onClick={() => setComposing(true)} data-testid="new-post" className="min-h-11 rounded-full">
            <PlusIcon aria-hidden />
            {t("newPost")}
          </Button>
        ) : null}
      </div>

      {view === "feed" ? (
        <FeedTabs value={tab} onValueChange={setTab}>
          {tab === "feed" ? (
            <>
              <PinnedAnnouncement schoolId={school.id} hrefFor={(card) => `/s/${encodeURIComponent(school.slug)}/announcements/${card.id}`} />
              <StoryStrip
                queryKey={fk.classStories(school.id, cls.id)}
                fetchStories={() => fetchClassStories(school.id, cls.id)}
                create={canPost ? { schoolId: school.id, classId: cls.id } : undefined}
              />
            </>
          ) : null}
          <FeedList
            key={tab}
            queryKey={fk.classFeed(school.id, cls.id, tab)}
            fetchPage={(cursor) => fetchClassFeed(school.id, cls.id, cursor, tabFilter(tab))}
            empty={tab === "feed" ? (canPost ? t("emptyPost") : t("empty")) : tr(`empty.${tab}`)}
          />
        </FeedTabs>
      ) : (
        <MyPosts schoolId={school.id} classId={cls.id} />
      )}
      {composing ? <PostComposer schoolId={school.id} classId={cls.id} onClose={() => setComposing(false)} /> : null}
    </FeedColumns>
  );
}

/** The class card of the rail for staff: the counts the class view carries and the teachers the API lists for this person. */
function TeacherClassInfo({ cls }: { cls: ClassView }) {
  const { school } = useSchool();
  const teachers = useQuery({ queryKey: sk.classTeachers(school.id, cls.id), queryFn: () => fetchClassTeachers(school.id, cls.id), staleTime: 60_000, retry: false });
  return (
    <ClassInfoCard
      name={cls.name}
      schoolId={school.id}
      teachers={(teachers.data ?? []).map((teacher) => ({ id: teacher.teacherId, name: teacher.displayName, role: teacher.role, avatarId: teacher.avatar?.id }))}
      studentCount={cls.studentCount}
      teacherCount={cls.teacherCount}
    />
  );
}
