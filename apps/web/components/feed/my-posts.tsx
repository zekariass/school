"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import { fetchMyPosts, fk } from "@/lib/feed/queries";
import { MY_POST_TABS } from "@/lib/feed/view";
import { cn } from "@/lib/utils";
import { FeedList } from "./feed-list";

/** The author's own posts by status: drafts, scheduled, waiting for approval, rejected (with the reason), published, taken down. */
export function MyPosts({ schoolId, classId, initialStatus = "DRAFT", canEdit = true }: { schoolId: string; classId?: string; initialStatus?: (typeof MY_POST_TABS)[number]; canEdit?: boolean }) {
  const t = useTranslations("school.feed.mine");
  const [status, setStatus] = useState<(typeof MY_POST_TABS)[number]>(initialStatus);
  return (
    <div className="flex flex-col gap-4" data-testid="my-posts">
      <div role="group" aria-label={t("filter")} className="flex flex-wrap gap-2">
        {MY_POST_TABS.map((tab) => (
          <button
            key={tab}
            type="button"
            aria-pressed={status === tab}
            onClick={() => setStatus(tab)}
            className={cn("min-h-10 rounded-full border px-4 text-sm font-medium", status === tab ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent")}
            data-testid={`mine-${tab}`}
          >
            {t(`tabs.${tab}`)}
          </button>
        ))}
      </div>
      <FeedList
        key={status}
        queryKey={fk.myPosts(schoolId, status, classId)}
        fetchPage={(cursor) => fetchMyPosts(schoolId, status, classId, cursor)}
        empty={t(`empty.${status}`)}
        canEdit={canEdit}
      />
    </div>
  );
}
