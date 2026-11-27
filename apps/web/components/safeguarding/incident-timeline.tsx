"use client";

import { useTranslations } from "next-intl";
import { RelativeTime } from "@/components/feed/relative-time";
import { EmptyNotice } from "@/components/console/states";
import { INCIDENT_STATUSES, type IncidentNote } from "@/lib/moderation/types";
import { knownOr } from "@/lib/moderation/view";

const NOTE_KINDS = ["NOTE", "STATUS", "CLOSE", "REOPEN", "ASSIGN", "REFERRAL", "GUARDIAN_INFORMED"] as const;

/**
 * The notes and status changes of an incident, oldest first, exactly as the API sent them (decrypted for this read). A
 * note's author is "you" or "another lead": names of staff are never needed here and the API sends only an id.
 */
export function IncidentTimeline({ notes, userId }: { notes: readonly IncidentNote[]; userId: string }) {
  const t = useTranslations("safeguarding.incident.timeline");
  const ts = useTranslations("safeguarding.incidents.status");
  const sorted = [...notes].sort((a, b) => (a.createdAt ?? "").localeCompare(b.createdAt ?? ""));
  return (
    <section aria-label={t("title")} className="flex flex-col gap-2" data-testid="incident-timeline">
      <h2 className="text-lg font-bold">{t("title")}</h2>
      {sorted.length === 0 ? <EmptyNotice>{t("none")}</EmptyNotice> : null}
      <ol className="flex flex-col gap-2">
        {sorted.map((n, index) => {
          const kind = knownOr(n.kind, NOTE_KINDS, "NOTE");
          return (
            <li key={n.id ?? index} className="flex flex-col gap-1 rounded-xl border bg-card p-3" data-testid="incident-note" data-kind={kind}>
              <p className="flex flex-wrap items-center gap-x-2 text-sm">
                <span className="font-semibold">{t(`kind.${kind}`)}</span>
                {n.statusTo ? (
                  <span className="text-muted-foreground">
                    {n.statusFrom ? `${ts(knownOr(n.statusFrom, INCIDENT_STATUSES, "OPEN"))} → ` : ""}
                    {ts(knownOr(n.statusTo, INCIDENT_STATUSES, "OPEN"))}
                  </span>
                ) : null}
                <span className="text-muted-foreground">{n.authorId === userId ? t("you") : t("someone")}</span>
                {n.createdAt ? <RelativeTime instant={n.createdAt} className="text-xs text-muted-foreground" /> : null}
              </p>
              {n.note ? (
                <p dir="auto" className="whitespace-pre-wrap [overflow-wrap:anywhere]" data-testid="incident-note-text">
                  {n.note}
                </p>
              ) : null}
              {n.attachmentMediaId ? <p className="text-xs text-muted-foreground">{t("hasAttachment")}</p> : null}
            </li>
          );
        })}
      </ol>
    </section>
  );
}
