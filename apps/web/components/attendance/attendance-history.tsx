"use client";

import { useInfiniteQuery } from "@tanstack/react-query";
import { ClipboardCheckIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useFormatDate } from "@/components/calendar-preference";
import { EmptyNotice, ErrorNotice, LoadingRows, LoadMore } from "@/components/console/states";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ak, fetchHistory } from "@/lib/attendance/queries";
import { isStatus } from "@/lib/attendance/status";
import { errorMessage } from "@/lib/console/errors";
import { StatusPill } from "./status";

/**
 * A child's attendance history (newest first, keyset paged) for the student and for guardians who may view
 * academics. The API sends no teacher notes to them and this table has no note column either.
 */
export function AttendanceHistory({ schoolId, studentId, audience, heading }: { schoolId: string; studentId: string; audience: "student" | "guardian"; heading?: string }) {
  const t = useTranslations("family.attendance");
  const formatDate = useFormatDate();
  const history = useInfiniteQuery({
    queryKey: ak.history(schoolId, studentId),
    queryFn: ({ pageParam }) => fetchHistory(schoolId, studentId, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    staleTime: 20_000,
    retry: false,
  });
  const items = history.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <section aria-labelledby={`attendance-title-${studentId}`} data-testid="attendance-history">
      <Card>
        <CardHeader>
          <CardTitle id={`attendance-title-${studentId}`} className="flex items-center gap-2 text-xl">
            <ClipboardCheckIcon className="size-5 text-primary" aria-hidden />
            {audience === "student" ? t("studentTitle") : t("title")}
          </CardTitle>
          <p className="text-sm text-muted-foreground">{heading ? `${heading} · ` : ""}{t("subtitle")}</p>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          {history.isPending ? (
            <LoadingRows rows={3} />
          ) : history.isError ? (
            <ErrorNotice message={errorMessage(history.error, t("loadFailed"))} onRetry={() => void history.refetch()} />
          ) : items.length === 0 ? (
            <EmptyNotice>{t("empty")}</EmptyNotice>
          ) : (
            <div className="overflow-x-auto rounded-2xl border">
              <Table data-testid="attendance-history-table">
                <caption className="sr-only">{t("caption")}</caption>
                <TableHeader>
                  <TableRow>
                    <TableHead scope="col">{t("date")}</TableHead>
                    <TableHead scope="col">{t("class")}</TableHead>
                    <TableHead scope="col">{t("statusColumn")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {items.map((item) => (
                    <TableRow key={item.recordId}>
                      <TableCell className="whitespace-nowrap font-medium">{formatDate(item.date)}</TableCell>
                      <TableCell>{item.className}</TableCell>
                      <TableCell>
                        {isStatus(item.status) ? (
                          <span className="inline-flex flex-wrap items-center gap-2">
                            <StatusPill status={item.status} label={t(`status.${item.status}`)} />
                            {item.minutesLate ? <span className="text-xs text-muted-foreground">{t("minutesLate", { minutes: item.minutesLate })}</span> : null}
                          </span>
                        ) : (
                          item.status
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
          <p className="text-xs text-muted-foreground">{t("notesNote")}</p>
          <LoadMore hasMore={!!history.hasNextPage} loading={history.isFetchingNextPage} onClick={() => void history.fetchNextPage()} />
        </CardContent>
      </Card>
    </section>
  );
}
