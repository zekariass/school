"use client";

import { useMutation, useQueries, useQueryClient } from "@tanstack/react-query";
import { CalendarClockIcon, Trash2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { ConfirmDialog } from "@/components/school/confirm-dialog";
import { Button } from "@/components/ui/button";
import { errorMessage } from "@/lib/console/errors";
import { MESSAGING_GC_MS, mk } from "@/lib/messaging/keys";
import { deleteMessage, fetchConversation } from "@/lib/messaging/queries";
import { messageSegments, previewLine } from "@/lib/messaging/text";
import type { Message } from "@/lib/messaging/types";
import { useConversationTitle } from "./person-name";
import { useCapabilities, useScheduled } from "./use-messaging";
import { useMessageTime } from "./use-message-time";

/**
 * Your own scheduled messages that are not sent yet (adults only), with the time in the school's time zone. Cancelling withdraws
 * a message; the API sends it at its time after checking again that you may still write to that conversation.
 */
export function ScheduledView() {
  const t = useTranslations("school.messaging.scheduled");
  const scheduled = useScheduled();
  const items = scheduled.data ?? [];
  const ids = [...new Set(items.map((m) => m.conversationId))].slice(0, 30);
  const conversations = useQueries({
    queries: ids.map((id) => ({ queryKey: mk.conversation(id), queryFn: () => fetchConversation(id), staleTime: 30_000, gcTime: MESSAGING_GC_MS, retry: false })),
  });
  const byId = new Map(conversations.flatMap((q) => (q.data ? [[q.data.summary.id, q.data.summary] as const] : [])));
  const firstSchool = [...byId.values()][0]?.schoolId;
  const caps = useCapabilities(firstSchool);
  const time = useMessageTime(caps.data?.timeZone);
  const title = useConversationTitle();
  const queryClient = useQueryClient();
  const [cancelling, setCancelling] = useState<Message | null>(null);

  const cancel = useMutation({
    mutationFn: (m: Message) => deleteMessage(m.conversationId, m.id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: mk.scheduled });
      toast.success(t("cancelled"));
      setCancelling(null);
    },
  });

  return (
    <div className="flex flex-col gap-5">
      <header className="flex flex-col gap-1">
        <h1 className="text-3xl font-bold">{t("title")}</h1>
        <p className="text-muted-foreground">{t("subtitle")}</p>
      </header>
      {scheduled.isPending ? <LoadingRows rows={3} /> : null}
      {scheduled.isError ? <ErrorNotice message={errorMessage(scheduled.error, t("loadFailed"))} onRetry={() => void scheduled.refetch()} /> : null}
      {scheduled.isSuccess && items.length === 0 ? <EmptyNotice>{t("empty")}</EmptyNotice> : null}
      <ul className="flex flex-col gap-2">
        {items.map((m) => {
          const c = byId.get(m.conversationId);
          const text = m.body ? previewLine(messageSegments(m.body).map((s) => s.text).join(""), 160) : null;
          return (
            <li key={m.id} className="flex flex-wrap items-start gap-3 rounded-2xl border bg-card p-3" data-testid="scheduled-row">
              <CalendarClockIcon className="mt-1 size-5 shrink-0 text-primary" aria-hidden />
              <div className="min-w-0 flex-1">
                <p className="font-semibold">{c ? t("to", { name: title(c) }) : t("toUnknown")}</p>
                {text ? (
                  <p dir="auto" className="line-clamp-2 text-sm [overflow-wrap:anywhere]">
                    {text}
                  </p>
                ) : (
                  <p className="text-sm text-muted-foreground">{t("photos", { count: m.attachments.length })}</p>
                )}
                {m.scheduledFor ? (
                  <p className="text-sm text-muted-foreground">
                    <time dateTime={m.scheduledFor}>{t("sendsAt", { when: time.full(m.scheduledFor) })}</time>
                  </p>
                ) : null}
              </div>
              <Button type="button" variant="outline" size="sm" onClick={() => setCancelling(m)} data-testid="scheduled-cancel">
                <Trash2Icon aria-hidden />
                {t("cancel")}
              </Button>
            </li>
          );
        })}
      </ul>
      {cancelling ? (
        <ConfirmDialog
          title={t("cancelTitle")}
          description={t("cancelDescription")}
          confirmLabel={t("cancelConfirm")}
          pending={cancel.isPending}
          error={cancel.error ? errorMessage(cancel.error, t("cancelFailed")) : null}
          onClose={() => {
            cancel.reset();
            setCancelling(null);
          }}
          onConfirm={() => cancel.mutate(cancelling)}
        />
      ) : null}
    </div>
  );
}
