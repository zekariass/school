"use client";

import { useQueries } from "@tanstack/react-query";
import { HandHeartIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { bannerOf, classCards, classTargets, filterableChildren } from "@/lib/feed/family";
import { fetchCapabilities, fetchMyFeed, fetchMyStories, fk, type FeedCapabilities } from "@/lib/feed/queries";
import { ACHIEVEMENTS_FILTER, DEFAULT_TAB, RECENT_ACHIEVEMENTS, tabFilter, type FeedTab } from "@/lib/feed/tabs";
import type { MyChild } from "@/lib/family/queries";
import { cn } from "@/lib/utils";
import { ChildrenNav } from "./children-nav";
import { ContributionDialog } from "./contribution-dialog";
import { FeedBanner } from "./feed-banner";
import { FeedColumns } from "./feed-layout";
import { FeedList } from "./feed-list";
import { FeedTabs } from "./feed-tabs";
import { MyPosts } from "./my-posts";
import { PinnedAnnouncement } from "./pinned-announcement";
import { ClassInfoCard, RecentAchievementsCard, UpcomingCard } from "./rail";
import { StoryStrip } from "./story-strip";

/** At most this many class cards in the rail (siblings in different classes); the rest are in the children list. */
const MAX_CLASS_CARDS = 2;

/**
 * The guardian's home feed (`GET /me/feed`) in three columns: the children list (choosing one narrows the feed with
 * `studentId`), the class banner, the tabs Feed | Photos | Activities | Achievements (server-side filters), the pinned
 * announcement, the stories and the posts, and a rail with the class, the next events and the latest achievements.
 * Everything shown here was filtered by the API for this guardian's links; the page adds nothing.
 */
export function FamilyFeed({ kids }: { kids: readonly MyChild[] }) {
  const t = useTranslations("family.feed");
  const tr = useTranslations("school.feed.redesign");
  const visible = filterableChildren(kids);
  const [studentId, setStudentId] = useState<string | null>(null);
  const [tab, setTab] = useState<FeedTab>(DEFAULT_TAB);
  const [view, setView] = useState<"feed" | "mine">("feed");
  const [contributing, setContributing] = useState(false);
  const targets = useMemo(() => classTargets(kids), [kids]);

  const capabilityQueries = useQueries({
    queries: targets.map((target) => ({
      queryKey: fk.capabilities(target.schoolId, target.classId),
      queryFn: () => fetchCapabilities(target.schoolId, target.classId),
      staleTime: 60_000,
      retry: false,
    })),
  });
  const capabilities: Record<string, FeedCapabilities | undefined> = {};
  targets.forEach((target, i) => {
    capabilities[target.classId] = capabilityQueries[i]?.data;
  });
  const canContribute = targets.some((target) => capabilities[target.classId]?.canContribute);
  const schoolsWithContributions = [...new Set(targets.filter((target) => capabilities[target.classId]?.canContribute).map((target) => target.schoolId))];

  if (visible.length === 0) return null;
  // Guardians of one child do not need a filter.
  const filter = visible.length > 1;
  const selected = filter ? studentId : null;
  const shown = selected ? visible.filter((child) => child.studentId === selected) : visible;
  const cards = classCards(shown);
  const banner = bannerOf(cards, selected !== null);
  const bannerTitle = banner.title ?? tr("banner.allChildren");
  const showTab = (next: FeedTab) => {
    setView("feed");
    setTab(next);
  };

  const rail = (
    <>
      {cards.slice(0, MAX_CLASS_CARDS).map((card) => (
        <ClassInfoCard
          key={card.classId}
          name={card.name}
          schoolId={card.schoolId}
          teachers={card.teachers.map((teacher) => ({ id: teacher.teacherId, name: teacher.displayName, role: teacher.role }))}
          allowMessage
        />
      ))}
      <UpcomingCard schoolId={null} studentId={selected} calendarHref="/calendar" />
      <RecentAchievementsCard
        queryKey={fk.recentMine(selected)}
        fetchRecent={async () => (await fetchMyFeed(selected, undefined, ACHIEVEMENTS_FILTER, RECENT_ACHIEVEMENTS)).items}
        onViewAll={() => showTab("achievements")}
      />
    </>
  );

  return (
    <section aria-labelledby="family-feed-title" data-testid="family-feed">
      <FeedColumns left={<ChildrenNav kids={visible} selected={selected} onSelect={setStudentId} />} rail={rail} railLabel={tr("rail.label")}>
        <FeedBanner title={bannerTitle} subtitle={banner.subtitle ?? tr("banner.familySubtitle")} icon={banner.icon} titleId="family-feed-title" />

        {schoolsWithContributions.length > 0 || canContribute ? (
          <div className="flex flex-wrap items-center gap-2">
            {schoolsWithContributions.length > 0 ? (
              <div role="group" aria-label={t("view")} className="me-auto flex gap-2">
                <FilterChip active={view === "feed"} onClick={() => setView("feed")}>
                  {t("feed")}
                </FilterChip>
                <FilterChip active={view === "mine"} onClick={() => setView("mine")} testId="my-contributions">
                  {t("mine")}
                </FilterChip>
              </div>
            ) : null}
            {canContribute ? (
              <Button onClick={() => setContributing(true)} data-testid="contribute" className="min-h-11 rounded-full">
                <HandHeartIcon aria-hidden />
                {t("contributeButton")}
              </Button>
            ) : null}
          </div>
        ) : null}

        {view === "feed" ? (
          <FeedTabs value={tab} onValueChange={setTab}>
            {tab === "feed" ? (
              <>
                <PinnedAnnouncement schoolId={null} hrefFor={(card) => `/announcements/${card.schoolId}/${card.id}`} />
                <StoryStrip queryKey={fk.myStories(selected)} fetchStories={() => fetchMyStories(selected)} />
              </>
            ) : null}
            <FeedList
              key={`${selected ?? "all"}-${tab}`}
              queryKey={fk.myFeed(selected, tab)}
              fetchPage={(cursor) => fetchMyFeed(selected, cursor, tabFilter(tab))}
              empty={tab === "feed" ? t("empty") : tr(`empty.${tab}`)}
              canEdit={false}
            />
          </FeedTabs>
        ) : (
          <div className="flex flex-col gap-6">
            {schoolsWithContributions.map((schoolId) => (
              <MyPosts key={schoolId} schoolId={schoolId} initialStatus="PENDING_APPROVAL" canEdit={false} />
            ))}
          </div>
        )}
      </FeedColumns>
      {contributing ? <ContributionDialog targets={targets} capabilities={capabilities} onClose={() => setContributing(false)} /> : null}
    </section>
  );
}

function FilterChip({ active, onClick, children, testId }: { active: boolean; onClick: () => void; children: React.ReactNode; testId?: string }) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      data-testid={testId}
      className={cn("min-h-11 rounded-full border px-4 text-sm font-medium", active ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent")}
    >
      {children}
    </button>
  );
}

/** A student's own feed (the API answers `GET /me/feed` for them): read, with reactions and comments only where the API allows. */
export function StudentFeed() {
  const t = useTranslations("family.feed");
  const tr = useTranslations("school.feed.redesign");
  const [tab, setTab] = useState<FeedTab>(DEFAULT_TAB);

  const rail = (
    <>
      <UpcomingCard schoolId={null} studentId={null} calendarHref="/calendar" />
      <RecentAchievementsCard
        queryKey={fk.recentMine(null)}
        fetchRecent={async () => (await fetchMyFeed(null, undefined, ACHIEVEMENTS_FILTER, RECENT_ACHIEVEMENTS)).items}
        onViewAll={() => setTab("achievements")}
      />
    </>
  );

  return (
    <section aria-labelledby="student-feed-title" data-testid="student-feed">
      <FeedColumns rail={rail} railLabel={tr("rail.label")}>
        <FeedBanner title={tr("banner.studentTitle")} titleId="student-feed-title" />
        <FeedTabs value={tab} onValueChange={setTab}>
          {tab === "feed" ? (
            <>
              <PinnedAnnouncement schoolId={null} hrefFor={(card) => `/announcements/${card.schoolId}/${card.id}`} />
              <StoryStrip queryKey={fk.myStories(null)} fetchStories={() => fetchMyStories(null)} />
            </>
          ) : null}
          <FeedList
            key={tab}
            queryKey={fk.myFeed(null, tab)}
            fetchPage={(cursor) => fetchMyFeed(null, cursor, tabFilter(tab))}
            empty={tab === "feed" ? t("studentEmpty") : tr(`empty.${tab}`)}
            canEdit={false}
          />
        </FeedTabs>
      </FeedColumns>
    </section>
  );
}
