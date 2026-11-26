"use client";

import { MessageCircleIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/navigation";
import { unreadBadge } from "@/lib/messaging/inbox";
import { useUnreadCount } from "./use-messaging";

/**
 * The header's door to messages, with the number of unread messages (the API's total over every conversation the caller may
 * use now). The badge is a number, and the label says it in words too. Hidden on phones: the header has no room, and the
 * family tabs and the workspace menu carry the same badge there.
 */
export function MessagesLink() {
  const t = useTranslations("school.messaging.link");
  const unread = useUnreadCount();
  const count = unread.data?.unread ?? 0;
  const badge = unreadBadge(count);
  return (
    <Link
      href="/messages"
      aria-label={count > 0 ? t("unread", { count }) : t("label")}
      title={t("label")}
      data-testid="messages-link"
      className="relative hidden size-10 shrink-0 items-center justify-center rounded-xl sm:inline-flex text-foreground transition-colors hover:bg-accent hover:text-accent-foreground focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none sm:size-11"
    >
      <MessageCircleIcon className="size-5" aria-hidden />
      {badge ? (
        <span data-testid="messages-badge" className="absolute end-1 top-1 grid min-w-5 place-items-center rounded-full bg-destructive px-1 text-[0.7rem] leading-5 font-bold text-white">
          {badge}
        </span>
      ) : null}
    </Link>
  );
}
