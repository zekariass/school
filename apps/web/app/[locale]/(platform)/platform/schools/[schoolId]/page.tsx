import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { SchoolDetail } from "@/components/console/school-detail";
import { Skeleton } from "@/components/ui/skeleton";

export async function generateMetadata() {
  const t = await getTranslations("console.nav");
  return { title: t("schools") };
}

export default function SchoolDetailPage({ params }: PageProps<"/[locale]/platform/schools/[schoolId]">) {
  // `schoolId` is runtime data, so it is read inside Suspense (cacheComponents).
  return (
    <Suspense fallback={<Skeleton className="h-96 rounded-2xl" />}>
      <Detail params={params} />
    </Suspense>
  );
}

async function Detail({ params }: { params: PageProps<"/[locale]/platform/schools/[schoolId]">["params"] }) {
  const { schoolId } = await params;
  return <SchoolDetail schoolId={schoolId} />;
}
