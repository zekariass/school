import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { ComposerView } from "@/components/announcements/composer-view";
import { Skeleton } from "@/components/ui/skeleton";
import { isUuid } from "@/lib/feed/ids";

export async function generateMetadata() {
  const t = await getTranslations("school.announcements.composer");
  return { title: t("editTitle") };
}

export default function EditAnnouncementPage({ params }: PageProps<"/[locale]/s/[school]/announcements/[announcementId]/edit">) {
  return (
    <Suspense fallback={<Skeleton className="h-96 rounded-2xl" />}>
      <Edit params={params} />
    </Suspense>
  );
}

async function Edit({ params }: { params: PageProps<"/[locale]/s/[school]/announcements/[announcementId]/edit">["params"] }) {
  const { announcementId } = await params;
  if (!isUuid(announcementId)) notFound();
  return <ComposerView announcementId={announcementId} />;
}
