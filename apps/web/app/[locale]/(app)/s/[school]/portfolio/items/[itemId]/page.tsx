import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { WorkspaceItem } from "@/components/portfolio/workspace-item";
import { Skeleton } from "@/components/ui/skeleton";
import { isUuid } from "@/lib/feed/ids";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("portfolio") };
}

/** One portfolio item inside the school workspace (teachers and administrators). `itemId` is runtime data, read inside Suspense. */
export default function WorkspaceItemPage({ params }: PageProps<"/[locale]/s/[school]/portfolio/items/[itemId]">) {
  return (
    <Suspense fallback={<Skeleton className="h-96 rounded-2xl" />}>
      <Item params={params} />
    </Suspense>
  );
}

async function Item({ params }: { params: PageProps<"/[locale]/s/[school]/portfolio/items/[itemId]">["params"] }) {
  const { itemId } = await params;
  if (!isUuid(itemId)) notFound();
  return <WorkspaceItem itemId={itemId} />;
}
