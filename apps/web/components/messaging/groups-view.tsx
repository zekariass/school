"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckIcon, PlusIcon, SettingsIcon, XIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { FormField } from "@/components/console/form-field";
import { SelectField } from "@/components/school/fields";
import { FormDialog } from "@/components/school/form-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Link } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { followsRoster, groupFormProblem, groupRequest, isClassBound, MIN_STUDENTS, needsTitle, pendingApprovals } from "@/lib/messaging/groups";
import { flattenInbox } from "@/lib/messaging/inbox";
import { MESSAGING_GC_MS, mk } from "@/lib/messaging/keys";
import { approveGroup, createGroup, fetchMyClasses, rejectGroup } from "@/lib/messaging/queries";
import { POSTING_POLICIES, type GroupAdmin, type GroupKind } from "@/lib/messaging/types";
import { MemberPicker } from "./member-picker";
import { useMessagesPlace } from "./messages-context";
import { SettingsDialog } from "./settings-dialog";
import { useCapabilities, useGroups, useInbox } from "./use-messaging";

export interface SchoolRef {
  id: string;
  name: string;
}

/**
 * Groups of a school: the ones the caller moderates, proposals waiting for their approval, and (when the API says they may)
 * creating one. Group lists are metadata only: a moderator who is not a member manages a group without reading it.
 */
export function GroupsView() {
  const t = useTranslations("school.messaging.groups");
  const { schools } = useMessagesPlace().viewer;
  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-col gap-1">
        <h1 className="text-3xl font-bold">{t("title")}</h1>
        <p className="text-muted-foreground">{t("subtitle")}</p>
      </header>
      {schools.length === 0 ? <EmptyNotice>{t("noSchools")}</EmptyNotice> : null}
      {schools.map((school) => (
        <SchoolGroups key={school.id} school={school} showName={schools.length > 1} />
      ))}
    </div>
  );
}

