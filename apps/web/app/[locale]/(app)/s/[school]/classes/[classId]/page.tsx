import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { ClassDetail } from "@/components/school/class-detail";
import { Skeleton } from "@/components/ui/skeleton";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("classes") };
}

export default function ClassDetailPage({ params }: PageProps<"/[locale]/s/[school]/classes/[classId]">) {
  // `classId` is runtime data, so it is read inside Suspense (cacheComponents).
  return (
    <Suspense fallback={<Skeleton className="h-96 rounded-2xl" />}>
      <Detail params={params} />
    </Suspense>
  );
}

async function Detail({ params }: { params: PageProps<"/[locale]/s/[school]/classes/[classId]">["params"] }) {
  const { classId } = await params;
  return <ClassDetail classId={classId} />;
}
