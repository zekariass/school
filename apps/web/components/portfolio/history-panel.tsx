"use client";

import { useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useFormatInstant } from "@/components/calendar-preference";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { errorMessage } from "@/lib/console/errors";
import { fetchHistory, PORTFOLIO_GC_MS, pfk } from "@/lib/portfolio/queries";
import { isHistoryAction } from "@/lib/portfolio/view";

/**
 * The status history of an item (who did what, and the note). Only the student and the item's reviewers get it from
 * the API: the page asks for it only for them, and shows the API's refusal if it comes anyway. Notes are in memory only.
 */
export function HistoryPanel({ schoolId, itemId }: { schoolId: string; itemId: string }) {
  const t = useTranslations("school.portfolio.history");
  const format = useFormatInstant();
  const history = useQuery({
    queryKey: pfk.history(schoolId, itemId),
    queryFn: () => fetchHistory(schoolId, itemId),
    staleTime: 15_000,
    gcTime: PORTFOLIO_GC_MS,
    retry: false,
  });

  return (
    <section aria-labelledby="portfolio-history-title" className="flex flex-col gap-3" data-testid="portfolio-history">
      <h2 id="portfolio-history-title" className="text-lg font-bold">
        {t("title")}
      </h2>
      {history.isPending ? (
        <LoadingRows rows={2} />
      ) : history.isError ? (
        <ErrorNotice message={errorMessage(history.error, t("failed"))} onRetry={() => void history.refetch()} />
      ) : history.data.length === 0 ? (
        <EmptyNotice>{t("empty")}</EmptyNotice>
      ) : (
        <ol className="flex flex-col gap-2">
          {history.data.map((entry) => (
            <li key={entry.id} className="rounded-xl border p-3 text-sm" data-testid="portfolio-history-entry" data-action={entry.action}>
              <p className="flex flex-wrap items-baseline justify-between gap-2">
                <span className="font-bold">{isHistoryAction(entry.action) ? t(`actions.${entry.action}`) : entry.action}</span>
                <time dateTime={entry.createdAt} className="text-xs text-muted-foreground">
                  {format(entry.createdAt)}
                </time>
              </p>
              <p className="text-muted-foreground">{entry.actorName ? t("by", { name: entry.actorName }) : t(`roles.${entry.actorRole}`)}</p>
              {entry.note ? <p className="mt-1 break-words whitespace-pre-wrap">{entry.note}</p> : null}
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
