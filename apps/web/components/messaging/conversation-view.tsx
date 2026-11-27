"use client";

import { useQueryClient } from "@tanstack/react-query";
import { ArrowLeftIcon, EyeIcon, InfoIcon, LockIcon, ShieldAlertIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useEffect, useMemo, useRef, useState } from "react";
import { ErrorNotice, LoadingRows } from "@/components/console/states";
import { Button } from "@/components/ui/button";
import { Link } from "@/i18n/navigation";
import { ApiRequestError } from "@/lib/api-error";
import { errorMessage } from "@/lib/console/errors";
import { mk } from "@/lib/messaging/keys";
import { markRead } from "@/lib/messaging/queries";
import { personName } from "@/lib/messaging/participants";
import { banners, flattenMessages, hasReceipts, newestForeignId, readOnlyReason, type Banner } from "@/lib/messaging/thread";
import { Composer } from "./composer";
import { ConversationMenu } from "./conversation-menu";
import { useMessagesPlace } from "./messages-context";
import { MessageList } from "./message-list";
import { PersonBadge, useConversationTitle, usePersonText } from "./person-name";
import { useCapabilities, useConversation, useDocumentHidden, useMembers, useMessages } from "./use-messaging";
import { useDisplayPlace, useSchoolPlace } from "@/components/display-context";
import { useMessageTime } from "./use-message-time";
import type { Message } from "@/lib/messaging/types";

/**
 * One conversation. What the person may do comes from the API: `canPost`, their role and the conversation's status decide whether the
 * composer exists at all (never for observers or closed conversations); `canModerate`, `canEdit` and `canDelete` decide the menus.
 * Messages are fetched through the API only; the live "doorbell" just makes this refetch.
 */
export function ConversationView({ conversationId }: { conversationId: string }) {
  const t = useTranslations("school.messaging");
  const { base } = useMessagesPlace();
  const queryClient = useQueryClient();
  const conversation = useConversation(conversationId);
  const messages = useMessages(conversationId);
  const summary = conversation.data?.summary;
  const capabilities = useCapabilities(summary?.schoolId);
  const members = useMembers(conversationId, summary?.myRole === "OBSERVER" && summary.type === "DIRECT");
  const title = useConversationTitle();
  const { participant } = usePersonText();
  // The conversation's school clock: its capabilities, else the place /me gave for that school, else the page's (D42).
  const schoolPlace = useSchoolPlace(summary?.schoolId);
  const pagePlace = useDisplayPlace();
  const messageZone = capabilities.data?.timeZone ?? schoolPlace?.timeZone ?? pagePlace?.timeZone;
  const time = useMessageTime(messageZone);
  const hidden = useDocumentHidden();
  const [replyTo, setReplyTo] = useState<Message | null>(null);
  const [now, setNow] = useState(() => new Date());
  const lastMarked = useRef<string | null>(null);

  // a clock for the edit countdown
  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 15_000);
    return () => clearInterval(timer);
  }, []);

  // the text of a conversation does not stay in memory after it is left
  useEffect(() => {
    return () => {
      queryClient.removeQueries({ queryKey: mk.messages(conversationId) });
    };
  }, [conversationId, queryClient]);

  const ordered = useMemo(() => flattenMessages(messages.data?.pages ?? []), [messages.data]);
  const newest = newestForeignId(ordered);
  const unread = summary?.unreadCount ?? 0;

  // reading moves the watermark (and, in a direct conversation, gives the sender a "Seen"; the API never does that for observers)
  useEffect(() => {
    if (!newest || hidden || !messages.isSuccess) return;
    const changed = lastMarked.current !== null && lastMarked.current !== newest;
    if (unread === 0 && !changed) {
      lastMarked.current ??= newest;
      return;
    }
    lastMarked.current = newest;
    void markRead(conversationId, newest)
      .then(() => {
        void queryClient.invalidateQueries({ queryKey: mk.inboxAll });
        void queryClient.invalidateQueries({ queryKey: mk.unread });
        void queryClient.invalidateQueries({ queryKey: mk.conversation(conversationId) });
      })
      .catch(() => {
        lastMarked.current = null;
      });
  }, [newest, unread, hidden, messages.isSuccess, conversationId, queryClient]);

  if (conversation.isPending) return <LoadingRows rows={5} />;
  if (conversation.isError || !summary) {
    const missing = conversation.error instanceof ApiRequestError && (conversation.error.status === 404 || conversation.error.status === 403);
    return (
      <div className="flex flex-col gap-4">
        <BackLink base={base} />
        {missing ? (
          <p role="alert" className="rounded-xl border border-dashed p-8 text-center text-muted-foreground" data-testid="conversation-missing">
            {errorMessage(conversation.error, t("conversation.notFound"))}
          </p>
        ) : (
          <ErrorNotice message={errorMessage(conversation.error, t("conversation.loadFailed"))} onRetry={() => void conversation.refetch()} />
        )}
      </div>
    );
  }

  const observer = summary.myRole === "OBSERVER";
  const reason = readOnlyReason(summary);
  const notices = banners(summary);
  const direct = hasReceipts(summary);
  const name = observer && summary.type === "DIRECT" && members.data ? members.data.members.filter((m) => m.role !== "OBSERVER").map((m) => participant(m)).join(" · ") || title(summary) : title(summary);
  const aboutChild = summary.myActingAs !== "STUDENT" && summary.contextStudent?.displayName && summary.type === "DIRECT" ? t("aboutChild", { child: summary.contextStudent.displayName }) : null;

  return (
    <div className="flex h-[calc(100dvh-11rem)] min-h-[26rem] flex-col gap-3 md:h-[calc(100dvh-9rem)]" data-testid="conversation">
      <header className="flex items-center gap-2">
        <BackLink base={base} compact />
        <PersonBadge name={summary.counterpart ? personName(summary.counterpart) : { kind: "name", name }} />
        <div className="min-w-0 flex-1">
          <h1 className="truncate text-xl font-bold" data-testid="conversation-title">
            {name}
          </h1>
          {aboutChild ? <p className="truncate text-sm text-muted-foreground">{aboutChild}</p> : null}
        </div>
        <ConversationMenu conversation={summary} canModerate={conversation.data.canModerate} />
      </header>

      {notices.map((notice) => (
        <BannerView key={notice.kind} banner={notice} />
      ))}

      {messages.isPending || (messages.isSuccess && !messageZone) ? <LoadingRows rows={4} /> : null}
      {messages.isError ? <ErrorNotice message={errorMessage(messages.error, t("conversation.loadFailed"))} onRetry={() => void messages.refetch()} /> : null}
      {messages.isSuccess && messageZone ? (
        <MessageList
          conversationId={conversationId}
          messages={ordered}
          hasOlder={!!messages.hasNextPage}
          loadingOlder={messages.isFetchingNextPage}
          onLoadOlder={() => void messages.fetchNextPage()}
          timeZone={messageZone}
          time={time}
          now={now}
          direct={direct}
          observer={observer}
          canModerate={conversation.data.canModerate}
          closed={summary.status !== "ACTIVE"}
          showSenders={summary.type !== "DIRECT" || observer}
          capabilities={capabilities.data}
          onReply={setReplyTo}
        />
      ) : null}

      {reason === null ? (
        <Composer conversation={summary} capabilities={capabilities.data} replyTo={replyTo} onClearReply={() => setReplyTo(null)} />
      ) : (
        <p className="rounded-2xl border bg-muted p-3 text-center text-sm text-muted-foreground" data-testid="read-only" data-reason={reason}>
          {t(`readOnly.${reason}`)}
        </p>
      )}
    </div>
  );
}

