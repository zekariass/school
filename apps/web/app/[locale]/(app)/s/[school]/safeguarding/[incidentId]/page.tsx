import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { IncidentView } from "@/components/safeguarding/incident-view";
import { SchoolModerationScope } from "@/components/moderation/scope";
import { Skeleton } from "@/components/ui/skeleton";
import { isUuid } from "@/lib/feed/ids";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  // The document title never carries anything about the case or incident.
  return { title: t("safeguarding") };
}

/** `incidentId` is runtime data, read inside Suspense. */
export default function SchoolIncidentPage({ params }: PageProps<"/[locale]/s/[school]/safeguarding/[incidentId]">) {
  return (
    <Suspense fallback={<Skeleton className="h-96 rounded-2xl" />}>
      <Detail params={params} />
    </Suspense>
  );
}

async function Detail({ params }: { params: PageProps<"/[locale]/s/[school]/safeguarding/[incidentId]">["params"] }) {
  const { incidentId } = await params;
  if (!isUuid(incidentId)) notFound();
  return (
    <SchoolModerationScope>
      <IncidentView incidentId={incidentId} />
    </SchoolModerationScope>
  );
}
