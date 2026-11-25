"use client";

import { useQuery } from "@tanstack/react-query";
import { HeartHandshakeIcon, MailPlusIcon, PrinterIcon, ShieldAlertIcon, UserPlusIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { useFormatInstant } from "@/components/calendar-preference";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage } from "@/lib/console/errors";
import { familyAbilities } from "@/lib/school/families";
import {
  fetchStudentGuardians,
  fetchStudentInvitations,
  sk,
  type GuardianInvitation,
  type GuardianLink,
  type StudentView,
} from "@/lib/school/queries";
import type { ParentCodeSheet } from "@/lib/school/parent-codes";
import { ConfirmDialog } from "./confirm-dialog";
import { SelectField } from "./fields";
import {
  AddGuardianDialog,
  DecisionDialog,
  EditLinkDialog,
  InviteGuardianDialog,
  RevokeLinkDialog,
} from "./guardian-dialogs";
import { ParentCodeDialog } from "./parent-code-dialog";
import { useSchool } from "./school-context";
import { useIssueParentCodes } from "./use-issue-parent-codes";

type Dialog =
  | { kind: "invite" }
  | { kind: "add" }
  | { kind: "edit"; link: GuardianLink }
  | { kind: "revoke"; link: GuardianLink }
  | { kind: "decide"; link: GuardianLink; decision: "approve" | "reject" };

/**
 * The guardians of one student. Administrators see every link with masked contact details and
 * manage it; teachers see names, relationship and status of PENDING and ACTIVE links, and the API
 * never sends them restricted links or any trace of a restriction.
 */
export function GuardiansPanel({ student }: { student: StudentView }) {
  const t = useTranslations("school.families.panel");
  const { school, permissions, isTeacher, isStaff } = useSchool();
  const abilities = familyAbilities({ permissions, isTeacher, isStaff, isStudent: false });
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [codeSheet, setCodeSheet] = useState<ParentCodeSheet | null>(null);
  const issue = useIssueParentCodes();
  const classes = student.classes ?? [];
  const [classId, setClassId] = useState(classes[0]?.id ?? "");
  const chosen = classes.find((c) => c.id === classId) ?? classes[0];
  const withdrawn = student.status === "WITHDRAWN";

  const links = useQuery({
    queryKey: sk.studentGuardians(school.id, student.id),
    queryFn: () => fetchStudentGuardians(school.id, student.id),
    enabled: abilities.canSeeGuardians,
  });

  if (!abilities.canSeeGuardians) return null;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-lg">
          <HeartHandshakeIcon className="size-5" aria-hidden />
          {t("title")}
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <p className="text-sm text-muted-foreground">{t("intro", { name: student.displayName })}</p>
        {!abilities.canManageLinks ? (
          <p className="rounded-xl bg-muted/60 p-3 text-sm text-muted-foreground">{t("teacherNote")}</p>
        ) : null}

        {links.isPending ? (
          <LoadingRows rows={2} />
        ) : links.isError ? (
          <ErrorNotice message={errorMessage(links.error, t("loadFailed"))} onRetry={() => void links.refetch()} />
        ) : visibleLinks(links.data, abilities.canManageLinks).length === 0 ? (
          <EmptyNotice>{t("none")}</EmptyNotice>
        ) : (
          <ul className="flex flex-col gap-2">
            {visibleLinks(links.data, abilities.canManageLinks).map((link) => (
              <GuardianRow
                key={link.id}
                link={link}
                canVerify={abilities.canVerify}
                canManage={abilities.canManageLinks}
                onDialog={setDialog}
              />
            ))}
          </ul>
        )}

        {!withdrawn ? (
          <div className="flex flex-wrap items-end gap-3">
            {abilities.canInvite ? (
              <Button variant="outline" onClick={() => setDialog({ kind: "invite" })}>
                <MailPlusIcon className="size-4" aria-hidden />
                {t("invite")}
              </Button>
            ) : null}
            {abilities.canManageLinks ? (
              <Button variant="outline" onClick={() => setDialog({ kind: "add" })}>
                <UserPlusIcon className="size-4" aria-hidden />
                {t("add")}
              </Button>
            ) : null}
            {abilities.canInvite && classes.length > 0 ? (
              <>
                {classes.length > 1 ? (
                  <SelectField
                    className="min-w-48"
                    label={t("codeClass")}
                    value={classId}
                    onChange={setClassId}
                    options={classes.map((c) => ({ value: c.id, label: c.name }))}
                  />
                ) : null}
                <Button
                  variant="outline"
                  disabled={issue.isPending || !chosen}
                  onClick={() =>
                    chosen &&
                    issue.mutate(
                      { classId: chosen.id, className: chosen.name, studentIds: [student.id] },
                      { onSuccess: (sheet) => setCodeSheet(sheet) },
                    )
                  }
                >
                  <PrinterIcon className="size-4" aria-hidden />
                  {t("issueCode")}
                </Button>
              </>
            ) : null}
          </div>
        ) : null}
        {issue.isError ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {errorMessage(issue.error, t("codeFailed"))}
          </p>
        ) : null}
        {abilities.canInvite && classes.length > 0 ? (
          <p className="text-sm text-muted-foreground">{t("codeReplaceNote")}</p>
        ) : null}

        {abilities.canInvite ? <InvitationsList studentId={student.id} /> : null}
      </CardContent>

      {dialog?.kind === "invite" ? (
        <InviteGuardianDialog studentId={student.id} studentName={student.displayName} onClose={() => setDialog(null)} />
      ) : null}
      {dialog?.kind === "add" ? (
        <AddGuardianDialog studentId={student.id} studentName={student.displayName} onClose={() => setDialog(null)} />
      ) : null}
      {dialog?.kind === "edit" ? <EditLinkDialog link={dialog.link} onClose={() => setDialog(null)} /> : null}
      {dialog?.kind === "revoke" ? <RevokeLinkDialog link={dialog.link} onClose={() => setDialog(null)} /> : null}
      {dialog?.kind === "decide" ? (
        <DecisionDialog link={dialog.link} decision={dialog.decision} onClose={() => setDialog(null)} />
      ) : null}
      {codeSheet ? (
        <ParentCodeDialog
          sheet={codeSheet}
          onClose={() => {
            setCodeSheet(null);
            issue.reset(); // drop the plaintext from memory
          }}
        />
      ) : null}
    </Card>
  );
}

