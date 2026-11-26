"use client";

import { BellOffIcon, EyeIcon, ImageIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { RelativeTime } from "@/components/feed/relative-time";
import { Link } from "@/i18n/navigation";
import { isMuted, isObserver, lastMessageState, unreadBadge } from "@/lib/messaging/inbox";
import { personName } from "@/lib/messaging/participants";
import { previewLine } from "@/lib/messaging/text";
import type { ConversationSummary } from "@/lib/messaging/types";
import { cn } from "@/lib/utils";
import { useMessagesPlace } from "./messages-context";
import { PersonBadge, useConversationTitle, usePersonText } from "./person-name";

/**
 * One line of the inbox. The preview text is whatever the API sent: for a deleted or hidden last message it sends none and
 * this shows a plain "message deleted" notice, never text. Names are those the API chose for this viewer.
 */
export function ConversationRow({ conversation: c, showChild = true }: { conversation: ConversationSummary; showChild?: boolean }) {
  const t = useTranslations("school.messaging");
  const { base } = useMessagesPlace();
  const title = useConversationTitle();
  const { text } = usePersonText();
  const name = title(c);
  const unread = unreadBadge(c.unreadCount);
  const observing = isObserver(c);
  const muted = isMuted(c, new Date());
  const last = lastMessageState(c);
  const preview = previewLine(c.lastMessage?.preview);
  const labelNote = showChild && c.myActingAs !== "STUDENT" && c.contextStudent?.displayName && c.type === "DIRECT" ? t("aboutChild", { child: c.contextStudent.displayName }) : null;

  return (
    <li>
      <Link
        href={`${base}/${c.id}`}
        data-testid="conversation-row"
        className={cn(
          "flex min-h-16 items-center gap-3 rounded-2xl border bg-card p-3 transition-colors hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none",
          unread && "border-primary/40",
        )}
      >
        <PersonBadge name={c.counterpart ? personName(c.counterpart) : { kind: "name", name }} />
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <div className="flex items-baseline justify-between gap-2">
            <p className={cn("truncate text-base", unread ? "font-bold" : "font-semibold")}>{name}</p>
            <RelativeTime instant={c.lastMessage?.sentAt ?? c.activityAt} className="shrink-0 text-xs text-muted-foreground" />
          </div>
          {labelNote ? <p className="truncate text-xs text-muted-foreground">{labelNote}</p> : null}
          <div className="flex items-center gap-2">
            <p className={cn("min-w-0 flex-1 truncate text-sm", unread ? "text-foreground" : "text-muted-foreground")}>
              {last === "none" ? t("row.noMessages") : null}
              {last === "deleted" ? <span className="italic">{t("row.deleted")}</span> : null}
              {last === "hidden" ? <span className="italic">{t("row.hidden")}</span> : null}
              {last === "image" ? (
                <span className="inline-flex items-center gap-1">
                  <ImageIcon className="size-4" aria-hidden />
                  {t("row.photo")}
                </span>
              ) : null}
              {last === "text" ? (
                <>
                  {c.lastMessage?.sender && !c.lastMessage.sender.self && c.type !== "DIRECT" ? <span className="font-medium">{text(personName(c.lastMessage.sender))}: </span> : null}
                  <span dir="auto">{preview}</span>
                </>
              ) : null}
            </p>
            {observing ? (
              <span className="inline-flex shrink-0 items-center gap-1 rounded-full bg-secondary px-2 py-0.5 text-xs font-semibold text-secondary-foreground">
                <EyeIcon className="size-3" aria-hidden />
                {t("row.observing")}
              </span>
            ) : null}
            {muted ? <BellOffIcon className="size-4 shrink-0 text-muted-foreground" aria-label={t("row.muted")} /> : null}
            {unread ? (
              <span
                data-testid="unread-badge"
                aria-label={t("row.unread", { count: c.unreadCount })}
                className="grid min-w-6 shrink-0 place-items-center rounded-full bg-primary px-1.5 text-xs leading-6 font-bold text-primary-foreground"
              >
                {unread}
              </span>
            ) : null}
          </div>
        </div>
      </Link>
    </li>
  );
}
