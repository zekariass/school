import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { ChildDetail } from "@/components/family/child-detail";
import { FamilyShell } from "@/components/family/family-shell";
import { Skeleton } from "@/components/ui/skeleton";

export async function generateMetadata() {
  const t = await getTranslations("family.points");
  return { title: t("childTitle") };
}

export default function ChildPage({ params }: PageProps<"/[locale]/family/children/[studentId]">) {
  // `studentId` is runtime data, so it is read inside Suspense (cacheComponents).
  return (
    <FamilyShell>
      <Suspense fallback={<Skeleton className="h-96 rounded-2xl" />}>
        <Child params={params} />
      </Suspense>
    </FamilyShell>
  );
}

async function Child({ params }: { params: PageProps<"/[locale]/family/children/[studentId]">["params"] }) {
  const { studentId } = await params;
  return <ChildDetail studentId={studentId} />;
}
