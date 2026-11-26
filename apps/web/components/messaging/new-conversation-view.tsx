"use client";

import { useQuery } from "@tanstack/react-query";
import { MessageSquarePlusIcon, ShieldAlertIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { SelectField } from "@/components/school/fields";
import { ConfirmDialog } from "@/components/school/confirm-dialog";
import { Button } from "@/components/ui/button";
import { Link, useRouter } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { familyKeys, fetchMyChildren, type MyChild } from "@/lib/family/queries";
import { MESSAGING_GC_MS, mk } from "@/lib/messaging/keys";
import { fetchMyClasses, startDirect } from "@/lib/messaging/queries";
import type { Contact } from "@/lib/messaging/types";
import { fetchRoster } from "@/lib/school/queries";

type MyClassItem = Awaited<ReturnType<typeof fetchMyClasses>>[number];
import { useMessagesPlace } from "./messages-context";
import { PersonBadge, usePersonText } from "./person-name";
import { contactName } from "@/lib/messaging/participants";
import { useCapabilities, useContacts } from "./use-messaging";

/**
 * Starting a conversation. There is no directory and no search: a guardian picks a child and then one of that child's
 * teachers (the API lists them); a teacher picks a class and a student and then one of the student's guardians (labels
 * only) or, when the school allows it, the student. A refusal is the API's generic "you can't do this here": nothing explains why.
 */
export function NewConversationView() {
  const t = useTranslations("school.messaging.start");
  const { school } = useMessagesPlace();
  const children = useQuery({ queryKey: familyKeys.children, queryFn: fetchMyChildren, retry: false, gcTime: MESSAGING_GC_MS });
  const classes = useQuery({ queryKey: ["messaging", "my-classes"], queryFn: fetchMyClasses, retry: false, gcTime: MESSAGING_GC_MS });
  const childList = (children.data?.children ?? []).filter((c) => !school || c.schools.some((s) => s.schoolId === school.id));
  const teaching = (classes.data ?? []).filter((c) => c.role === "TEACHER" && c.status === "ACTIVE" && (!school || c.schoolId === school.id));
  const loading = children.isPending || classes.isPending;

  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-col gap-1">
        <h1 className="text-3xl font-bold">{t("title")}</h1>
        <p className="text-muted-foreground">{t("subtitle")}</p>
      </header>
      {loading ? <LoadingRows rows={3} /> : null}
      {childList.length > 0 ? <GuardianStart kids={childList} /> : null}
      {teaching.length > 0 ? <TeacherStart classes={teaching} /> : null}
      {!loading && childList.length === 0 && teaching.length === 0 ? <EmptyNotice>{t("nobody")}</EmptyNotice> : null}
      {children.isError && classes.isError ? <ErrorNotice message={t("loadFailed")} /> : null}
    </div>
  );
}

function GuardianStart({ kids }: { kids: MyChild[] }) {
  const t = useTranslations("school.messaging.start");
  const [childId, setChildId] = useState(kids[0]?.studentId ?? "");
  const child = kids.find((c) => c.studentId === childId) ?? kids[0];
  const [schoolId, setSchoolId] = useState("");
  const school = child?.schools.find((s) => s.schoolId === schoolId) ?? child?.schools[0];
  const capabilities = useCapabilities(school?.schoolId);
  const teachers = useContacts(school?.schoolId, child?.studentId, "TEACHERS");
  const parents = useContacts(school?.schoolId, child?.studentId, "PARENTS", !!capabilities.data?.canStartParentParent);

  return (
    <section aria-labelledby="start-guardian" className="flex flex-col gap-4 rounded-2xl border bg-card p-4">
      <h2 id="start-guardian" className="text-xl font-bold">
        {t("guardianTitle")}
      </h2>
      <div className="grid gap-3 sm:grid-cols-2">
        {kids.length > 1 ? (
          <SelectField
            label={t("child")}
            value={child?.studentId ?? ""}
            onChange={(v) => {
              setChildId(v);
              setSchoolId("");
            }}
            options={kids.map((c) => ({ value: c.studentId, label: c.displayName }))}
          />
        ) : null}
        {child && child.schools.length > 1 ? (
          <SelectField label={t("school")} value={school?.schoolId ?? ""} onChange={setSchoolId} options={child.schools.map((s) => ({ value: s.schoolId, label: s.schoolName }))} />
        ) : null}
      </div>
      {child && school ? (
        <>
          <ContactList title={t("teachersOf", { child: child.givenName })} query={teachers} schoolId={school.schoolId} studentId={child.studentId} />
          {capabilities.data?.canStartParentParent && (parents.data?.length ?? 0) > 0 ? (
            <ContactList title={t("otherParents")} query={parents} schoolId={school.schoolId} studentId={child.studentId} />
          ) : null}
        </>
      ) : null}
    </section>
  );
}

