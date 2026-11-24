"use client";

import { useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { ensureOk, unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import {
  editorKindFor,
  parseEditorText,
  summarizeValue,
  toEditorText,
  type EditorKind,
} from "@/lib/console/definitions";
import { errorMessage, fieldErrors } from "@/lib/console/errors";
import { P } from "@/lib/console/permissions";
import {
  fetchDefinitions,
  fetchSettingOverrides,
  queryKeys,
  type Definitions,
  type ScopeRef,
} from "@/lib/console/queries";
import { useCan } from "./console-context";
import { ErrorNotice, LoadingRows } from "./states";
import { ScopePicker, type ScopeType } from "./scope-picker";
import { useSubmit } from "./use-submit";

type Definition = Definitions["settings"][number];

export function SettingsView() {
  const t = useTranslations("console");
  const canPlatform = useCan(P.settingsManage);
  // Without platform.settings.manage the user may only change school-level settings.
  const allowed: ScopeType[] = canPlatform ? ["PLATFORM", "COUNTRY", "ORGANIZATION", "SCHOOL"] : ["SCHOOL"];
  const [scope, setScope] = useState<ScopeRef | null>(canPlatform ? { type: "PLATFORM" } : null);

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-bold">{t("settings.title")}</h1>
        <p className="text-muted-foreground">{t("settings.subtitle")}</p>
      </div>
      <ScopePicker allowed={allowed} onChange={setScope} />
      {scope ? <SettingsTable scope={scope} /> : <p className="text-muted-foreground">{t("scope.pickPrompt")}</p>}
    </div>
  );
}

function SettingsTable({ scope }: { scope: ScopeRef }) {
  const t = useTranslations("console");
  const definitions = useQuery({ queryKey: queryKeys.definitions, queryFn: fetchDefinitions, staleTime: 600_000 });
  const overrides = useQuery({
    queryKey: queryKeys.settingOverrides(scope),
    queryFn: () => fetchSettingOverrides(scope),
  });

  if (definitions.isPending || overrides.isPending) return <LoadingRows />;
  if (definitions.isError || overrides.isError) {
    return (
      <ErrorNotice
        message={errorMessage(definitions.error ?? overrides.error, t("loadFailed"))}
        onRetry={() => {
          void definitions.refetch();
          void overrides.refetch();
        }}
      />
    );
  }
  const byKey = new Map(overrides.data.map((o) => [o.key, o]));
  const applicable = definitions.data.settings.filter((d) => d.allowedScopes.includes(scope.type));

  return (
    <div className="flex flex-col gap-3">
      {applicable.map((definition) => (
        <SettingRow
          // Remount when the scope changes so drafts never leak between scopes.
          key={`${definition.key}:${JSON.stringify(scope)}`}
          scope={scope}
          definition={definition}
          overridden={byKey.has(definition.key)}
          value={byKey.has(definition.key) ? byKey.get(definition.key)?.value : definition.defaultValue}
        />
      ))}
    </div>
  );
}

function SettingRow({
  scope,
  definition,
  overridden,
  value,
}: {
  scope: ScopeRef;
  definition: Definition;
  overridden: boolean;
  value: unknown;
}) {
  const t = useTranslations("console");
  const kind = editorKindFor(definition.valueSchema);
  const stored = toEditorText(kind, value);
  const [draft, setDraft] = useState<string | null>(null);
  const [parseError, setParseError] = useState(false);
  const text = draft ?? stored;
  const dirty = draft !== null && draft !== stored;

  const save = useSubmit({
    mutationFn: async (nextText: string) => {
      const parsed = parseEditorText(kind, nextText);
      if (!parsed.ok) throw new Error("unparseable");
      return unwrap(
        await getBrowserApi().PUT("/api/v1/platform/settings", {
          body: { key: definition.key, scope, value: parsed.value },
        }),
      );
    },
    invalidate: [queryKeys.settingOverrides(scope)],
    successMessage: t("settings.saved", { key: definition.key }),
    errorFallback: t("saveFailed"),
    toastOnError: false,
    onSuccess: () => setDraft(null),
  });
  const clear = useSubmit({
    mutationFn: async () =>
      ensureOk(
        await getBrowserApi().DELETE("/api/v1/platform/settings", {
          params: {
            query: {
              key: definition.key,
              scopeType: scope.type,
              countryCode: scope.countryCode,
              organizationId: scope.organizationId,
              schoolId: scope.schoolId,
            },
          },
        }),
      ),
    invalidate: [queryKeys.settingOverrides(scope)],
    successMessage: t("settings.cleared", { key: definition.key }),
    errorFallback: t("saveFailed"),
    onSuccess: () => setDraft(null),
  });

  function submit() {
    const parsed = parseEditorText(kind, text);
    setParseError(!parsed.ok);
    if (parsed.ok) save.mutate(text);
  }

  const serverError = save.isError
    ? (fieldErrors(save.error).value ?? errorMessage(save.error, t("saveFailed")))
    : null;
  const label = definition.key;
  const inputId = `setting-${definition.key}`;

  return (
    <Card className="shadow-none">
      <CardContent className="flex flex-col gap-3">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div>
            <label htmlFor={inputId} className="font-mono text-sm font-semibold">
              {label}
            </label>
            {definition.description ? <p className="text-sm text-muted-foreground">{definition.description}</p> : null}
          </div>
          <div className="flex items-center gap-2">
            <Badge variant="outline">{definition.module}</Badge>
            <Badge variant={overridden ? "secondary" : "outline"}>
              {overridden ? t("settings.overridden") : t("settings.inherited")}
            </Badge>
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
            <Button onClick={submit} disabled={save.isPending || !(dirty || !overridden)}>
              {save.isPending ? t("saving") : overridden ? t("save") : t("settings.override")}
            </Button>
            {overridden ? (
              <Button variant="outline" onClick={() => clear.mutate()} disabled={clear.isPending}>
                {t("settings.clear")}
              </Button>
            ) : null}
          </div>
        </div>

        <p className="text-xs text-muted-foreground">
          {t("settings.default", { value: summarizeValue(definition.defaultValue, 80) })}
        </p>
        {parseError ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {t("settings.invalidValue")}
          </p>
        ) : null}
        {serverError ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {serverError}
          </p>
        ) : null}
      </CardContent>
    </Card>
  );
}

