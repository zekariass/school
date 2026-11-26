import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { ReportView } from "@/components/announcements/report-view";
import { Skeleton } from "@/components/ui/skeleton";
import { isUuid } from "@/lib/feed/ids";

export async function generateMetadata() {
  const t = await getTranslations("school.announcements.report");
  return { title: t("title") };
}

export default function AnnouncementReportPage({ params }: PageProps<"/[locale]/s/[school]/announcements/[announcementId]/report">) {
  return (
    <Suspense fallback={<Skeleton className="h-96 rounded-2xl" />}>
      <Report params={params} />
    </Suspense>
  );
}

async function Report({ params }: { params: PageProps<"/[locale]/s/[school]/announcements/[announcementId]/report">["params"] }) {
  const { announcementId } = await params;
  if (!isUuid(announcementId)) notFound();
  return <ReportView announcementId={announcementId} />;
}