function TeacherStart({ classes }: { classes: MyClassItem[] }) {
  const t = useTranslations("school.messaging.start");
  const [classId, setClassId] = useState(classes[0]?.id ?? "");
  const klass = classes.find((c) => c.id === classId) ?? classes[0];
  const roster = useQuery({
    queryKey: ["messaging", "picker", "roster", klass?.schoolId, klass?.id],
    queryFn: () => fetchRoster(klass!.schoolId, klass!.id),
    enabled: !!klass,
    gcTime: MESSAGING_GC_MS,
    retry: false,
  });
  const [studentId, setStudentId] = useState("");
  const students = roster.data ?? [];
  const student = students.find((s) => s.studentId === studentId);
  const capabilities = useCapabilities(klass?.schoolId);
  const guardians = useContacts(klass?.schoolId, student?.studentId, "TEACHERS");
  const [confirming, setConfirming] = useState(false);
  const router = useRouter();
  const { base } = useMessagesPlace();
  const direct = useSubmit({
    mutationFn: (vars: { recipientUserId: string }, key: string) => startDirect(klass!.schoolId, vars.recipientUserId, undefined, key),
    invalidate: [mk.inboxAll],
    errorFallback: t("failed"),
    onSuccess: (conversation) => router.push(`${base}/${conversation.summary.id}`),
  });

  return (
    <section aria-labelledby="start-teacher" className="flex flex-col gap-4 rounded-2xl border bg-card p-4">
      <h2 id="start-teacher" className="text-xl font-bold">
        {t("teacherTitle")}
      </h2>
      <div className="grid gap-3 sm:grid-cols-2">
        <SelectField
          label={t("class")}
          value={klass?.id ?? ""}
          onChange={(v) => {
            setClassId(v);
            setStudentId("");
          }}
          options={classes.map((c) => ({ value: c.id, label: c.schoolName && !classes.every((x) => x.schoolId === c.schoolId) ? `${c.name} (${c.schoolName})` : c.name }))}
        />
        <SelectField label={t("student")} value={studentId} onChange={setStudentId} placeholder={t("chooseStudent")} options={students.map((s) => ({ value: s.studentId, label: s.displayName }))} />
      </div>
      {roster.isError ? <ErrorNotice message={errorMessage(roster.error, t("loadFailed"))} /> : null}
      {klass && student ? (
        <>
          <ContactList title={t("guardiansOf", { student: student.displayName })} query={guardians} schoolId={klass.schoolId} studentId={student.studentId} />
          {capabilities.data?.canStartTeacherStudent ? (
            <div className="flex flex-col gap-2 rounded-xl border border-primary/40 bg-primary/5 p-3">
              <p className="flex items-start gap-2 text-sm">
                <ShieldAlertIcon className="mt-0.5 size-5 shrink-0 text-primary" aria-hidden />
                {t("studentNotice")}
              </p>
              <div>
                <Button type="button" variant="outline" onClick={() => setConfirming(true)} data-testid="start-student">
                  <MessageSquarePlusIcon aria-hidden />
                  {t("messageStudent", { student: student.displayName })}
                </Button>
              </div>
            </div>
          ) : null}
        </>
      ) : null}
      {confirming && student ? (
        <ConfirmDialog
          title={t("studentConfirmTitle", { student: student.displayName })}
          description={t("studentConfirmBody")}
          confirmLabel={t("studentConfirm")}
          destructive={false}
          pending={direct.isPending}
          error={direct.error ? errorMessage(direct.error, t("failed")) : null}
          onClose={() => {
            direct.reset();
            setConfirming(false);
          }}
          onConfirm={() => direct.mutate({ recipientUserId: student.studentId })}
        />
      ) : null}
    </section>
  );
}

/** People the API lists as possible recipients (teachers by name, guardians by label). One button each: start or open. */
function ContactList({
  title,
  query,
  schoolId,
  studentId,
}: {
  title: string;
  query: { isPending: boolean; isError: boolean; error: unknown; data: Contact[] | undefined; fetchStatus: string };
  schoolId: string;
  studentId: string;
}) {
  const t = useTranslations("school.messaging.start");
  const { contact } = usePersonText();
  const router = useRouter();
  const { base } = useMessagesPlace();
  const start = useSubmit({
    mutationFn: (vars: { recipientUserId: string }, key: string) => startDirect(schoolId, vars.recipientUserId, studentId, key),
    invalidate: [mk.inboxAll],
    errorFallback: t("failed"),
    onSuccess: (conversation) => router.push(`${base}/${conversation.summary.id}`),
  });
  const loading = query.isPending && query.fetchStatus !== "idle";
  return (
    <div className="flex flex-col gap-2">
      <h3 className="font-semibold">{title}</h3>
      {loading ? <LoadingRows rows={2} /> : null}
      {query.isError ? <p className="text-sm text-muted-foreground">{t("nobodyHere")}</p> : null}
      {query.data && query.data.length === 0 ? <p className="text-sm text-muted-foreground">{t("nobodyHere")}</p> : null}
      <ul className="flex flex-col gap-2">
        {(query.data ?? []).map((c) => {
          const name = contactName(c.label);
          const detail = [c.label?.subjectName, c.label?.className].filter(Boolean).join(" · ");
          return (
            <li key={c.userId} className="flex items-center gap-3 rounded-xl border p-2" data-testid="contact">
              <PersonBadge name={name} />
              <div className="min-w-0 flex-1">
                <p className="truncate font-semibold">{contact(c.label)}</p>
                {detail ? <p className="truncate text-sm text-muted-foreground">{detail}</p> : null}
              </div>
              {c.conversationId ? (
                <Button asChild variant="outline" size="sm">
                  <Link href={`${base}/${c.conversationId}`}>{t("open")}</Link>
                </Button>
              ) : (
                <Button type="button" size="sm" disabled={start.isPending} onClick={() => start.mutate({ recipientUserId: c.userId })} data-testid="start-with">
                  {t("message")}
                </Button>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
