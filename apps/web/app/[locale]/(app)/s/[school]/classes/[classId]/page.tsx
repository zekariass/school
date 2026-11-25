import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { ClassDetail } from "@/components/school/class-detail";
import { Skeleton } from "@/components/ui/skeleton";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("classes") };
}

export default function ClassDetailPage({ params, searchParams }: PageProps<"/[locale]/s/[school]/classes/[classId]">) {
  // `classId` and `?tab=` are runtime data, so they are read inside Suspense (cacheComponents).
  return (
    <Suspense fallback={<Skeleton className="h-96 rounded-2xl" />}>
      <Detail params={params} searchParams={searchParams} />
    </Suspense>
  );
}

async function Detail({
  params,
  searchParams,
}: {
  params: PageProps<"/[locale]/s/[school]/classes/[classId]">["params"];
  searchParams: PageProps<"/[locale]/s/[school]/classes/[classId]">["searchParams"];
}) {
  const [{ classId }, query] = await Promise.all([params, searchParams]);
  const first = (value: string | string[] | undefined) => (typeof value === "string" ? value : undefined);
  return <ClassDetail classId={classId} initialTab={first(query.tab)} initialDate={first(query.date)} initialSlot={first(query.slot)} />;
}