function BackLink({ base, compact }: { base: string; compact?: boolean }) {
  const t = useTranslations("school.messaging.conversation");
  if (compact) {
    return (
      <Button asChild variant="ghost" size="icon">
        <Link href={base} aria-label={t("back")} data-testid="back-to-inbox">
          <ArrowLeftIcon aria-hidden className="rtl:rotate-180" />
        </Link>
      </Button>
    );
  }
  return (
    <Link href={base} className="inline-flex items-center gap-2 font-semibold text-primary" data-testid="back-to-inbox">
      <ArrowLeftIcon className="size-4 rtl:rotate-180" aria-hidden />
      {t("back")}
    </Link>
  );
}

/** The safeguarding and state notices at the top. They are not dismissible: they say who can read the conversation. */
function BannerView({ banner }: { banner: Banner }) {
  const t = useTranslations("school.messaging.banner");
  const safeguarding = banner.kind === "observerGuardian" || banner.kind === "studentGuardiansCanSee" || banner.kind === "studentReviewable" || banner.kind === "adultStudentNotPrivate";
  const Icon = banner.kind === "observerGuardian" ? EyeIcon : safeguarding ? ShieldAlertIcon : banner.kind === "closed" ? LockIcon : InfoIcon;
  return (
    <div
      role="note"
      data-testid={`banner-${banner.kind}`}
      className={safeguarding ? "flex items-start gap-3 rounded-2xl border border-primary/40 bg-primary/5 p-3 text-sm" : "flex items-start gap-3 rounded-2xl border bg-muted p-3 text-sm"}
    >
      <Icon className="mt-0.5 size-5 shrink-0 text-primary" aria-hidden />
      <p>{banner.kind === "closed" ? t(`closed.${banner.status === "ARCHIVED" ? "ARCHIVED" : "LOCKED"}`) : t(banner.kind)}</p>
    </div>
  );
}