/**
 * Only administrators see custody-restricted links. The API already withholds them from teachers; this
 * keeps the screen free of any trace of a restriction should a response ever carry one (defense in depth,
 * never the only check).
 */
function visibleLinks(links: GuardianLink[], canManage: boolean): GuardianLink[] {
  return canManage ? links : links.filter((l) => !l.custodyRestricted);
}

function GuardianRow({
  link,
  canVerify,
  canManage,
  onDialog,
}: {
  link: GuardianLink;
  canVerify: boolean;
  canManage: boolean;
  onDialog: (dialog: Dialog) => void;
}) {
  const t = useTranslations("school.families.panel");
  const tq = useTranslations("school.families.queue");
  const tRel = useTranslations("family.relationship");
  const live = link.status === "PENDING" || link.status === "ACTIVE";
  const contact = [link.guardianPhone, link.guardianEmail].filter(Boolean).join(" · ");
  return (
    <li className="flex flex-col gap-2 rounded-xl border px-3 py-3 text-sm" data-testid="guardian-row">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-base font-semibold">{link.guardianName}</span>
        <Badge variant="secondary">{tRel(link.relationshipType)}</Badge>
        <Badge variant={link.status === "ACTIVE" ? "default" : "outline"}>{t(`status.${link.status}`)}</Badge>
        {link.primary ? <Badge variant="outline">{t("primary")}</Badge> : null}
        {link.custodyRestricted && canManage ? (
          <Badge variant="destructive">
            <ShieldAlertIcon aria-hidden />
            {t("restricted")}
          </Badge>
        ) : null}
      </div>
      {contact ? <p className="text-muted-foreground">{t("contact", { contact })}</p> : null}
      {link.invitedName && link.invitedName !== link.guardianName ? (
        <p className="text-muted-foreground">{t("invitedName", { name: link.invitedName })}</p>
      ) : null}
      {link.restrictionNote && canManage ? <p className="text-muted-foreground">{t("restrictionNote", { note: link.restrictionNote })}</p> : null}
      <div className="flex flex-wrap gap-2">
        {link.status === "PENDING" && canVerify ? (
          <>
            <Button size="sm" onClick={() => onDialog({ kind: "decide", link, decision: "approve" })}>
              {tq("approve")}
            </Button>
            <Button size="sm" variant="outline" onClick={() => onDialog({ kind: "decide", link, decision: "reject" })}>
              {tq("reject")}
            </Button>
          </>
        ) : null}
        {canManage && live ? (
          <>
            <Button size="sm" variant="outline" onClick={() => onDialog({ kind: "edit", link })}>
              {t("edit")}
            </Button>
            <Button size="sm" variant="outline" className="text-destructive" onClick={() => onDialog({ kind: "revoke", link })}>
              {t("revoke")}
            </Button>
          </>
        ) : null}
      </div>
    </li>
  );
}

