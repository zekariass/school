"use client";

import { useTranslations } from "next-intl";
import { useSchool } from "@/components/school/school-context";
import { AnnouncementDetail } from "./announcement-detail";

/** An announcement inside the school workspace: with the author's and managers' controls. */
export function WorkspaceAnnouncement({ announcementId }: { announcementId: string }) {
  const t = useTranslations("school.announcements.detail");
  const { school, userId } = useSchool();
  return (
    <div className="mx-auto max-w-3xl">
      <AnnouncementDetail
        schoolId={school.id}
        announcementId={announcementId}
        viewerId={userId}
        backHref={`/s/${encodeURIComponent(school.slug)}/announcements`}
        backLabel={t("back")}
        workspaceSlug={school.slug}
        inWorkspace
      />
    </div>
  );
}
