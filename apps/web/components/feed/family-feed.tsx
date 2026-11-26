"use client";

import { useQueries } from "@tanstack/react-query";
import { HandHeartIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { classTargets, filterableChildren } from "@/lib/feed/family";
import { fetchCapabilities, fetchMyFeed, fetchMyStories, fk, type FeedCapabilities } from "@/lib/feed/queries";
import type { MyChild } from "@/lib/family/queries";
import { cn } from "@/lib/utils";
import { ContributionDialog } from "./contribution-dialog";
import { FeedList } from "./feed-list";
import { MyPosts } from "./my-posts";
import { StoryStrip } from "./story-strip";

/**
 * The guardian's home feed (`GET /me/feed`): posts of the classes their children are in and of their schools,
 * newest first, with a filter per child, the stories and, when the school allows it, a way to share a photo.
 * Everything shown here was filtered by the API for this guardian's links; the page adds nothing.
 */
export function FamilyFeed({ kids }: { kids: readonly MyChild[] }) {
  const t = useTranslations("family.feed");
  const visible = filterableChildren(kids);
  const [studentId, setStudentId] = useState<string | null>(null);
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

  return (
    <section aria-labelledby="family-feed-title" className="flex flex-col gap-4" data-testid="family-feed">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="family-feed-title" className="text-2xl font-bold">
          {t("title")}
        </h2>
        {canContribute ? (
          <Button onClick={() => setContributing(true)} data-testid="contribute">
            <HandHeartIcon aria-hidden />
            {t("contributeButton")}
          </Button>
        ) : null}
      </div>

      {filter ? (
        <div role="group" aria-label={t("filter")} className="flex flex-wrap gap-2">
          <FilterChip active={selected === null} onClick={() => setStudentId(null)}>
            {t("allChildren")}
          </FilterChip>
          {visible.map((child) => (
            <FilterChip key={child.studentId} active={selected === child.studentId} onClick={() => setStudentId(child.studentId)} testId={`filter-${child.studentId}`}>
              {child.givenName || child.displayName}
            </FilterChip>
          ))}
        </div>
      ) : null}

      {schoolsWithContributions.length > 0 ? (
        <div role="group" aria-label={t("view")} className="flex gap-2">
          <FilterChip active={view === "feed"} onClick={() => setView("feed")}>
            {t("feed")}
          </FilterChip>
          <FilterChip active={view === "mine"} onClick={() => setView("mine")} testId="my-contributions">
            {t("mine")}
          </FilterChip>
        </div>
      ) : null}

      {view === "feed" ? (
        <>
          <StoryStrip queryKey={fk.myStories(selected)} fetchStories={() => fetchMyStories(selected)} />
          <FeedList
            key={selected ?? "all"}
            queryKey={fk.myFeed(selected)}
            fetchPage={(cursor) => fetchMyFeed(selected, cursor)}
            empty={t("empty")}
            canEdit={false}
          />
        </>
      ) : (
        <div className="flex flex-col gap-6">
          {schoolsWithContributions.map((schoolId) => (
            <MyPosts key={schoolId} schoolId={schoolId} initialStatus="PENDING_APPROVAL" canEdit={false} />
          ))}
        </div>
      )}
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
      className={cn("min-h-10 rounded-full border px-4 text-sm font-medium", active ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent")}
    >
      {children}
    </button>
  );
}

/** A student's own feed (the API answers `GET /me/feed` for them): read, with reactions and comments only where the API allows. */
export function StudentFeed() {
  const t = useTranslations("family.feed");
  return (
    <section aria-labelledby="student-feed-title" className="flex flex-col gap-4" data-testid="student-feed">
      <h2 id="student-feed-title" className="text-2xl font-bold">
        {t("studentTitle")}
      </h2>
      <StoryStrip queryKey={fk.myStories(null)} fetchStories={() => fetchMyStories(null)} />
      <FeedList queryKey={fk.myFeed(null)} fetchPage={(cursor) => fetchMyFeed(null, cursor)} empty={t("studentEmpty")} canEdit={false} />
    </section>
  );
}
