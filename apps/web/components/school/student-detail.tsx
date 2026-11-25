"use client";

import { useQuery } from "@tanstack/react-query";
import { ArrowLeftIcon, KeyRoundIcon, PencilIcon, PrinterIcon, QrCodeIcon, UserRoundXIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { useFormatDate, useFormatInstant } from "@/components/calendar-preference";
import { FormField } from "@/components/console/form-field";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Link } from "@/i18n/navigation";
import { ensureOk, unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage, fieldErrors } from "@/lib/console/errors";
import { SP } from "@/lib/school/access";
import type { CodeKind, CodeSheet } from "@/lib/school/codes";
import { fetchStudent, fetchStudentCodes, sk, type StudentView } from "@/lib/school/queries";
import { ConfirmDialog } from "./confirm-dialog";
import { StudentConsentCard } from "./consent-views";
import { GuardiansPanel } from "./guardians-panel";
import { SelectField } from "./fields";
import { FormDialog } from "./form-dialog";
import { IssuedCodeDialog } from "./issued-code-dialog";
import { useSchool } from "./school-context";
import { StudentAvatar } from "./student-avatar";
import { StudentFormDialog } from "./student-form";
import { useIssueCodes } from "./use-issue-codes";
import { useSchoolSetting } from "./use-setting";

/** One student: details (FULL) or just name and picture (BASIC), plus the actions their role allows. */
export function StudentDetail({ studentId }: { studentId: string }) {
  const t = useTranslations("school.students.detail");
  const { school, permissions } = useSchool();
  const student = useQuery({
    queryKey: sk.studentOne(school.id, studentId),
    queryFn: () => fetchStudent(school.id, studentId),
  });
  const back = `/s/${encodeURIComponent(school.slug)}/${permissions.has(SP.students) ? "students" : "classes"}`;

  if (student.isPending) return <LoadingRows rows={4} />;
  if (student.isError) {
    return (
      <div className="mx-auto flex max-w-3xl flex-col gap-4">
        <ErrorNotice message={errorMessage(student.error, t("loadFailed"))} onRetry={() => void student.refetch()} />
      </div>
    );
  }
  const data = student.data;
  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <Link href={back} className="inline-flex min-h-11 items-center gap-2 text-sm font-semibold text-primary hover:underline">
        <ArrowLeftIcon className="size-4 rtl:rotate-180" aria-hidden />
        {t("back")}
      </Link>
      <StudentCard student={data} />
      {data.accessLevel === "FULL" && permissions.has(SP.students) ? <LoginCodesCard student={data} /> : null}
      <GuardiansPanel student={data} />
      <StudentConsentCard student={data} />
    </div>
  );
}

/** The student's profile as a compact card (also used in the roster drawer). */
export function StudentCard({ student, compact = false }: { student: StudentView; compact?: boolean }) {
  const t = useTranslations("school.students.detail");
  const tStatus = useTranslations("school.students.status");
  const tGender = useTranslations("school.students.gender");
  const { school, permissions } = useSchool();
  const format = useFormatDate();
  const [editing, setEditing] = useState(false);
  const [withdrawing, setWithdrawing] = useState(false);
  const [settingPassword, setSettingPassword] = useState(false);
  const full = student.accessLevel === "FULL";
  const canManage = permissions.has(SP.students) && full;
  const passwordSetting = useSchoolSetting<boolean>("students.password_login.enabled");
  const withdrawn = student.status === "WITHDRAWN";
  const gender = student.gender
    ? ["FEMALE", "MALE", "OTHER"].includes(student.gender)
      ? tGender(student.gender as "FEMALE")
      : student.gender
    : "—";

  return (
    <Card className={compact ? "shadow-none" : undefined}>
      <CardContent className="flex flex-col gap-5">
        <div className="flex flex-wrap items-center gap-4">
          <StudentAvatar
            id={student.id}
            name={student.displayName}
            mediaId={student.avatar?.id}
            config={student.avatarConfig}
            size={compact ? 80 : 96}
          />
          <div className="min-w-0 flex-1">
            <h1 className={compact ? "truncate text-xl font-bold" : "truncate text-2xl font-bold"}>{student.displayName}</h1>
            <div className="flex flex-wrap items-center gap-2 pt-1">
              {student.status ? (
                <Badge variant={withdrawn ? "outline" : "default"}>{tStatus(student.status)}</Badge>
              ) : null}
              {(student.classes ?? []).map((c) => (
                <Badge key={c.id} variant="secondary">
                  {c.name}
                </Badge>
              ))}
            </div>
          </div>
          {canManage ? (
            <div className="flex flex-wrap gap-2">
              <Button variant="outline" onClick={() => setEditing(true)}>
                <PencilIcon className="size-4" aria-hidden />
                {t("edit")}
              </Button>
              {passwordSetting.value === true && student.username && !withdrawn ? (
                <Button variant="outline" onClick={() => setSettingPassword(true)}>
                  <KeyRoundIcon className="size-4" aria-hidden />
                  {t("setPassword")}
                </Button>
              ) : null}
              {!withdrawn ? (
                <Button variant="outline" className="text-destructive" onClick={() => setWithdrawing(true)}>
                  <UserRoundXIcon className="size-4" aria-hidden />
                  {t("withdraw")}
                </Button>
              ) : null}
            </div>
          ) : null}
        </div>

        {full ? (
          <dl className="grid gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
            <Field label={t("studentNumber")} value={student.studentNumber ?? "—"} mono />
            <Field label={t("username")} value={student.username ?? "—"} mono />
            <Field label={t("dateOfBirth")} value={student.dateOfBirth ? format(student.dateOfBirth) : "—"} />
            <Field label={t("gender")} value={gender} />
            <Field label={t("admission")} value={student.admissionOn ? format(student.admissionOn) : "—"} />
            {student.leftOn ? <Field label={t("left")} value={format(student.leftOn)} /> : null}
          </dl>
        ) : (
          <p className="rounded-xl bg-muted/60 p-3 text-sm text-muted-foreground">{t("basicNote")}</p>
        )}
      </CardContent>

      {editing ? <StudentFormDialog student={student} onClose={() => setEditing(false)} /> : null}
      {withdrawing ? <WithdrawDialog student={student} onClose={() => setWithdrawing(false)} /> : null}
      {settingPassword ? <PasswordDialog student={student} onClose={() => setSettingPassword(false)} /> : null}
      <span className="sr-only">{school.name}</span>
    </Card>
  );
}

