"use client";

import { useTranslations } from "next-intl";
import { useSchool } from "@/components/school/school-context";
import { workspaceItemPath } from "@/lib/portfolio/paths";
import { fetchSchoolGallery, pfk } from "@/lib/portfolio/queries";
import { ItemList } from "./item-list";

/** The school gallery in the workspace: approved items whose audience is the whole school (empty unless the school allows it). */
export function SchoolPortfolioView() {
  const t = useTranslations("school.portfolio.schoolPage");
  const { school } = useSchool();
  return (
    <div className="mx-auto flex w-full max-w-5xl flex-col gap-4">
      <header className="flex flex-col gap-1">
        <h1 className="text-3xl font-bold">{t("title")}</h1>
        <p className="text-muted-foreground">{t("subtitle")}</p>
      </header>
      <ItemList
        queryKey={pfk.schoolGallery(school.id)}
        fetchPage={(cursor) => fetchSchoolGallery(school.id, cursor)}
        itemHref={(item) => workspaceItemPath(school.slug, item.id)}
        showStudent
        emptyText={t("empty")}
        failedText={t("failed")}
        testId="portfolio-school-gallery"
      />
    </div>
  );
}