function InvitationsList({ studentId }: { studentId: string }) {
  const t = useTranslations("school.families.panel");
  const { school } = useSchool();
  const formatInstant = useFormatInstant();
  const [cancelling, setCancelling] = useState<GuardianInvitation | null>(null);
  const invitations = useQuery({
    queryKey: sk.studentInvitations(school.id, studentId),
    queryFn: () => fetchStudentInvitations(school.id, studentId),
    retry: false,
  });
  const cancel = useSubmit({
    mutationFn: async (invitation: GuardianInvitation) =>
      unwrap(
        await getBrowserApi().POST("/api/v1/schools/{schoolId}/guardian-invitations/{invitationId}/revoke", {
          params: { path: { schoolId: school.id, invitationId: invitation.id } },
        }),
      ),
    invalidate: [sk.studentInvitations(school.id, studentId)],
    successMessage: t("cancelled"),
    errorFallback: t("loadFailed"),
    toastOnError: false,
    onSuccess: () => setCancelling(null),
  });

  // Teachers who may not list invitations simply see nothing here (the API answers 403).
  if (invitations.isPending || invitations.isError || invitations.data.length === 0) return null;

  return (
    <div className="flex flex-col gap-2 border-t pt-4">
      <h3 className="text-sm font-semibold">{t("invitationsTitle")}</h3>
      <ul className="flex flex-col gap-2">
        {invitations.data.map((inv) => {
          const live = inv.status === "PENDING" || inv.status === "SENT";
          return (
            <li key={inv.id} className="flex flex-wrap items-center gap-2 rounded-xl border px-3 py-2 text-sm">
              <Badge variant="secondary">{t(`channel.${inv.channel}`)}</Badge>
              <Badge variant="outline">
                {t(inv.channel === "PRINTED_CODE" && live ? "invitation.ACTIVE" : `invitation.${inv.status}`)}
              </Badge>
              {inv.destination ? <span className="text-muted-foreground">{inv.destination}</span> : null}
              {inv.channel === "PRINTED_CODE" ? (
                <span className="text-muted-foreground">{t("usesOf", { used: inv.useCount, max: inv.maxUses })}</span>
              ) : null}
              <span className="text-muted-foreground">{t("expires", { date: formatInstant(inv.expiresAt) })}</span>
              {live ? (
                <Button size="sm" variant="ghost" className="ms-auto" onClick={() => setCancelling(inv)}>
                  {t("cancelInvitation")}
                </Button>
              ) : null}
            </li>
          );
        })}
      </ul>
      {cancelling ? (
        <ConfirmDialog
          title={t("cancelTitle")}
          description={t("cancelBody")}
          confirmLabel={t("cancelConfirm")}
          pending={cancel.isPending}
          error={cancel.isError ? errorMessage(cancel.error, t("loadFailed")) : null}
          onConfirm={() => cancel.mutate(cancelling)}
          onClose={() => setCancelling(null)}
        />
      ) : null}
    </div>
  );
}
