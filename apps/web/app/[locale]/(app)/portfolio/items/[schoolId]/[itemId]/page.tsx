import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { LinkedShell } from "@/components/feed/linked-shell";
import { ItemDetail } from "@/components/portfolio/item-detail";
import { isUuid } from "@/lib/feed/ids";
import { requireSession } from "@/lib/server/dal";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("portfolio") };
}

/** One portfolio item (a notification, a student's list or a guardian's list opens it). Only ids are in the path. */
export default function PortfolioItemPage({ params }: PageProps<"/[locale]/portfolio/items/[schoolId]/[itemId]">) {
  return (
    <LinkedShell>
      <Suspense fallback={null}>
        <Item params={params} />
      </Suspense>
    </LinkedShell>
  );
}

async function Item({ params }: { params: PageProps<"/[locale]/portfolio/items/[schoolId]/[itemId]">["params"] }) {
  const { schoolId, itemId } = await params;
  // Both ids go into API paths: only well-formed ids pass.
  if (!isUuid(schoolId) || !isUuid(itemId)) notFound();
  const me = await requireSession();
  const self = me.memberships.find((m) => m.type === "STUDENT" && m.schoolId === schoolId)?.studentId;
  return <ItemDetail schoolId={schoolId} itemId={itemId} selfStudentId={self ?? undefined} />;
}
