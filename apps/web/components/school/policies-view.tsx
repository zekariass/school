"use client";

import { useQuery } from "@tanstack/react-query";
import { FilePlus2Icon } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { useState } from "react";
import { SUPPORTED_LOCALES } from "@school/i18n";
import { useFormatInstant } from "@/components/calendar-preference";
import { FormField } from "@/components/console/form-field";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { ApiRequestError, unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage } from "@/lib/console/errors";
import { newIdempotencyKey } from "@/lib/console/idempotency";
import { SP } from "@/lib/school/access";
import {
  SCHOOL_POLICY_TYPES,
  documentFor,
  validateVersion,
  type SchoolPolicyType,
  type VersionDraft,
} from "@/lib/school/families";
import {
  fetchEffectivePolicy,
  fetchSchoolPolicies,
  sk,
  type PolicyView,
  type SchoolPolicy,
} from "@/lib/school/queries";
import { ConfirmDialog } from "./confirm-dialog";
import { CheckboxField, SelectField } from "./fields";
import { FormDialog } from "./form-dialog";
import { PageHeader } from "./page-header";
import { RequirePermission } from "./require-permission";
import { useSchool } from "./school-context";

/** The policy texts guardians agree to: the one in effect, the school's own versions and publishing a new one. */
export function PoliciesView() {
  const t = useTranslations("school.families.policies");
  const { school } = useSchool();
  const policies = useQuery({ queryKey: sk.policies(school.id), queryFn: () => fetchSchoolPolicies(school.id) });
  const [publishing, setPublishing] = useState<SchoolPolicyType | null>(null);

  return (
    <RequirePermission anyOf={[SP.policies]}>
      <div className="mx-auto flex max-w-4xl flex-col gap-6">
        <PageHeader title={t("title")} description={t("subtitle")} />
        {policies.isPending ? (
          <LoadingRows rows={4} />
        ) : policies.isError ? (
          <ErrorNotice message={errorMessage(policies.error, t("loadFailed"))} onRetry={() => void policies.refetch()} />
        ) : (
          SCHOOL_POLICY_TYPES.map((type) => (
            <PolicyCard
              key={type}
              type={type}
              own={documentFor(policies.data, type)}
              onPublish={() => setPublishing(type)}
            />
          ))
        )}
      </div>
      {publishing && policies.data ? (
        <PublishDialog
          type={publishing}
          own={documentFor(policies.data, publishing)}
          onClose={() => setPublishing(null)}
        />
      ) : null}
    </RequirePermission>
  );
}

