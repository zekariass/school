import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { WorkspaceAnnouncement } from "@/components/announcements/workspace-detail";
import { Skeleton } from "@/components/ui/skeleton";
import { isUuid } from "@/lib/feed/ids";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("announcements") };
}

export default function AnnouncementPage({ params }: PageProps<"/[locale]/s/[school]/announcements/[announcementId]">) {
  // `announcementId` is runtime data: read inside Suspense (cacheComponents).
  return (
    <Suspense fallback={<Skeleton className="h-96 rounded-2xl" />}>
      <Detail params={params} />
    </Suspense>
  );
}

async function Detail({ params }: { params: PageProps<"/[locale]/s/[school]/announcements/[announcementId]">["params"] }) {
  const { announcementId } = await params;
  if (!isUuid(announcementId)) notFound();
  return <WorkspaceAnnouncement announcementId={announcementId} />;
}
