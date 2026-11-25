"use client";

import { useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { ErrorNotice, LoadingRows } from "@/components/console/states";
import { SourceBadge } from "@/components/console/source-badge";
import { ValueEditor } from "@/components/console/settings-view";
import { useSubmit } from "@/components/console/use-submit";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ensureOk, unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { editorKindFor, parseEditorText, summarizeValue, toEditorText } from "@/lib/console/definitions";
import { errorMessage, fieldErrors } from "@/lib/console/errors";
import { SP } from "@/lib/school/access";
import { ENGAGEMENT_MODULE, engagementSettingId } from "@/lib/points/settings";
import { groupSettings, type SettingGroup } from "@/lib/school/settings";
import { definitionsKey, fetchConfiguration, fetchDefinitions, sk } from "@/lib/school/queries";
import { PageHeader } from "./page-header";
import { RequirePermission } from "./require-permission";
import { useSchool } from "./school-context";

/** The school's own settings: every setting a school may change, its effective value and where that comes from. */
export function SchoolSettingsView() {
  const t = useTranslations("school.settings");
  const { school } = useSchool();
  const definitions = useQuery({ queryKey: definitionsKey, queryFn: fetchDefinitions, staleTime: 600_000 });
  const configuration = useQuery({
    queryKey: sk.configuration(school.id),
    queryFn: () => fetchConfiguration(school.id),
  });

  let body: React.ReactNode;
  if (definitions.isPending || configuration.isPending) {
    body = <LoadingRows rows={5} />;
  } else if (definitions.isError || configuration.isError) {
    body = (
      <ErrorNotice
        message={errorMessage(definitions.error ?? configuration.error, t("loadFailed"))}
        onRetry={() => {
          void definitions.refetch();
          void configuration.refetch();
        }}
      />
    );
  } else {
    const groups = groupSettings(definitions.data.settings, configuration.data.settings);
    body = (
      <div className="flex flex-col gap-6">
        {groups.map((group) => (
          <ModuleCard key={group.module} group={group} />
        ))}
      </div>
    );
  }

  return (
    <RequirePermission anyOf={[SP.settings]}>
      <div className="mx-auto flex max-w-4xl flex-col gap-6">
        <PageHeader title={t("title")} description={t("subtitle")} />
        {body}
      </div>
    </RequirePermission>
  );
}

function ModuleCard({ group }: { group: SettingGroup }) {
  const tp = useTranslations("school.points.settings");
  return (
    <Card>
      <CardHeader>
        <CardTitle className={group.module === ENGAGEMENT_MODULE ? "text-lg" : "text-lg capitalize"}>
          {group.module === ENGAGEMENT_MODULE ? tp("module") : group.module}
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {group.settings.map((setting) => (
          // Remount when the stored value changes so a saved draft shows the new effective value.
          <SettingRow key={`${setting.key}:${JSON.stringify(setting.value)}:${setting.source}`} setting={setting} />
        ))}
      </CardContent>
    </Card>
  );
}

function SettingRow({ setting }: { setting: SettingGroup["settings"][number] }) {
  const t = useTranslations("school.settings");
  const tp = useTranslations("school.points.settings");
  const engagementId = engagementSettingId(setting.key);
  const { school } = useSchool();
  const kind = editorKindFor(setting.valueSchema);
  const stored = toEditorText(kind, setting.value);
  const [draft, setDraft] = useState<string | null>(null);
  const [parseError, setParseError] = useState(false);
  const text = draft ?? stored;
  const dirty = draft !== null && draft !== stored;
  const own = setting.source === "SCHOOL";

  const save = useSubmit({
    mutationFn: async (nextText: string) => {
      const parsed = parseEditorText(kind, nextText);
      if (!parsed.ok) throw new Error("unparseable");
      return unwrap(
        await getBrowserApi().PUT("/api/v1/platform/settings", {
          body: { key: setting.key, scope: { type: "SCHOOL", schoolId: school.id }, value: parsed.value },
        }),
      );
    },
    invalidate: [sk.configuration(school.id)],
    successMessage: t("saved", { key: setting.key }),
    errorFallback: t("saveFailed"),
    toastOnError: false,
    onSuccess: () => setDraft(null),
  });
  const reset = useSubmit({
    mutationFn: async () =>
      ensureOk(
        await getBrowserApi().DELETE("/api/v1/platform/settings", {
          params: { query: { key: setting.key, scopeType: "SCHOOL", schoolId: school.id } },
        }),
      ),
    invalidate: [sk.configuration(school.id)],
    successMessage: t("resetDone", { key: setting.key }),
    errorFallback: t("saveFailed"),
    toastOnError: false,
    onSuccess: () => setDraft(null),
  });

  const inputId = `school-setting-${setting.key}`;
  const serverError = save.isError
    ? (fieldErrors(save.error).value ?? errorMessage(save.error, t("saveFailed")))
    : reset.isError
      ? errorMessage(reset.error, t("saveFailed"))
      : null;

  return (
    <div className="flex flex-col gap-3 rounded-2xl border p-4" data-testid={`setting-${setting.key}`}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          {engagementId ? <p className="font-semibold">{tp(`${engagementId}.title`)}</p> : null}
          <label htmlFor={inputId} className={engagementId ? "font-mono text-xs text-muted-foreground break-all" : "font-mono text-sm font-semibold break-all"}>
            {setting.key}
          </label>
          {engagementId ? (
            <p className="text-sm text-muted-foreground">{tp(`${engagementId}.description`)}</p>
          ) : setting.description ? (
            <p className="text-sm text-muted-foreground">{setting.description}</p>
          ) : null}
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs text-muted-foreground">{t("effectiveFrom")}</span>
          <SourceBadge source={setting.source} />
        </div>
      </div>
      <div className="flex flex-wrap items-start gap-3">
        <div className="min-w-64 flex-1">
          <ValueEditor
            id={inputId}
            kind={kind}
            text={text}
            onText={(next) => {
              setDraft(next);
              setParseError(false);
            }}
          />
        </div>
        <div className="flex gap-2">
          <Button
            disabled={save.isPending || !dirty}
            onClick={() => {
              const parsed = parseEditorText(kind, text);
              setParseError(!parsed.ok);
              if (parsed.ok) save.mutate(text);
            }}
          >
            {save.isPending ? t("saving") : t("save")}
          </Button>
          {own ? (
            <Button variant="outline" onClick={() => reset.mutate()} disabled={reset.isPending}>
              {t("reset")}
            </Button>
          ) : null}
        </div>
      </div>
      <p className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        <Badge variant="outline">{t("default", { value: summarizeValue(setting.defaultValue, 60) })}</Badge>
        {own ? t("ownNote") : t("inheritedNote")}
      </p>
      {parseError ? (
        <p role="alert" className="text-sm font-medium text-destructive">
          {t("invalidValue")}
        </p>
      ) : null}
      {serverError ? (
        <p role="alert" className="text-sm font-medium text-destructive">
          {serverError}
        </p>
      ) : null}
    </div>
  );
}
