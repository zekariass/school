"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import { SelectField } from "@/components/school/fields";
import { ank, fetchInbox, type AnnouncementCard } from "@/lib/announcements/queries";
import { INBOX_FILTERS, INBOX_SORTS, acknowledgementCount, badgeCount, type InboxFilter, type InboxSort } from "@/lib/announcements/view";
import { cn } from "@/lib/utils";
import { AnnouncementList } from "./announcement-list";
import { useAnnouncementSummary } from "./use-announcements";

/**
 * `/announcements`: what the school sent to the signed-in person (guardians, students and staff alike), with unread
 * and needs-acknowledgement filters. Server-filtered and keyset paged; the lists live in memory only.
 */
export function InboxView() {
  const t = useTranslations("family.announcements");
  const [filter, setFilter] = useState<InboxFilter>("ALL");
  const [sort, setSort] = useState<InboxSort>("NEWEST");
  const summary = useAnnouncementSummary();
  const unread = badgeCount(summary.data);
  const ack = acknowledgementCount(summary.data);
  const hrefFor = (card: AnnouncementCard) => `/announcements/${card.schoolId}/${card.id}`;

  return (
    <div className="flex flex-col gap-5">
      <header className="flex flex-col gap-1">
        <h1 className="text-3xl font-bold">{t("title")}</h1>
        <p className="text-muted-foreground">{t("subtitle")}</p>
      </header>

      <div className="flex flex-wrap items-end justify-between gap-3">
        <div role="group" aria-label={t("filter")} className="flex flex-wrap gap-2">
          {INBOX_FILTERS.map((value) => {
            const count = value === "UNREAD" ? unread : value === "NEEDS_ACKNOWLEDGEMENT" ? ack : 0;
            return (
              <button
                key={value}
                type="button"
                aria-pressed={filter === value}
                onClick={() => setFilter(value)}
                data-testid={`inbox-filter-${value}`}
                className={cn(
                  "inline-flex min-h-11 items-center gap-2 rounded-full border px-4 text-sm font-semibold",
                  filter === value ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent",
                )}
              >
                {t(`filters.${value}`)}
                {count > 0 ? (
                  <span className={cn("grid min-w-5 place-items-center rounded-full px-1.5 text-xs font-bold", filter === value ? "bg-primary-foreground text-primary" : "bg-destructive text-white")}>
                    {count > 99 ? "99+" : count}
                  </span>
                ) : null}
              </button>
            );
          })}
        </div>
        <SelectField
          label={t("sortLabel")}
          value={sort}
          onChange={(value) => setSort(value === "UNREAD_FIRST" ? "UNREAD_FIRST" : "NEWEST")}
          options={INBOX_SORTS.map((value) => ({ value, label: t(`sorts.${value}`) }))}
          className="w-48"
        />
      </div>

      <AnnouncementList
        key={`${filter}:${sort}`}
        queryKey={ank.inbox(null, filter, sort)}
        fetchPage={(cursor) => fetchInbox(null, filter, sort, cursor)}
        hrefFor={hrefFor}
        mode="reader"
        showSchool
        empty={t(`empty.${filter}`)}
        failed={t("loadFailed")}
      />
    </div>
  );
}
