import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { AnnouncementDetail } from "@/components/announcements/announcement-detail";
import { AnnouncementsShell } from "@/components/announcements/announcements-shell";
import { Skeleton } from "@/components/ui/skeleton";
import { isUuid } from "@/lib/feed/ids";
import { schoolSlugOf } from "@/lib/notifications/deep-link";

export async function generateMetadata() {
  const t = await getTranslations("family.announcements");
  return { title: t("detailTitle") };
}

type Params = PageProps<"/[locale]/announcements/[schoolId]/[announcementId]">["params"];

/** One announcement opened from the inbox or from a notification (`/announcements/{school}/{announcement}`). */
export default function AnnouncementPage({ params }: PageProps<"/[locale]/announcements/[schoolId]/[announcementId]">) {
  return (
    <AnnouncementsShell>
      {(me) => (
        <Suspense fallback={<Skeleton className="h-64 rounded-2xl" />}>
          <Opened params={params} me={me} />
        </Suspense>
      )}
    </AnnouncementsShell>
  );
}

async function Opened({ params, me }: { params: Params; me: Parameters<typeof schoolSlugOf>[0] & { id: string } }) {
  const { schoolId, announcementId } = await params;
  const back = await getTranslations("family.announcements");
  // Both ids go into API paths: only well-formed ids pass.
  if (!isUuid(schoolId) || !isUuid(announcementId)) notFound();
  return (
    <AnnouncementDetail
      schoolId={schoolId}
      announcementId={announcementId}
      viewerId={me.id}
      backHref="/announcements"
      backLabel={back("backToList")}
      workspaceSlug={schoolSlugOf(me, schoolId)}
      inWorkspace={false}
    />
  );
}
