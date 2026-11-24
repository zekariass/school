"use client";

import { useInfiniteQuery } from "@tanstack/react-query";
import { EyeIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { useFormatInstant } from "@/components/calendar-preference";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { errorMessage } from "@/lib/console/errors";
import { fetchAudit, queryKeys, type AuditEntry, type AuditFilters } from "@/lib/console/queries";
import { FormField } from "./form-field";
import { EmptyNotice, ErrorNotice, LoadingRows, LoadMore } from "./states";

interface Draft {
  actionPrefix: string;
  targetType: string;
  from: string;
  to: string;
}

const EMPTY: Draft = { actionPrefix: "", targetType: "", from: "", to: "" };

/** A date input value ("2026-11-24") as the start (or end) of that day, UTC. */
function toInstant(date: string, endOfDay: boolean): string | undefined {
  if (!date) return undefined;
  return `${date}T${endOfDay ? "23:59:59" : "00:00:00"}Z`;
}

function toFilters(draft: Draft): AuditFilters {
  return {
    ...(draft.actionPrefix.trim() ? { actionPrefix: draft.actionPrefix.trim() } : {}),
    ...(draft.targetType.trim() ? { targetType: draft.targetType.trim() } : {}),
    ...(draft.from ? { from: toInstant(draft.from, false) } : {}),
    ...(draft.to ? { to: toInstant(draft.to, true) } : {}),
  };
}

export function AuditView() {
  const t = useTranslations("console");
  const formatInstant = useFormatInstant();
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [filters, setFilters] = useState<AuditFilters>({});
  const [selected, setSelected] = useState<AuditEntry | null>(null);

  const audit = useInfiniteQuery({
    queryKey: queryKeys.audit(filters),
    queryFn: ({ pageParam }) => fetchAudit(filters, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor,
  });
  const rows = audit.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-bold">{t("audit.title")}</h1>
        <p className="text-muted-foreground">{t("audit.subtitle")}</p>
      </div>

      <form
        className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5 lg:items-end"
        onSubmit={(event) => {
          event.preventDefault();
          setFilters(toFilters(draft));
        }}
      >
        <FormField label={t("audit.action")} hint={t("audit.actionHint")}>
          {(props) => (
            <Input
              {...props}
              value={draft.actionPrefix}
              spellCheck={false}
              onChange={(e) => setDraft({ ...draft, actionPrefix: e.target.value })}
            />
          )}
        </FormField>
        <FormField label={t("audit.targetType")}>
          {(props) => (
            <Input
              {...props}
              value={draft.targetType}
              spellCheck={false}
              onChange={(e) => setDraft({ ...draft, targetType: e.target.value })}
            />
          )}
        </FormField>
        <FormField label={t("audit.from")}>
          {(props) => (
            <Input {...props} type="date" value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
          )}
        </FormField>
        <FormField label={t("audit.to")}>
          {(props) => (
            <Input {...props} type="date" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} />
          )}
        </FormField>
        <div className="flex gap-2">
          <Button type="submit">{t("audit.apply")}</Button>
          <Button
            type="button"
            variant="outline"
            onClick={() => {
              setDraft(EMPTY);
              setFilters({});
            }}
          >
            {t("audit.reset")}
          </Button>
        </div>
      </form>

      {audit.isPending ? (
        <LoadingRows />
      ) : audit.isError ? (
        <ErrorNotice message={errorMessage(audit.error, t("loadFailed"))} onRetry={() => void audit.refetch()} />
      ) : rows.length === 0 ? (
        <EmptyNotice>{t("audit.empty")}</EmptyNotice>
      ) : (
        <>
          <div className="rounded-2xl border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("audit.when")}</TableHead>
                  <TableHead>{t("audit.action")}</TableHead>
                  <TableHead>{t("audit.outcome")}</TableHead>
                  <TableHead>{t("audit.actor")}</TableHead>
                  <TableHead>{t("audit.target")}</TableHead>
                  <TableHead className="w-12" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((entry) => (
                  <TableRow key={entry.id}>
                    <TableCell className="whitespace-nowrap">{formatInstant(entry.occurredAt)}</TableCell>
                    <TableCell className="font-mono text-sm">{entry.action}</TableCell>
                    <TableCell>
                      <Badge variant={entry.outcome === "SUCCESS" ? "default" : "destructive"}>{entry.outcome}</Badge>
                    </TableCell>
                    <TableCell className="font-mono text-xs">{entry.actorUserId?.slice(0, 8) ?? "—"}</TableCell>
                    <TableCell className="text-sm">
                      {entry.targetType ?? "—"}
                      {entry.targetId ? (
                        <span className="ms-1 font-mono text-xs text-muted-foreground">{entry.targetId.slice(0, 8)}</span>
                      ) : null}
                    </TableCell>
                    <TableCell>
                      <Button
                        size="icon"
                        variant="ghost"
                        aria-label={t("audit.details")}
                        onClick={() => setSelected(entry)}
                      >
                        <EyeIcon className="size-4" aria-hidden />
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <LoadMore
            hasMore={!!audit.hasNextPage}
            loading={audit.isFetchingNextPage}
            onClick={() => void audit.fetchNextPage()}
          />
        </>
      )}

      <Dialog open={selected !== null} onOpenChange={(open) => !open && setSelected(null)}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle className="font-mono text-base">{selected?.action}</DialogTitle>
            <DialogDescription>{selected ? formatInstant(selected.occurredAt) : null}</DialogDescription>
          </DialogHeader>
          {selected ? (
            <pre className="max-h-96 overflow-auto rounded-xl bg-muted p-4 text-xs" dir="ltr">
              {JSON.stringify(
                {
                  actorUserId: selected.actorUserId,
                  actorRoles: selected.actorRoles,
                  schoolId: selected.schoolId,
                  targetType: selected.targetType,
                  targetId: selected.targetId,
                  changes: selected.changes,
                  metadata: selected.metadata,
                  requestId: selected.requestId,
                  ip: selected.ip,
                },
                null,
                2,
              )}
            </pre>
          ) : null}
        </DialogContent>
      </Dialog>
    </div>
  );
}
