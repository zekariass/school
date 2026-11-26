"use client";

import { useMutation, useQueryClient, type InfiniteData } from "@tanstack/react-query";
import { toast } from "sonner";
import { errorMessage } from "@/lib/console/errors";
import { mk } from "@/lib/messaging/keys";
import {
  blockPerson,
  deleteMessage,
  editMessage,
  hideMessage,
  reactToMessage,
  reportMessage,
  translateMessage,
  unhideMessage,
  unreactToMessage,
} from "@/lib/messaging/queries";
import { tombstone, upsertMessage } from "@/lib/messaging/thread";
import type { Message, ReportReason } from "@/lib/messaging/types";

type Pages = InfiniteData<{ items: Message[]; nextCursor?: string }, string | undefined>;

/**
 * The writes a message offers. Each one patches the cached pages with what the API answered (a message or a tombstone) and
 * asks for a refetch; the API decides whether it is allowed (every refusal shows its own localized detail).
 */
export function useMessageActions(conversationId: string, fallbacks: { failed: string }) {
  const queryClient = useQueryClient();

  function patch(message: Message) {
    queryClient.setQueryData<Pages>(mk.messages(conversationId), (data) => (data ? { ...data, pages: upsertMessage(data.pages, message) } : data));
  }
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: mk.messages(conversationId) });
    void queryClient.invalidateQueries({ queryKey: mk.inboxAll });
    void queryClient.invalidateQueries({ queryKey: mk.unread });
  };
  const onError = (error: unknown) => toast.error(errorMessage(error, fallbacks.failed));

  return {
    react: useMutation({
      mutationFn: ({ message, emoji }: { message: Message; emoji: string }) => {
        const mine = message.reactions.some((r) => r.emoji === emoji && r.mine);
        return mine ? unreactToMessage(conversationId, message.id, emoji) : reactToMessage(conversationId, message.id, emoji);
      },
      onSuccess: patch,
      onError,
    }),
    edit: useMutation({
      mutationFn: ({ messageId, body }: { messageId: string; body: string }) => editMessage(conversationId, messageId, body),
      onSuccess: (message) => {
        patch(message);
        refresh();
      },
    }),
    remove: useMutation({
      mutationFn: ({ message }: { message: Message }) => deleteMessage(conversationId, message.id),
      onSuccess: (_, { message }) => {
        patch(tombstone(message));
        refresh();
      },
    }),
    report: useMutation({
      mutationFn: ({ messageId, reason, note }: { messageId: string; reason: ReportReason; note?: string }) =>
        reportMessage(conversationId, messageId, reason, note),
    }),
    hide: useMutation({
      mutationFn: ({ messageId, reason }: { messageId: string; reason: string }) => hideMessage(conversationId, messageId, reason),
      onSuccess: refresh,
    }),
    unhide: useMutation({
      mutationFn: ({ messageId }: { messageId: string }) => unhideMessage(conversationId, messageId),
      onSuccess: refresh,
      onError,
    }),
    block: useMutation({
      mutationFn: ({ memberId }: { memberId: string }) => blockPerson(conversationId, memberId),
      onSuccess: () => {
        void queryClient.invalidateQueries({ queryKey: mk.blocks });
        refresh();
      },
    }),
    translate: useMutation({
      mutationFn: ({ messageId, language }: { messageId: string; language: string }) => translateMessage(conversationId, messageId, language),
    }),
  };
}

export type MessageActions = ReturnType<typeof useMessageActions>;
