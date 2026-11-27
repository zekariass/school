"use client";

import { useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { errorMessage } from "@/lib/console/errors";
import { familyKeys, fetchMyChildren } from "@/lib/family/queries";
import { itemPath } from "@/lib/portfolio/paths";
import { fetchFamilyPortfolio, pfk } from "@/lib/portfolio/queries";
import { cn } from "@/lib/utils";
import { ItemList } from "./item-list";

/**
 * A guardian's view of their children's portfolios (`GET /me/portfolio`): approved items the API shows to this
 * guardian, newest first, with a filter per child. Open an item to read it and leave an encouraging comment. Nothing
 * here is filtered or guessed by the page.
 */
export function FamilyPortfolio({ initialStudentId }: { initialStudentId?: string }) {
  const t = useTranslations("family.portfolio");
  const children = useQuery({ queryKey: familyKeys.children, queryFn: fetchMyChildren });
  const [picked, setPicked] = useState<string | null>(initialStudentId ?? null);

  if (children.isPending) return <LoadingRows rows={3} />;
  if (children.isError) return <ErrorNotice message={errorMessage(children.error, t("loadFailed"))} onRetry={() => void children.refetch()} />;
  const kids = children.data.children.filter((c) => c.permissions.canViewAcademics);
  if (kids.length === 0) return <EmptyNotice>{t("noChildren")}</EmptyNotice>;
  const selected = kids.length > 1 ? picked : (kids[0]?.studentId ?? null);

  return (
    <section aria-labelledby="family-portfolio-title" className="flex flex-col gap-4" data-testid="family-portfolio">
      <header className="flex flex-col gap-1">
        <h1 id="family-portfolio-title" className="text-3xl font-bold">
          {t("title")}
        </h1>
        <p className="text-muted-foreground">{t("subtitle")}</p>
      </header>
      {kids.length > 1 ? (
        <div role="group" aria-label={t("filter")} className="flex flex-wrap gap-2">
          <Chip active={selected === null} onClick={() => setPicked(null)}>
            {t("allChildren")}
          </Chip>
          {kids.map((child) => (
            <Chip key={child.studentId} active={selected === child.studentId} onClick={() => setPicked(child.studentId)} testId={`portfolio-filter-${child.studentId}`}>
              {child.givenName || child.displayName}
            </Chip>
          ))}
        </div>
      ) : null}
      <ItemList
        key={selected ?? "all"}
        queryKey={pfk.family(selected)}
        fetchPage={(cursor) => fetchFamilyPortfolio(selected, cursor)}
        itemHref={(item) => itemPath(item.schoolId, item.id)}
        showStudent={kids.length > 1}
        emptyText={t("empty")}
        failedText={t("failed")}
        testId="portfolio-family-list"
      />
    </section>
  );
}

function Chip({ active, onClick, children, testId }: { active: boolean; onClick: () => void; children: React.ReactNode; testId?: string }) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      data-testid={testId}
      className={cn("min-h-10 rounded-full border px-4 text-sm font-medium", active ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent")}
    >
      {children}
    </button>
  );
}
