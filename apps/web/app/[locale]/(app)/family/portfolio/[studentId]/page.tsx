import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { FamilyShell } from "@/components/family/family-shell";
import { FamilyPortfolio } from "@/components/portfolio/family-portfolio";
import { Skeleton } from "@/components/ui/skeleton";
import { isUuid } from "@/lib/feed/ids";

export async function generateMetadata() {
  const t = await getTranslations("family.portfolio");
  return { title: t("title") };
}

/** One child's portfolio items. `studentId` is runtime data, so it is read inside Suspense (cacheComponents). */
export default function ChildPortfolioPage({ params }: PageProps<"/[locale]/family/portfolio/[studentId]">) {
  return (
    <FamilyShell>
      <Suspense fallback={<Skeleton className="h-64 rounded-2xl" />}>
        <Child params={params} />
      </Suspense>
    </FamilyShell>
  );
}

async function Child({ params }: { params: PageProps<"/[locale]/family/portfolio/[studentId]">["params"] }) {
  const { studentId } = await params;
  if (!isUuid(studentId)) notFound();
  return <FamilyPortfolio initialStudentId={studentId} />;
}