function Field({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className={mono ? "font-mono" : "font-medium"}>{value}</dd>
    </div>
  );
}

function WithdrawDialog({ student, onClose }: { student: StudentView; onClose: () => void }) {
  const t = useTranslations("school.students.withdraw");
  const { school } = useSchool();
  const [reason, setReason] = useState("");
  const withdraw = useSubmit({
    mutationFn: async () =>
      unwrap(
        await getBrowserApi().POST("/api/v1/schools/{schoolId}/students/{studentId}/withdraw", {
          params: { path: { schoolId: school.id, studentId: student.id } },
          body: { reason: reason.trim() || undefined },
        }),
      ),
    invalidate: [sk.students(school.id), sk.classes(school.id), sk.summary(school.id)],
    successMessage: t("done"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: onClose,
  });
  return (
    <FormDialog
      title={t("title", { name: student.displayName })}
      description={t("body")}
      submitLabel={t("confirm")}
      pending={withdraw.isPending}
      error={withdraw.isError ? errorMessage(withdraw.error, t("failed")) : null}
      onSubmit={() => withdraw.mutate()}
      onClose={onClose}
    >
      <FormField label={t("reason")} hint={t("reasonHint")}>
        {(props) => <Textarea {...props} rows={2} maxLength={200} value={reason} onChange={(e) => setReason(e.target.value)} />}
      </FormField>
    </FormDialog>
  );
}

function PasswordDialog({ student, onClose }: { student: StudentView; onClose: () => void }) {
  const t = useTranslations("school.students.password");
  const tv = useTranslations("school.validation");
  const { school } = useSchool();
  const [password, setPassword] = useState("");
  const [local, setLocal] = useState<"required" | "short" | null>(null);
  const save = useSubmit({
    mutationFn: async () => {
      ensureOk(
        await getBrowserApi().POST("/api/v1/schools/{schoolId}/students/{studentId}/password", {
          params: { path: { schoolId: school.id, studentId: student.id } },
          body: { password },
        }),
      );
    },
    successMessage: t("done"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: onClose,
  });
  const server = fieldErrors(save.error);
  return (
    <FormDialog
      title={t("title", { name: student.displayName })}
      description={t("body")}
      submitLabel={t("confirm")}
      pending={save.isPending}
      error={save.isError && !server.password ? errorMessage(save.error, t("failed")) : null}
      onSubmit={() => {
        const error = password.trim() === "" ? "required" : password.length < 8 ? "short" : null;
        setLocal(error);
        if (!error) save.mutate();
      }}
      onClose={onClose}
    >
      <FormField label={t("label")} hint={t("hint")} error={local ? tv(local) : (server.password ?? null)}>
        {(props) => (
          <Input
            {...props}
            type="password"
            autoComplete="new-password"
            maxLength={128}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        )}
      </FormField>
    </FormDialog>
  );
}

/** Metadata of the student's live codes, with "Issue new code" (shown once) and "Revoke". */
function LoginCodesCard({ student }: { student: StudentView }) {
  const t = useTranslations("school.students.codes");
  const tKind = useTranslations("school.codes.kind");
  const { school } = useSchool();
  const formatInstant = useFormatInstant();
  const classes = student.classes ?? [];
  const [classId, setClassId] = useState(classes[0]?.id ?? "");
  const [kind, setKind] = useState<CodeKind>("TEXT");
  const [sheet, setSheet] = useState<CodeSheet | null>(null);
  const [revoking, setRevoking] = useState(false);
  const codes = useQuery({
    queryKey: sk.studentCodes(school.id, student.id),
    queryFn: () => fetchStudentCodes(school.id, student.id),
  });
  const issue = useIssueCodes();
  const revoke = useSubmit({
    mutationFn: async () =>
      unwrap(
        await getBrowserApi().POST("/api/v1/schools/{schoolId}/students/{studentId}/login-codes/revoke", {
          params: { path: { schoolId: school.id, studentId: student.id } },
        }),
      ),
    invalidate: [sk.studentCodes(school.id, student.id)],
    successMessage: t("revoked"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: () => setRevoking(false),
  });

  const chosen = classes.find((c) => c.id === classId) ?? classes[0];
  const withdrawn = student.status === "WITHDRAWN";

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-lg">
          <QrCodeIcon className="size-5" aria-hidden />
          {t("title")}
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <p className="text-sm text-muted-foreground">{t("intro")}</p>
        {codes.isPending ? (
          <LoadingRows rows={1} />
        ) : codes.isError ? (
          <ErrorNotice message={errorMessage(codes.error, t("loadFailed"))} onRetry={() => void codes.refetch()} />
        ) : codes.data.length === 0 ? (
          <EmptyNotice>{t("none")}</EmptyNotice>
        ) : (
          <ul className="flex flex-col gap-2">
            {codes.data.map((c) => (
              <li key={c.id} className="flex flex-wrap items-center gap-2 rounded-xl border px-3 py-2 text-sm">
                <Badge variant="secondary">{tKind(c.type)}</Badge>
                <span className="text-muted-foreground">{t("created", { date: formatInstant(c.createdAt) })}</span>
                {c.expiresAt ? (
                  <span className={c.expired ? "font-semibold text-destructive" : "text-muted-foreground"}>
                    {c.expired ? t("expired") : t("expires", { date: formatInstant(c.expiresAt) })}
                  </span>
                ) : null}
                <span className="ms-auto text-muted-foreground">
                  {c.lastUsedAt ? t("lastUsed", { date: formatInstant(c.lastUsedAt) }) : t("neverUsed")}
                </span>
              </li>
            ))}
          </ul>
        )}

        {withdrawn ? null : classes.length === 0 ? (
          <p className="rounded-xl bg-muted/60 p-3 text-sm text-muted-foreground">{t("needsClass")}</p>
        ) : (
          <div className="flex flex-wrap items-end gap-3">
            {classes.length > 1 ? (
              <SelectField
                className="min-w-48"
                label={t("class")}
                value={classId}
                onChange={setClassId}
                options={classes.map((c) => ({ value: c.id, label: c.name }))}
              />
            ) : null}
            <SelectField
              className="min-w-40"
              label={t("kind")}
              value={kind}
              onChange={(v) => setKind(v as CodeKind)}
              options={(["TEXT", "QR", "BOTH"] as const).map((k) => ({ value: k, label: tKind(k) }))}
            />
            <Button
              disabled={issue.isPending || !chosen}
              onClick={() =>
                chosen &&
                issue.mutate(
                  { classId: chosen.id, className: chosen.name, studentIds: [student.id], kind },
                  { onSuccess: (result) => setSheet(result) },
                )
              }
            >
              <PrinterIcon className="size-4" aria-hidden />
              {codes.data && codes.data.length > 0 ? t("issueNew") : t("issue")}
            </Button>
            {codes.data && codes.data.length > 0 ? (
              <Button variant="outline" onClick={() => setRevoking(true)}>
                {t("revoke")}
              </Button>
            ) : null}
          </div>
        )}
        {issue.isError ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {errorMessage(issue.error, t("failed"))}
          </p>
        ) : null}
        {codes.data && codes.data.length > 0 ? <p className="text-sm text-muted-foreground">{t("replaceNote")}</p> : null}
      </CardContent>

      {sheet ? (
        <IssuedCodeDialog
          sheet={sheet}
          onClose={() => {
            setSheet(null);
            issue.reset(); // drop the plaintext from memory
          }}
        />
      ) : null}
      {revoking ? (
        <ConfirmDialog
          title={t("revokeTitle", { name: student.displayName })}
          description={t("revokeBody")}
          confirmLabel={t("revokeConfirm")}
          pending={revoke.isPending}
          error={revoke.isError ? errorMessage(revoke.error, t("failed")) : null}
          onConfirm={() => revoke.mutate()}
          onClose={() => setRevoking(false)}
        />
      ) : null}
    </Card>
  );
}
