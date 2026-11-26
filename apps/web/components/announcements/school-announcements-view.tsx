"use client";

import { InboxIcon, PlusIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { PageHeader } from "@/components/school/page-header";
import { useSchool } from "@/components/school/school-context";
import { Button } from "@/components/ui/button";
import { Link } from "@/i18n/navigation";
import { ank, fetchAuthored, fetchSchoolList, type AnnouncementCard } from "@/lib/announcements/queries";
import { AUTHORED_TABS, authoredStatus, schoolTabs, type AuthoredTab, type SchoolTab } from "@/lib/announcements/view";
import { canManageAnnouncements } from "@/lib/school/access";
import { cn } from "@/lib/utils";
import { AnnouncementList } from "./announcement-list";
import { useAnnouncementCapabilities } from "./use-announcements";

/**
 * `/s/{slug}/announcements`: the school's announcements the caller may read, the ones they wrote ("Mine", by status) and,
 * for managers, everything written in the school. Writing is offered when the API's capabilities allow it.
 */
export function SchoolAnnouncementsView() {
  const t = useTranslations("school.announcements");
  const { school, permissions } = useSchool();
  const caps = useAnnouncementCapabilities(school.id);
  const manager = canManageAnnouncements({ permissions });
  const tabs = schoolTabs({ canCompose: !!caps.data?.canCompose, manager });
  const [tab, setTab] = useState<SchoolTab>("school");
  const [status, setStatus] = useState<AuthoredTab>("ALL");
  const base = `/s/${encodeURIComponent(school.slug)}/announcements`;
  const activeTab = tabs.includes(tab) ? tab : "school";
  const hrefFor = (card: AnnouncementCard) => `${base}/${card.id}`;

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <PageHeader
        title={t("title")}
        description={t("subtitle")}
        actions={
          <>
            <Button asChild variant="outline">
              <Link href="/announcements" data-testid="inbox-link">
                <InboxIcon aria-hidden />
                {t("inbox")}
              </Link>
            </Button>
            {caps.data?.canCompose ? (
              <Button asChild>
                <Link href={`${base}/new`} data-testid="new-announcement">
                  <PlusIcon aria-hidden />
                  {t("new")}
                </Link>
              </Button>
            ) : null}
          </>
        }
      />

      {tabs.length > 1 ? (
        <div role="group" aria-label={t("tabsLabel")} className="flex flex-wrap gap-2">
          {tabs.map((value) => (
            <button
              key={value}
              type="button"
              aria-pressed={activeTab === value}
              onClick={() => setTab(value)}
              data-testid={`tab-${value}`}
              className={cn("min-h-11 rounded-full border px-4 text-sm font-semibold", activeTab === value ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent")}
            >
              {t(`tabs.${value}`)}
            </button>
          ))}
        </div>
      ) : null}

      {activeTab === "school" ? (
        <AnnouncementList
          key="school"
          queryKey={ank.schoolList(school.id, "ALL", "NEWEST")}
          fetchPage={(cursor) => fetchSchoolList(school.id, "ALL", "NEWEST", cursor)}
          hrefFor={hrefFor}
          mode="reader"
          empty={t("empty.school")}
          failed={t("loadFailed")}
        />
      ) : (
        <>
          <div role="group" aria-label={t("tabsLabel")} className="flex flex-wrap gap-2">
            {AUTHORED_TABS.map((value) => (
              <button
                key={value}
                type="button"
                aria-pressed={status === value}
                onClick={() => setStatus(value)}
                data-testid={`status-${value}`}
                className={cn("min-h-10 rounded-full border px-3.5 text-sm font-medium", status === value ? "border-primary bg-primary/10 font-semibold" : "hover:bg-accent")}
              >
                {t(`statusTabs.${value}`)}
              </button>
            ))}
          </div>
          <AnnouncementList
            key={`${activeTab}:${status}`}
            queryKey={ank.authored(school.id, status, activeTab === "mine")}
            fetchPage={(cursor) => fetchAuthored(school.id, authoredStatus(status), activeTab === "mine", cursor)}
            hrefFor={hrefFor}
            mode="author"
            empty={t(`empty.${activeTab === "all" ? "all" : "mine"}`)}
            failed={t("loadFailed")}
          />
        </>
      )}
    </div>
  );
}
