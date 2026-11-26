"use client";

import { ArrowDownIcon, Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { findReplyTarget, lastSeenId, threadRows } from "@/lib/messaging/thread";
import type { Capabilities, Message } from "@/lib/messaging/types";
import { MessageBubble } from "./message-bubble";

export interface MessageTime {
  clock: (instant: string | Date) => string;
  day: (instant: string | Date) => string;
  full: (instant: string | Date) => string;
}

interface Props {
  conversationId: string;
  /** Oldest first. */
  messages: readonly Message[];
  hasOlder: boolean;
  loadingOlder: boolean;
  onLoadOlder: () => void;
  timeZone: string;
  time: MessageTime;
  now: Date;
  direct: boolean;
  observer: boolean;
  canModerate: boolean;
  closed: boolean;
  /** Groups and observed conversations name the sender of every run; a direct conversation does not. */
  showSenders: boolean;
  capabilities: Capabilities | undefined;
  onReply: (message: Message) => void;
}

/**
 * The messages, newest at the bottom. New messages scroll into view only while the reader is at the bottom (otherwise a
 * "new messages" button appears); loading older ones keeps the reader where they were. A polite live region announces what arrives.
 */
export function MessageList({ conversationId, messages, hasOlder, loadingOlder, onLoadOlder, timeZone, time, now, direct, observer, canModerate, closed, showSenders, capabilities, onReply }: Props) {
  const t = useTranslations("school.messaging");
  const scroller = useRef<HTMLDivElement>(null);
  const content = useRef<HTMLDivElement>(null);
  const stick = useRef(true);
  const previous = useRef<{ first: string | null; last: string | null; height: number }>({ first: null, last: null, height: 0 });
  const [hasNew, setHasNew] = useState(false);
  const rows = threadRows(messages, timeZone);
  const seenId = lastSeenId(messages, direct);
  const first = messages[0]?.id ?? null;
  const last = messages[messages.length - 1]?.id ?? null;
  const lastMine = messages[messages.length - 1]?.mine ?? false;

  useLayoutEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const before = previous.current;
    if (before.last === null) {
      el.scrollTop = el.scrollHeight;
    } else if (first !== before.first && last === before.last) {
      // older messages were added above: keep the reader on the same message
      el.scrollTop += el.scrollHeight - before.height;
    } else if (last !== before.last) {
      if (stick.current || lastMine) {
        el.scrollTop = el.scrollHeight;
        stick.current = true;
        setHasNew(false);
      } else {
        setHasNew(true);
      }
    }
    previous.current = { first, last, height: el.scrollHeight };
  }, [first, last, lastMine]);

  // fonts and images settle after the first paint: while the reader is at the bottom they stay at the bottom
  useEffect(() => {
    const el = scroller.current;
    const inner = content.current;
    if (!el || !inner || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(() => {
      if (stick.current) el.scrollTop = el.scrollHeight;
    });
    observer.observe(inner);
    return () => observer.disconnect();
  }, []);

  function onScroll() {
    const el = scroller.current;
    if (!el) return;
    stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
    if (stick.current) setHasNew(false);
  }

  function jumpToNewest() {
    const el = scroller.current;
    if (!el) return;
    el.scrollTo({ top: el.scrollHeight, behavior: "smooth" });
    stick.current = true;
    setHasNew(false);
  }

  return (
    <div className="relative flex min-h-0 flex-1 flex-col">
      <div
        ref={scroller}
        onScroll={onScroll}
        role="log"
        aria-live="polite"
        aria-relevant="additions"
        aria-label={t("thread.label")}
        tabIndex={0}
        data-testid="message-log"
        className="flex min-h-0 flex-1 flex-col overflow-y-auto overscroll-contain rounded-2xl border bg-background p-3 focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
      >
        <div ref={content} className="flex min-h-full flex-col gap-1.5">
        {hasOlder ? (
          <div className="flex justify-center pb-2">
            <Button type="button" variant="outline" size="sm" onClick={onLoadOlder} disabled={loadingOlder} data-testid="load-older">
              {loadingOlder ? <Loader2Icon className="animate-spin" aria-hidden /> : null}
              {t("thread.loadOlder")}
            </Button>
          </div>
        ) : null}
        {messages.length === 0 ? <p className="m-auto text-center text-muted-foreground">{t("thread.empty")}</p> : null}
        {rows.map((row) =>
          row.kind === "day" ? (
            <p key={row.key} className="self-center rounded-full bg-muted px-3 py-0.5 text-xs font-medium text-muted-foreground">
              {time.day(row.instant)}
            </p>
          ) : (
            <div key={row.key} className={row.grouped ? "-mt-0.5" : "mt-1"}>
              <MessageBubble
                conversationId={conversationId}
                message={row.message}
                replyTarget={findReplyTarget(messages, row.message.replyToMessageId)}
                showSender={showSenders && !row.grouped}
                showSeen={row.message.id === seenId}
                observer={observer}
                canModerate={canModerate}
                closed={closed}
                capabilities={capabilities}
                time={time}
                now={now}
                onReply={onReply}
              />
            </div>
          ),
        )}
        </div>
      </div>
      {hasNew ? (
        <Button type="button" size="sm" className="absolute start-1/2 bottom-3 -translate-x-1/2 shadow-md rtl:translate-x-1/2" onClick={jumpToNewest} data-testid="jump-newest">
          <ArrowDownIcon aria-hidden />
          {t("thread.newMessages")}
        </Button>
      ) : null}
    </div>
  );
}
