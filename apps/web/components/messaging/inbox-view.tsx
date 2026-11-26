"use client";

import { PlusIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { EmptyNotice, ErrorNotice, LoadMore, LoadingRows } from "@/components/console/states";
import { Button } from "@/components/ui/button";
import { Link } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { flattenInbox, groupByChild, shouldGroupByChild } from "@/lib/messaging/inbox";
import { ConversationRow } from "./conversation-row";
import { useMessagesPlace } from "./messages-context";
import { useInbox } from "./use-messaging";

/**
 * The inbox: the conversations the caller may use right now, newest activity first. A guardian's list is grouped by child.
 * Unread counts, previews and the observer mark all come from the API; nothing is filtered here.
 */
export function InboxView() {
  const t = useTranslations("school.messaging");
  const { base, school, viewer } = useMessagesPlace();
  const canStart = viewer.canStart;
  const inbox = useInbox(school?.id ?? null);
  const items = flattenInbox(inbox.data?.pages ?? []);

  return (
    <div className="flex flex-col gap-5">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-3xl font-bold">{t("inbox.title")}</h1>
          <p className="text-muted-foreground">{school ? t("inbox.subtitleSchool", { school: school.name }) : t("inbox.subtitle")}</p>
        </div>
        {canStart ? (
          <Button asChild>
            <Link href={`${base}/new`} data-testid="new-conversation">
              <PlusIcon aria-hidden />
              {t("inbox.new")}
            </Link>
          </Button>
        ) : null}
      </header>

      {inbox.isPending ? <LoadingRows rows={5} /> : null}
      {inbox.isError ? <ErrorNotice message={errorMessage(inbox.error, t("inbox.loadFailed"))} onRetry={() => void inbox.refetch()} /> : null}
      {inbox.isSuccess && items.length === 0 ? <EmptyNotice>{canStart ? t("inbox.empty") : t("inbox.emptyStudent")}</EmptyNotice> : null}

      {items.length > 0 ? (
        shouldGroupByChild(items) ? (
          <div className="flex flex-col gap-6">
            {groupByChild(items).map((group) => (
              <section key={group.studentId ?? "none"} aria-labelledby={`child-${group.studentId ?? "none"}`} className="flex flex-col gap-2">
                <h2 id={`child-${group.studentId ?? "none"}`} className="text-lg font-bold">
                  {group.childName ? t("inbox.aboutChild", { child: group.childName }) : t("inbox.otherConversations")}
                </h2>
                <ul className="flex flex-col gap-2">
                  {group.items.map((c) => (
                    <ConversationRow key={c.id} conversation={c} showChild={false} />
                  ))}
                </ul>
              </section>
            ))}
          </div>
        ) : (
          <ul className="flex flex-col gap-2">
            {items.map((c) => (
              <ConversationRow key={c.id} conversation={c} />
            ))}
          </ul>
        )
      ) : null}
      <LoadMore hasMore={!!inbox.hasNextPage} loading={inbox.isFetchingNextPage} onClick={() => void inbox.fetchNextPage()} />
    </div>
  );
}
