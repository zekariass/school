"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { toast } from "sonner";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { Button } from "@/components/ui/button";
import { errorMessage } from "@/lib/console/errors";
import { mk } from "@/lib/messaging/keys";
import { personName } from "@/lib/messaging/participants";
import { unblockPerson } from "@/lib/messaging/queries";
import { PersonBadge, usePersonText } from "./person-name";
import { useBlocks } from "./use-messaging";

/** The people you blocked, and unblocking. It only ever shows your own blocks, never anything about who blocked you. */
export function BlockedView() {
  const t = useTranslations("school.messaging.blocked");
  const blocks = useBlocks();
  const { participant } = usePersonText();
  const queryClient = useQueryClient();
  const unblock = useMutation({
    mutationFn: (id: string) => unblockPerson(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: mk.all });
      toast.success(t("unblocked"));
    },
    onError: (e) => toast.error(errorMessage(e, t("failed"))),
  });

  return (
    <div className="flex flex-col gap-5">
      <header className="flex flex-col gap-1">
        <h1 className="text-3xl font-bold">{t("title")}</h1>
        <p className="text-muted-foreground">{t("subtitle")}</p>
      </header>
      {blocks.isPending ? <LoadingRows rows={2} /> : null}
      {blocks.isError ? <ErrorNotice message={errorMessage(blocks.error, t("loadFailed"))} onRetry={() => void blocks.refetch()} /> : null}
      {blocks.isSuccess && blocks.data.length === 0 ? <EmptyNotice>{t("empty")}</EmptyNotice> : null}
      <ul className="flex flex-col gap-2">
        {(blocks.data ?? []).map((b) => (
          <li key={b.id} className="flex items-center gap-3 rounded-2xl border bg-card p-3" data-testid="blocked-row">
            <PersonBadge name={personName(b.person)} />
            <p className="min-w-0 flex-1 truncate font-semibold">{participant(b.person)}</p>
            <Button type="button" variant="outline" size="sm" disabled={unblock.isPending} onClick={() => unblock.mutate(b.id)} data-testid="unblock">
              {t("unblock")}
            </Button>
          </li>
        ))}
      </ul>
    </div>
  );
}
