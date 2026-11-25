"use client";

import { useQuery } from "@tanstack/react-query";
import { CheckCircle2Icon, CircleHelpIcon, ShieldCheckIcon, XCircleIcon } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { useState } from "react";
import { useFormatInstant } from "@/components/calendar-preference";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { FormField } from "@/components/console/form-field";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { ApiRequestError } from "@/lib/api-error";
import { errorMessage } from "@/lib/console/errors";
import { consentGuardians, familyAbilities } from "@/lib/school/families";
import {
  fetchClassConsent,
  fetchEffectivePolicy,
  fetchStudentConsents,
  fetchStudentGuardians,
  sk,
  type ClassView,
  type StudentView,
} from "@/lib/school/queries";
import { SelectField } from "./fields";
import { FormDialog } from "./form-dialog";
import { useSchool } from "./school-context";

type Status = "GRANTED" | "DENIED" | "UNKNOWN";

const STATUS_ICON = { GRANTED: CheckCircle2Icon, DENIED: XCircleIcon, UNKNOWN: CircleHelpIcon } as const;

/** One effective status as a small badge (icon and words, never colour alone). */
export function ConsentBadge({ status, title }: { status: Status; title?: string }) {
  const t = useTranslations("school.families.consent.state");
  const Icon = STATUS_ICON[status];
  return (
    <Badge variant={status === "GRANTED" ? "default" : status === "DENIED" ? "destructive" : "outline"} title={title}>
      <Icon aria-hidden />
      {t(status)}
    </Badge>
  );
}