/** An input matching the definition's type. All values travel as text and are parsed on save. */
function ValueEditor({
  id,
  kind,
  text,
  onText,
}: {
  id: string;
  kind: EditorKind;
  text: string;
  onText: (text: string) => void;
}) {
  const t = useTranslations("console");
  switch (kind.kind) {
    case "boolean":
      return (
        <div className="flex items-center gap-3">
          <Switch id={id} checked={text === "true"} onCheckedChange={(on) => onText(on ? "true" : "false")} />
          <span className="text-sm font-medium">{text === "true" ? t("featureFlags.on") : t("featureFlags.off")}</span>
        </div>
      );
    case "enum":
      return (
        <Select value={text} onValueChange={onText}>
          <SelectTrigger id={id} className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {kind.options.map((option) => (
              <SelectItem key={option} value={option}>
                {option}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      );
    case "number":
      return (
        <Input
          id={id}
          type="number"
          inputMode="decimal"
          min={kind.min}
          max={kind.max}
          step={kind.integer ? 1 : "any"}
          value={text}
          onChange={(e) => onText(e.target.value)}
        />
      );
    case "string":
      return <Input id={id} value={text} maxLength={kind.maxLength} onChange={(e) => onText(e.target.value)} />;
    case "json":
      return (
        <Textarea
          id={id}
          rows={Math.min(10, Math.max(3, text.split("\n").length))}
          className="font-mono text-sm"
          spellCheck={false}
          value={text}
          onChange={(e) => onText(e.target.value)}
        />
      );
  }
}
