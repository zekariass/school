"use client";

import { useLocale } from "next-intl";
import { useEffect, useState } from "react";
import { cn } from "@/lib/utils";
import { formatRelativeTime } from "@/lib/notifications/relative-time";
import type { NotificationItem } from "@/lib/notifications/types";

/** "now" for relative times, refreshed every minute so "2 minutes ago" does not go stale. */
function useNow() {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 60_000);
    return () => clearInterval(id);
  }, []);
  return now;
}

/** Title, body, relative time and an unread dot. The caller wraps it in the right control. */
export function NotificationContent({ item, className }: { item: NotificationItem; className?: string }) {
  const locale = useLocale();
  const now = useNow();
  const unread = !item.readAt;
  return (
    <span className={cn("flex min-w-0 items-start gap-3 text-start", className)}>
      <span
        aria-hidden
        className={cn("mt-2 size-2.5 shrink-0 rounded-full", unread ? "bg-primary" : "bg-transparent")}
      />
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className={cn("truncate text-sm", unread ? "font-semibold" : "font-medium")}>{item.title}</span>
        {item.body ? <span className="line-clamp-2 text-sm text-muted-foreground">{item.body}</span> : null}
        <time dateTime={item.createdAt} className="text-xs text-muted-foreground">
          {formatRelativeTime(item.createdAt, now, locale)}
        </time>
      </span>
    </span>
  );
}
