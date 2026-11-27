import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { CaseView } from "@/components/moderation/case-view";
import { PlatformModerationScope } from "@/components/moderation/scope";
import { Skeleton } from "@/components/ui/skeleton";
import { isUuid } from "@/lib/feed/ids";

export async function generateMetadata() {
  const t = await getTranslations("console.nav");
  // The document title never carries anything about the case or incident.
  return { title: t("moderation") };
}

/** `caseId` is runtime data, read inside Suspense. */
export default function PlatformCasePage({ params }: PageProps<"/[locale]/platform/moderation/[caseId]">) {
  return (
    <Suspense fallback={<Skeleton className="h-96 rounded-2xl" />}>
      <Detail params={params} />
    </Suspense>
  );
}

async function Detail({ params }: { params: PageProps<"/[locale]/platform/moderation/[caseId]">["params"] }) {
  const { caseId } = await params;
  if (!isUuid(caseId)) notFound();
  return (
    <PlatformModerationScope>
      <CaseView caseId={caseId} />
    </PlatformModerationScope>
  );
}
