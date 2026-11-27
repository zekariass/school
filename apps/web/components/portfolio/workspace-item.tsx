"use client";

import { useSchool } from "@/components/school/school-context";
import { workspacePortfolioPath } from "@/lib/portfolio/paths";
import { ItemDetail } from "./item-detail";

/** The item page inside a school workspace: the school comes from the workspace, the item from the path. */
export function WorkspaceItem({ itemId }: { itemId: string }) {
  const { school } = useSchool();
  return <ItemDetail schoolId={school.id} itemId={itemId} fallbackHref={workspacePortfolioPath(school.slug)} />;
}