function SchoolGroups({ school, showName }: { school: SchoolRef; showName: boolean }) {
  const t = useTranslations("school.messaging.groups");
  const tKind = useTranslations("school.messaging.kind");
  const { base } = useMessagesPlace();
  const capabilities = useCapabilities(school.id);
  const groups = useGroups(school.id, undefined);
  const inbox = useInbox(school.id);
  const mine = new Set(flattenInbox(inbox.data?.pages ?? []).map((c) => c.id));
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<GroupAdmin | null>(null);
  const queryClient = useQueryClient();
  const caps = capabilities.data;
  const canCreate = !!caps && caps.featureEnabled && caps.creatableGroups.length > 0;
  const waiting = pendingApprovals(groups.data ?? []);

  const decide = useMutation({
    mutationFn: ({ id, approve }: { id: string; approve: boolean }) => (approve ? approveGroup(id) : rejectGroup(id)),
    onSuccess: (_, { approve }) => {
      void queryClient.invalidateQueries({ queryKey: mk.all });
      toast.success(approve ? t("approved") : t("rejected"));
    },
    onError: (e) => toast.error(errorMessage(e, t("failed"))),
  });

  return (
    <section aria-labelledby={`groups-${school.id}`} className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id={`groups-${school.id}`} className="text-xl font-bold">
          {showName ? school.name : t("yourGroups")}
        </h2>
        {canCreate ? (
          <Button type="button" onClick={() => setCreating(true)} data-testid="create-group">
            <PlusIcon aria-hidden />
            {t("createButton")}
          </Button>
        ) : null}
      </div>
      {capabilities.isSuccess && !caps?.featureEnabled ? <EmptyNotice>{t("disabled")}</EmptyNotice> : null}

      {waiting.length > 0 ? (
        <div className="flex flex-col gap-2 rounded-2xl border border-primary/40 bg-primary/5 p-3" data-testid="approvals">
          <h3 className="font-semibold">{t("approvalsTitle")}</h3>
          <ul className="flex flex-col gap-2">
            {waiting.map((g) => (
              <li key={g.id} className="flex flex-wrap items-center justify-between gap-2 rounded-xl bg-background p-2">
                <p className="min-w-0 font-medium">{g.title || tKind(g.groupKind)}</p>
                <div className="flex gap-2">
                  <Button type="button" size="sm" disabled={decide.isPending} onClick={() => decide.mutate({ id: g.id, approve: true })} data-testid="approve">
                    <CheckIcon aria-hidden />
                    {t("approve")}
                  </Button>
                  <Button type="button" size="sm" variant="outline" disabled={decide.isPending} onClick={() => decide.mutate({ id: g.id, approve: false })} data-testid="reject">
                    <XIcon aria-hidden />
                    {t("reject")}
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {groups.isPending ? <LoadingRows rows={2} /> : null}
      {groups.isError ? <ErrorNotice message={errorMessage(groups.error, t("loadFailed"))} onRetry={() => void groups.refetch()} /> : null}
      {groups.isSuccess && groups.data.length === 0 ? <EmptyNotice>{t("empty")}</EmptyNotice> : null}
      <ul className="flex flex-col gap-2">
        {(groups.data ?? []).map((g) => (
          <li key={g.id} className="flex flex-wrap items-center gap-3 rounded-2xl border bg-card p-3" data-testid="group-row">
            <div className="min-w-0 flex-1">
              <p className="truncate font-semibold">{g.title || tKind(g.groupKind)}</p>
              <p className="text-sm text-muted-foreground">
                {tKind(g.groupKind)} · {t("members", { count: g.memberCount })} · {t(`status.${statusKey(g.status)}`)}
                {g.proposedByMe ? ` · ${t("proposedByMe")}` : ""}
              </p>
            </div>
            {mine.has(g.id) ? (
              <Button asChild variant="outline" size="sm">
                <Link href={`${base}/${g.id}`}>{t("open")}</Link>
              </Button>
            ) : null}
            {g.status !== "PENDING_APPROVAL" && g.status !== "REJECTED" ? (
              <Button type="button" variant="ghost" size="sm" onClick={() => setEditing(g)} aria-label={t("manageNamed", { name: g.title || tKind(g.groupKind) })} data-testid="group-manage">
                <SettingsIcon aria-hidden />
                {t("manage")}
              </Button>
            ) : null}
          </li>
        ))}
      </ul>

      {creating && caps ? <CreateGroupDialog school={school} kinds={caps.creatableGroups} onClose={() => setCreating(false)} /> : null}
      {editing ? <SettingsDialog conversation={{ id: editing.id, title: editing.title, postingPolicy: editing.postingPolicy, status: editing.status }} onClose={() => setEditing(null)} /> : null}
    </section>
  );
}

function statusKey(status: string): "ACTIVE" | "LOCKED" | "ARCHIVED" | "PENDING_APPROVAL" | "REJECTED" {
  return status === "LOCKED" || status === "ARCHIVED" || status === "PENDING_APPROVAL" || status === "REJECTED" ? status : "ACTIVE";
}

function CreateGroupDialog({ school, kinds, onClose }: { school: SchoolRef; kinds: readonly GroupKind[]; onClose: () => void }) {
  const t = useTranslations("school.messaging.groups.create");
  const tKind = useTranslations("school.messaging.kind");
  const tPolicy = useTranslations("school.messaging.settings.postingPolicy");
  const [kind, setKind] = useState<GroupKind | "">(kinds[0] ?? "");
  const [classId, setClassId] = useState("");
  const [title, setTitle] = useState("");
  const [memberIds, setMemberIds] = useState<string[]>([]);
  const [postingPolicy, setPostingPolicy] = useState("");
  const [touched, setTouched] = useState(false);
  const classes = useQuery({ queryKey: ["messaging", "my-classes"], queryFn: fetchMyClasses, retry: false, gcTime: MESSAGING_GC_MS });
  const mine = (classes.data ?? []).filter((c) => c.schoolId === school.id && c.role === "TEACHER" && c.status === "ACTIVE");
  const problem = groupFormProblem({ kind, classId, title, memberIds });

  const create = useSubmit({
    mutationFn: (vars: ReturnType<typeof groupRequest>, key: string) => createGroup(school.id, vars, key),
    invalidate: [mk.all],
    successMessage: t("created"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: onClose,
  });

  const problemText =
    problem === "tooFewStudents" ? t("tooFewStudents", { min: MIN_STUDENTS }) : problem === "title" ? t("titleRequired") : problem === "class" ? t("classRequired") : problem === "members" ? t("membersRequired") : null;

  return (
    <FormDialog
      title={t("title")}
      description={t("description", { school: school.name })}
      submitLabel={t("submit")}
      pending={create.isPending}
      error={create.error ? errorMessage(create.error, t("failed")) : null}
      onClose={onClose}
      onSubmit={() => {
        setTouched(true);
        if (!problem && kind) create.mutate(groupRequest({ kind, classId, title, memberIds, postingPolicy }));
      }}
      className="sm:max-w-xl"
    >
      <SelectField
        label={t("kind")}
        value={kind}
        onChange={(v) => {
          setKind(v as GroupKind);
          setMemberIds([]);
        }}
        options={kinds.map((k) => ({ value: k, label: tKind(k) }))}
        hint={kind ? t(`kindHelp.${kind}`) : undefined}
      />
      {kind && isClassBound(kind) ? (
        <SelectField label={t("class")} value={classId} onChange={(v) => { setClassId(v); setMemberIds([]); }} placeholder={t("chooseClass")} options={mine.map((c) => ({ value: c.id, label: c.name }))} />
      ) : null}
      {kind && needsTitle(kind) ? (
        <FormField label={t("name")}>{(props) => <Input {...props} value={title} maxLength={120} onChange={(e) => setTitle(e.target.value)} />}</FormField>
      ) : null}
      {kind && !followsRoster(kind) ? (
        <MemberPicker schoolId={school.id} kind={kind} classId={classId || null} value={memberIds} onChange={(ids) => setMemberIds(ids)} label={kind === "STUDENT_GROUP" ? t("studentsLabel") : t("membersLabel")} />
      ) : null}
      {kind === "STUDENT_GROUP" ? (
        <p className="rounded-xl bg-primary/5 p-3 text-sm" data-testid="student-group-rule">
          {t("studentRule", { min: MIN_STUDENTS })}
        </p>
      ) : null}
      <SelectField
        label={t("posting")}
        value={postingPolicy}
        onChange={setPostingPolicy}
        noneLabel={t("postingDefault")}
        options={POSTING_POLICIES.map((value) => ({ value, label: tPolicy(value) }))}
      />
      {touched && problemText ? (
        <p role="alert" className="text-sm font-medium text-destructive">
          {problemText}
        </p>
      ) : null}
    </FormDialog>
  );
}
