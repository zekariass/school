"use client";

import { useQuery } from "@tanstack/react-query";
import { HistoryIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { useFormatInstant } from "@/components/calendar-preference";
import { ErrorNotice, LoadingRows } from "@/components/console/states";
import { Button } from "@/components/ui/button";
import { ank, fetchRevisions } from "@/lib/announcements/queries";
import { errorMessage } from "@/lib/console/errors";
import { PlainText } from "./announcement-text";

/** The previous versions of a published announcement with who replaced them and whether people were told (author and managers). */
export function RevisionsPanel({ schoolId, announcementId, revision }: { schoolId: string; announcementId: string; revision: number }) {
  const t = useTranslations("school.announcements.revisions");
  const format = useFormatInstant();
  const [open, setOpen] = useState(false);
  const revisions = useQuery({
    queryKey: [...ank.revisions(schoolId, announcementId), revision],
    queryFn: () => fetchRevisions(schoolId, announcementId),
    enabled: open,
    retry: false,
  });
  return (
    <section className="flex flex-col gap-3 rounded-2xl border p-4" aria-labelledby="revisions-title" data-testid="revisions">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="revisions-title" className="flex items-center gap-2 text-lg font-bold">
          <HistoryIcon className="size-5" aria-hidden />
          {t("title")}
        </h2>
        <Button type="button" variant="outline" size="sm" aria-expanded={open} onClick={() => setOpen(!open)} data-testid="revisions-toggle">
          {open ? "−" : "+"} {t("title")}
        </Button>
      </div>
      {open ? (
        revisions.isPending ? (
          <LoadingRows rows={2} />
        ) : revisions.isError ? (
          <ErrorNotice message={errorMessage(revisions.error, t("loadFailed"))} onRetry={() => void revisions.refetch()} />
        ) : revisions.data.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("empty")}</p>
        ) : (
          <ol className="flex flex-col gap-3">
            {revisions.data.map((r) => (
              <li key={r.revision} className="flex flex-col gap-1.5 rounded-xl bg-muted/50 p-3" data-testid="revision">
                <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                  <span className="font-bold text-foreground">{t("version", { revision: r.revision })}</span>
                  <span>{r.editedByName ? t("by", { name: r.editedByName, when: format(r.editedAt) }) : t("byUnknown", { when: format(r.editedAt) })}</span>
                  <span className="rounded-full border px-2 py-0.5 font-semibold">{r.material ? t("material") : t("silent")}</span>
                </div>
                {r.changeNote ? <p className="text-sm font-medium [overflow-wrap:anywhere]">{t("note", { note: r.changeNote })}</p> : null}
                <p className="font-semibold [overflow-wrap:anywhere]">{r.title}</p>
                <PlainText text={r.body} className="text-sm text-muted-foreground" />
              </li>
            ))}
          </ol>
        )
      ) : null}
    </section>
  );
}