function PolicyCard({
  type,
  own,
  onPublish,
}: {
  type: SchoolPolicyType;
  own: SchoolPolicy | undefined;
  onPublish: () => void;
}) {
  const t = useTranslations("school.families.policies");
  const tType = useTranslations("consent.type");
  const locale = useLocale();
  const { school } = useSchool();
  const [publishingDraft, setPublishingDraft] = useState<SchoolPolicy["versions"][number] | null>(null);
  const formatInstant = useFormatInstant();
  const effective = useQuery({
    queryKey: sk.effectivePolicy(school.id, type, locale),
    queryFn: async (): Promise<PolicyView | null> => {
      try {
        return await fetchEffectivePolicy(school.id, type, locale);
      } catch (error) {
        if (error instanceof ApiRequestError && error.status === 404) return null;
        throw error;
      }
    },
  });

  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-3">
        <CardTitle className="text-lg">{tType(type)}</CardTitle>
        <Button onClick={onPublish}>
          <FilePlus2Icon className="size-4" aria-hidden />
          {t("publish")}
        </Button>
      </CardHeader>
      <CardContent className="flex flex-col gap-5">
        <section className="flex flex-col gap-2" aria-label={t("current")}>
          <h3 className="text-sm font-semibold">{t("current")}</h3>
          {effective.isPending ? (
            <LoadingRows rows={2} />
          ) : effective.isError ? (
            <ErrorNotice message={errorMessage(effective.error, t("loadFailed"))} onRetry={() => void effective.refetch()} />
          ) : effective.data ? (
            <>
              <div className="flex flex-wrap items-center gap-2 text-sm">
                <Badge variant={effective.data.schoolId ? "default" : "secondary"}>
                  {t(`source.${effective.data.schoolId ? "school" : "default"}`)}
                </Badge>
                <span className="text-muted-foreground">{t("language", { language: effective.data.languageCode })}</span>
                <span className="text-muted-foreground">{effective.data.versionLabel}</span>
              </div>
              <pre
                tabIndex={0}
                className="max-h-64 overflow-auto rounded-xl bg-muted/60 p-3 text-sm whitespace-pre-wrap"
                data-testid={`policy-text-${type}`}
              >
                {effective.data.content}
              </pre>
            </>
          ) : (
            <EmptyNotice>{t("noVersions")}</EmptyNotice>
          )}
        </section>

        <section className="flex flex-col gap-2" aria-label={t("versionsTitle")}>
          <h3 className="text-sm font-semibold">{t("versionsTitle")}</h3>
          {!own || own.versions.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("noVersions")}</p>
          ) : (
            <ul className="flex flex-col gap-2">
              {own.versions.map((v) => (
                <li key={v.id} className="flex flex-wrap items-center gap-2 rounded-xl border px-3 py-2 text-sm">
                  <span className="font-semibold">{v.versionLabel}</span>
                  <Badge variant="secondary">{v.languageCode}</Badge>
                  {v.publishedAt ? (
                    <span className="text-muted-foreground">{t("published", { date: formatInstant(v.publishedAt) })}</span>
                  ) : (
                    <Badge variant="outline">{t("draft")}</Badge>
                  )}
                  {v.requiresReconsent ? <Badge variant="outline">{t("requiresReconsent")}</Badge> : null}
                  {!v.publishedAt ? (
                    <Button size="sm" variant="outline" className="ms-auto" onClick={() => setPublishingDraft(v)}>
                      {t("publishDraft")}
                    </Button>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
        </section>
      </CardContent>
      {publishingDraft && own ? (
        <PublishDraftDialog documentId={own.document.id} version={publishingDraft} onClose={() => setPublishingDraft(null)} />
      ) : null}
    </Card>
  );
}

function PublishDraftDialog({
  documentId,
  version,
  onClose,
}: {
  documentId: string;
  version: SchoolPolicy["versions"][number];
  onClose: () => void;
}) {
  const t = useTranslations("school.families.policies");
  const { school } = useSchool();
  const publish = useSubmit({
    mutationFn: async () =>
      unwrap(
        await getBrowserApi().POST("/api/v1/schools/{schoolId}/policies/{documentId}/versions/{versionId}/publish", {
          params: { path: { schoolId: school.id, documentId, versionId: version.id } },
        }),
      ),
    invalidate: [sk.policies(school.id)],
    successMessage: t("done"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: onClose,
  });
  return (
    <ConfirmDialog
      title={t("confirmTitle", { version: version.versionLabel })}
      description={version.requiresReconsent ? t("confirmBodyReconsent") : t("confirmBody")}
      confirmLabel={t("form.submit")}
      destructive={false}
      pending={publish.isPending}
      error={publish.isError ? errorMessage(publish.error, t("failed")) : null}
      onConfirm={() => publish.mutate()}
      onClose={onClose}
    />
  );
}

/** Writes a version in one language and publishes it; starts the school's own document when it has none. */
function PublishDialog({
  type,
  own,
  onClose,
}: {
  type: SchoolPolicyType;
  own: SchoolPolicy | undefined;
  onClose: () => void;
}) {
  const t = useTranslations("school.families.policies");
  const tf = useTranslations("school.families.policies.form");
  const tType = useTranslations("consent.type");
  const tv = useTranslations("school.validation");
  const locale = useLocale();
  const { school } = useSchool();
  const [draft, setDraft] = useState<VersionDraft>({ label: "", language: locale, content: "" });
  const [reconsent, setReconsent] = useState(false);
  const [errors, setErrors] = useState<ReturnType<typeof validateVersion>>({});

  const publish = useSubmit({
    mutationFn: async () => {
      const api = getBrowserApi();
      let documentId = own?.document.id;
      if (!documentId) {
        try {
          documentId = unwrap(
            await api.POST("/api/v1/schools/{schoolId}/policies", {
              params: { path: { schoolId: school.id } },
              headers: { "Idempotency-Key": newIdempotencyKey() },
              body: { policyType: type, title: tType(type) },
            }),
          ).id;
        } catch (error) {
          // Another administrator started it meanwhile: use that document.
          if (!(error instanceof ApiRequestError) || error.problem?.code !== "policy.already_exists") throw error;
          const fresh = await fetchSchoolPolicies(school.id);
          documentId = documentFor(fresh, type)?.document.id;
          if (!documentId) throw error;
        }
      }
      const version = unwrap(
        await api.POST("/api/v1/schools/{schoolId}/policies/{documentId}/versions", {
          params: { path: { schoolId: school.id, documentId } },
          headers: { "Idempotency-Key": newIdempotencyKey() },
          body: {
            versionLabel: draft.label.trim(),
            languageCode: draft.language,
            content: draft.content,
            requiresReconsent: reconsent,
          },
        }),
      );
      return unwrap(
        await api.POST("/api/v1/schools/{schoolId}/policies/{documentId}/versions/{versionId}/publish", {
          params: { path: { schoolId: school.id, documentId, versionId: version.id } },
        }),
      );
    },
    invalidate: [sk.policies(school.id), sk.consent(school.id)],
    successMessage: t("done"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: onClose,
  });

  return (
    <FormDialog
      title={tf("title", { type: tType(type) })}
      description={reconsent ? t("confirmBodyReconsent") : t("confirmBody")}
      submitLabel={tf("submit")}
      pending={publish.isPending}
      error={publish.isError ? errorMessage(publish.error, t("failed")) : null}
      onSubmit={() => {
        const found = validateVersion(draft);
        setErrors(found);
        if (Object.keys(found).length === 0) publish.mutate();
      }}
      onClose={onClose}
      className="sm:max-w-2xl"
    >
      <FormField label={tf("label")} hint={tf("labelHint")} error={errors.label ? tv(errors.label.replace("validation.", "") as "required") : null}>
        {(props) => <Input {...props} maxLength={40} value={draft.label} onChange={(e) => setDraft({ ...draft, label: e.target.value })} />}
      </FormField>
      <SelectField
        label={tf("language")}
        value={draft.language}
        onChange={(v) => setDraft({ ...draft, language: v })}
        options={SUPPORTED_LOCALES.map((l) => ({ value: l.code, label: l.nativeName }))}
      />
      <FormField label={tf("content")} error={errors.content ? tv(errors.content.replace("validation.", "") as "required") : null}>
        {(props) => (
          <Textarea
            {...props}
            rows={10}
            maxLength={20000}
            value={draft.content}
            onChange={(e) => setDraft({ ...draft, content: e.target.value })}
          />
        )}
      </FormField>
      <CheckboxField label={tf("reconsent")} hint={tf("reconsentHint")} checked={reconsent} onChange={setReconsent} />
    </FormDialog>
  );
}