/** The class roster's consent column: who is allowed, refused or undecided, without guardian names. */
export function ClassConsentTab({ cls }: { cls: ClassView }) {
  const t = useTranslations("school.families.consent");
  const tReason = useTranslations("school.families.consent.reason");
  const { school } = useSchool();
  const rows = useQuery({
    queryKey: sk.classConsent(school.id, cls.id),
    queryFn: () => fetchClassConsent(school.id, cls.id),
  });

  return (
    <Card className="shadow-none">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-lg">
          <ShieldCheckIcon className="size-5" aria-hidden />
          {t("title")}
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <p className="text-sm text-muted-foreground">{t("intro")}</p>
        {rows.isPending ? (
          <LoadingRows rows={4} />
        ) : rows.isError ? (
          <ErrorNotice message={errorMessage(rows.error, t("loadFailed"))} onRetry={() => void rows.refetch()} />
        ) : rows.data.length === 0 ? (
          <EmptyNotice>{t("empty")}</EmptyNotice>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("child")}</TableHead>
                <TableHead>{t("account")}</TableHead>
                <TableHead>{t("media")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.data.map((row) => (
                <TableRow key={row.studentId} data-testid="consent-row">
                  <TableCell className="font-medium">{row.displayName}</TableCell>
                  <TableCell>
                    <ConsentBadge status={row.accountParticipation.status} title={tReason(row.accountParticipation.reason)} />
                  </TableCell>
                  <TableCell>
                    <ConsentBadge status={row.mediaPublication.status} title={tReason(row.mediaPublication.reason)} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  );
}

const DECISIONS = ["GRANTED", "DENIED", "WITHDRAWN"] as const;
const METHODS = ["PAPER_RECORDED_BY_SCHOOL", "VERBAL_RECORDED_BY_SCHOOL"] as const;
const CONSENT_TYPES = ["ACCOUNT_PARTICIPATION", "MEDIA_PUBLICATION"] as const;

/** A child's effective consent and every decision so far (administrators), with "record paper consent". */
export function StudentConsentCard({ student }: { student: StudentView }) {
  const t = useTranslations("school.families.consent");
  const tType = useTranslations("consent.type");
  const tState = useTranslations("school.families.consent.reason");
  const tDecision = useTranslations("school.families.consent.decision");
  const tMethod = useTranslations("school.families.consent.method");
  const { school, permissions, isTeacher, isStaff } = useSchool();
  const abilities = familyAbilities({ permissions, isTeacher, isStaff, isStudent: false });
  const formatInstant = useFormatInstant();
  const [recording, setRecording] = useState(false);
  const consents = useQuery({
    queryKey: sk.studentConsent(school.id, student.id),
    queryFn: () => fetchStudentConsents(school.id, student.id),
    enabled: abilities.canManageConsent,
  });
  if (!abilities.canManageConsent) return null;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-lg">
          <ShieldCheckIcon className="size-5" aria-hidden />
          {t("detailTitle", { name: student.displayName })}
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {consents.isPending ? (
          <LoadingRows rows={2} />
        ) : consents.isError ? (
          <ErrorNotice message={errorMessage(consents.error, t("loadFailed"))} onRetry={() => void consents.refetch()} />
        ) : (
          <>
            <dl className="grid gap-3 sm:grid-cols-2">
              {CONSENT_TYPES.map((type) => {
                const status = type === "MEDIA_PUBLICATION" ? consents.data.mediaPublication : consents.data.accountParticipation;
                return (
                  <div key={type} className="flex flex-col gap-1 rounded-xl border p-3">
                    <dt className="text-sm text-muted-foreground">{tType(type)}</dt>
                    <dd className="flex flex-col gap-1">
                      <ConsentBadge status={status.status} />
                      <span className="text-sm text-muted-foreground">{tState(status.reason)}</span>
                    </dd>
                  </div>
                );
              })}
            </dl>

            <h3 className="text-sm font-semibold">{t("records")}</h3>
            {consents.data.records.length === 0 ? (
              <EmptyNotice>{t("noRecords")}</EmptyNotice>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t("colType")}</TableHead>
                    <TableHead>{t("colWho")}</TableHead>
                    <TableHead>{t("colDecision")}</TableHead>
                    <TableHead>{t("colHow")}</TableHead>
                    <TableHead>{t("colWhen")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {consents.data.records.map((r) => (
                    <TableRow key={r.id}>
                      <TableCell>{tType(r.consentType)}</TableCell>
                      <TableCell>{r.decidedByName ?? "—"}</TableCell>
                      <TableCell>{tDecision(r.decision)}</TableCell>
                      <TableCell>{tMethod(r.method)}</TableCell>
                      <TableCell>{formatInstant(r.createdAt)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
            <div>
              <Button variant="outline" onClick={() => setRecording(true)} disabled={student.status === "WITHDRAWN"}>
                {t("record")}
              </Button>
            </div>
          </>
        )}
      </CardContent>
      {recording ? <RecordConsentDialog student={student} onClose={() => setRecording(false)} /> : null}
    </Card>
  );
}

function RecordConsentDialog({ student, onClose }: { student: StudentView; onClose: () => void }) {
  const t = useTranslations("school.families.consent");
  const tType = useTranslations("consent.type");
  const tDecision = useTranslations("school.families.consent.decision");
  const tMethod = useTranslations("school.families.consent.method");
  const tv = useTranslations("school.validation");
  const locale = useLocale();
  const { school } = useSchool();
  const [type, setType] = useState<(typeof CONSENT_TYPES)[number]>("MEDIA_PUBLICATION");
  const [decision, setDecision] = useState<(typeof DECISIONS)[number]>("GRANTED");
  const [method, setMethod] = useState<(typeof METHODS)[number]>("PAPER_RECORDED_BY_SCHOOL");
  const [who, setWho] = useState<"link" | "name">("name");
  const [linkId, setLinkId] = useState("");
  const [name, setName] = useState("");
  const [missing, setMissing] = useState(false);

  const policy = useQuery({
    queryKey: sk.effectivePolicy(school.id, type, locale),
    queryFn: async () => {
      try {
        return await fetchEffectivePolicy(school.id, type, locale);
      } catch (error) {
        if (error instanceof ApiRequestError && error.status === 404) return null;
        throw error;
      }
    },
  });
  const guardians = useQuery({
    queryKey: sk.studentGuardians(school.id, student.id),
    queryFn: () => fetchStudentGuardians(school.id, student.id),
  });
  const eligible = consentGuardians(guardians.data ?? []);
  const byLink = who === "link" && eligible.length > 0;

  const record = useSubmit({
    mutationFn: async (_: void, idempotencyKey) => {
      if (!policy.data) throw new Error("no policy");
      return unwrap(
        await getBrowserApi().POST("/api/v1/schools/{schoolId}/students/{studentId}/consents", {
          params: { path: { schoolId: school.id, studentId: student.id } },
          headers: { "Idempotency-Key": idempotencyKey },
          body: {
            consentType: type,
            policyVersionId: policy.data.versionId,
            decision,
            method,
            ...(byLink ? { guardianLinkId: linkId } : { guardianName: name.trim() }),
          },
        }),
      );
    },
    invalidate: [sk.consent(school.id)],
    successMessage: t("saved"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: onClose,
  });

  const noVersion = !policy.isPending && !policy.data;

  return (
    <FormDialog
      title={t("recordTitle")}
      description={t("recordBody")}
      submitLabel={t("submit")}
      pending={record.isPending}
      error={record.isError ? errorMessage(record.error, t("failed")) : noVersion ? t("noVersion") : null}
      onSubmit={() => {
        const incomplete = who === "link" ? !byLink || !linkId : !name.trim();
        setMissing(incomplete);
        if (!incomplete && policy.data) record.mutate();
      }}
      onClose={onClose}
    >
      <SelectField
        label={t("type")}
        value={type}
        onChange={(v) => setType(v as typeof type)}
        options={CONSENT_TYPES.map((value) => ({ value, label: tType(value) }))}
      />
      {policy.data ? <p className="text-sm text-muted-foreground">{t("usesVersion", { version: policy.data.versionLabel })}</p> : null}
      <SelectField
        label={t("answer")}
        value={decision}
        onChange={(v) => setDecision(v as typeof decision)}
        options={DECISIONS.map((value) => ({ value, label: tDecision(value) }))}
      />
      <SelectField
        label={t("who")}
        value={who}
        onChange={(v) => setWho(v as typeof who)}
        options={[
          { value: "link", label: t("whoLink") },
          { value: "name", label: t("whoName") },
        ]}
      />
      {who === "link" ? (
        eligible.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("noEligible")}</p>
        ) : (
          <SelectField
            label={t("guardian")}
            value={linkId}
            onChange={setLinkId}
            error={missing && !linkId ? tv("required") : null}
            options={eligible.map((l) => ({ value: l.id, label: l.guardianName }))}
          />
        )
      ) : (
        <FormField label={t("guardianName")} error={missing && !name.trim() ? tv("required") : null}>
          {(props) => <Input {...props} maxLength={200} autoComplete="off" value={name} onChange={(e) => setName(e.target.value)} />}
        </FormField>
      )}
      <SelectField
        label={t("howLabel")}
        value={method}
        onChange={(v) => setMethod(v as typeof method)}
        options={METHODS.map((value) => ({ value, label: tMethod(value) }))}
      />
    </FormDialog>
  );
}
