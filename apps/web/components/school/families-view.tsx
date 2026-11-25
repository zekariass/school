"use client";

import { useInfiniteQuery } from "@tanstack/react-query";
import { CheckCircle2Icon, SettingsIcon, TriangleAlertIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { useFormatInstant } from "@/components/calendar-preference";
import { EmptyNotice, ErrorNotice, LoadMore, LoadingRows } from "@/components/console/states";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Link } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { SP } from "@/lib/school/access";
import { contactComparison, familyAbilities, queueStatuses, type LinkStatus } from "@/lib/school/families";
import { fetchLinkQueue, sk, type GuardianLink } from "@/lib/school/queries";
import { SelectField } from "./fields";
import { DecisionDialog } from "./guardian-dialogs";
import { PageHeader } from "./page-header";
import { RequirePermission } from "./require-permission";
import { useSchool } from "./school-context";

/**
 * The verification queue: links waiting for the school, with the invited contact set against the
 * one the adult proved, so whoever confirms can spot a request that does not belong.
 */
export function FamiliesView() {
  const t = useTranslations("school.families");
  const tq = useTranslations("school.families.queue");
  const { school, permissions, isTeacher, isStaff } = useSchool();
  const abilities = familyAbilities({ permissions, isTeacher, isStaff, isStudent: false });
  const statuses = queueStatuses(abilities.canManageLinks);
  const [status, setStatus] = useState<LinkStatus>("PENDING");
  const [deciding, setDeciding] = useState<{ link: GuardianLink; decision: "approve" | "reject" } | null>(null);

  const queue = useInfiniteQuery({
    queryKey: sk.linkQueue(school.id, status),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => fetchLinkQueue(school.id, status, pageParam),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled: abilities.canVerify,
    retry: false,
  });
  const items = queue.data?.pages.flatMap((p) => p.items) ?? [];

  // Neither an administrator of guardians nor a teacher: the same friendly notice as a typed URL.
  if (!abilities.canSeeGuardians) return <RequirePermission anyOf={[]}>{null}</RequirePermission>;

  return (
    <>
      <div className="mx-auto flex max-w-4xl flex-col gap-6">
        <PageHeader
          title={t("title")}
          description={t("subtitle")}
          actions={
            permissions.has(SP.settings) ? (
              <Button variant="outline" asChild>
                <Link href={`/s/${encodeURIComponent(school.slug)}/settings`}>
                  <SettingsIcon className="size-4" aria-hidden />
                  {t("settingsLink")}
                </Link>
              </Button>
            ) : null
          }
        />

        {statuses.length > 1 ? (
          <SelectField
            className="max-w-56"
            label={tq("filter")}
            value={status}
            onChange={(v) => setStatus(v as LinkStatus)}
            options={statuses.map((s) => ({ value: s, label: tq(`status.${s}`) }))}
          />
        ) : null}

        {queue.isPending ? (
          <LoadingRows rows={3} />
        ) : queue.isError ? (
          <div className="flex flex-col gap-3">
            <ErrorNotice message={errorMessage(queue.error, tq("loadFailed"))} onRetry={() => void queue.refetch()} />
            {!abilities.canManageLinks ? (
              <p className="rounded-xl bg-muted/60 p-3 text-sm text-muted-foreground">{tq("restrictedForTeachers")}</p>
            ) : null}
          </div>
        ) : items.length === 0 ? (
          <EmptyNotice>{status === "PENDING" ? tq("emptyPending") : tq("empty")}</EmptyNotice>
        ) : (
          <ul className="flex flex-col gap-3">
            {items.map((link) => (
              <QueueRow
                key={link.id}
                link={link}
                onDecide={(decision) => setDeciding({ link, decision })}
                showActions={status === "PENDING"}
              />
            ))}
          </ul>
        )}
        <LoadMore hasMore={!!queue.hasNextPage} loading={queue.isFetchingNextPage} onClick={() => void queue.fetchNextPage()} />
      </div>
      {deciding ? <DecisionDialog link={deciding.link} decision={deciding.decision} onClose={() => setDeciding(null)} /> : null}
    </>
  );
}

function QueueRow({
  link,
  onDecide,
  showActions,
}: {
  link: GuardianLink;
  onDecide: (decision: "approve" | "reject") => void;
  showActions: boolean;
}) {
  const t = useTranslations("school.families.queue");
  const tRel = useTranslations("family.relationship");
  const formatInstant = useFormatInstant();
  const comparison = contactComparison(link);
  const contact = [link.guardianPhone, link.guardianEmail].filter(Boolean).join(" · ");
  return (
    <li>
      <Card className="shadow-none" data-testid="queue-row">
        <CardContent className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-lg font-bold">{link.studentName}</span>
            <span className="text-muted-foreground">←</span>
            <span className="text-lg font-semibold">{link.guardianName}</span>
            <Badge variant="secondary">{tRel(link.relationshipType)}</Badge>
            <Badge variant="outline">{t(`via.${link.requestedVia}`)}</Badge>
          </div>
          <p className="text-sm text-muted-foreground">{t("requestedOn", { date: formatInstant(link.createdAt) })}</p>
          {link.invitedName ? <p className="text-sm">{t("invitedAs", { name: link.invitedName })}</p> : null}
          {contact ? <p className="text-sm">{t("actualContact", { contact })}</p> : null}
          {comparison === "matches" ? (
            <p className="flex items-center gap-2 text-sm font-medium text-success">
              <CheckCircle2Icon className="size-4 shrink-0" aria-hidden />
              {t("contactMatches")}
            </p>
          ) : comparison === "differs" ? (
            <p className="flex items-center gap-2 rounded-xl bg-warning/15 p-2 text-sm font-medium">
              <TriangleAlertIcon className="size-4 shrink-0" aria-hidden />
              {t("contactDiffers")}
            </p>
          ) : null}
          {link.decisionNote ? <p className="text-sm text-muted-foreground">{link.decisionNote}</p> : null}
          {showActions ? (
            <div className="flex flex-wrap gap-2">
              <Button onClick={() => onDecide("approve")}>{t("approve")}</Button>
              <Button variant="outline" onClick={() => onDecide("reject")}>
                {t("reject")}
              </Button>
            </div>
          ) : null}
        </CardContent>
      </Card>
    </li>
  );
}
