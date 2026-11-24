"use client";

import { useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ensureOk, unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage } from "@/lib/console/errors";
import {
  fetchDefinitions,
  fetchFeatureOverrides,
  queryKeys,
  type ScopeRef,
} from "@/lib/console/queries";
import { ErrorNotice, LoadingRows } from "./states";
import { ScopePicker } from "./scope-picker";
import { useSubmit } from "./use-submit";

type Choice = "INHERIT" | "ON" | "OFF";

export function FeatureFlagsView() {
  const t = useTranslations("console");
  const [scope, setScope] = useState<ScopeRef | null>({ type: "PLATFORM" });

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-bold">{t("featureFlags.title")}</h1>
        <p className="text-muted-foreground">{t("featureFlags.subtitle")}</p>
      </div>
      <ScopePicker allowed={["PLATFORM", "COUNTRY", "ORGANIZATION", "SCHOOL"]} onChange={setScope} />
      {scope ? <FeatureTable scope={scope} /> : <p className="text-muted-foreground">{t("scope.pickPrompt")}</p>}
    </div>
  );
}

function FeatureTable({ scope }: { scope: ScopeRef }) {
  const t = useTranslations("console");
  const definitions = useQuery({ queryKey: queryKeys.definitions, queryFn: fetchDefinitions, staleTime: 600_000 });
  const overrides = useQuery({
    queryKey: queryKeys.featureOverrides(scope),
    queryFn: () => fetchFeatureOverrides(scope),
  });

  if (definitions.isPending || overrides.isPending) return <LoadingRows />;
  if (definitions.isError || overrides.isError) {
    const error = definitions.error ?? overrides.error;
    return (
      <ErrorNotice
        message={errorMessage(error, t("loadFailed"))}
        onRetry={() => {
          void definitions.refetch();
          void overrides.refetch();
        }}
      />
    );
  }
  const byKey = new Map(overrides.data.map((o) => [o.featureKey, o]));

  return (
    <div className="rounded-2xl border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{t("featureFlags.feature")}</TableHead>
            <TableHead>{t("featureFlags.default")}</TableHead>
            <TableHead className="w-48">{t("featureFlags.override")}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {definitions.data.features.map((feature) => (
            <FeatureRow
              key={feature.key}
              scope={scope}
              featureKey={feature.key}
              description={feature.description}
              defaultEnabled={feature.defaultEnabled}
              current={byKey.get(feature.key)?.enabled}
            />
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

function FeatureRow({
  scope,
  featureKey,
  description,
  defaultEnabled,
  current,
}: {
  scope: ScopeRef;
  featureKey: string;
  description?: string;
  defaultEnabled: boolean;
  current: boolean | undefined;
}) {
  const t = useTranslations("console");
  const choice: Choice = current === undefined ? "INHERIT" : current ? "ON" : "OFF";

  const change = useSubmit({
    mutationFn: async (next: Choice) => {
      const api = getBrowserApi();
      if (next === "INHERIT") {
        return ensureOk(
          await api.DELETE("/api/v1/platform/feature-overrides", {
            params: {
              query: {
                featureKey,
                scopeType: scope.type,
                countryCode: scope.countryCode,
                organizationId: scope.organizationId,
                schoolId: scope.schoolId,
              },
            },
          }),
        );
      }
      return unwrap(
        await api.PUT("/api/v1/platform/feature-overrides", {
          body: { featureKey, scope, enabled: next === "ON" },
        }),
      );
    },
    invalidate: [queryKeys.featureOverrides(scope)],
    errorFallback: t("saveFailed"),
    onSuccess: () => toast.success(t("featureFlags.saved", { key: featureKey })),
  });

  return (
    <TableRow>
      <TableCell>
        <div className="font-mono text-sm font-semibold">{featureKey}</div>
        {description ? <div className="text-sm text-muted-foreground">{description}</div> : null}
      </TableCell>
      <TableCell>
        <Badge variant="outline">{defaultEnabled ? t("featureFlags.on") : t("featureFlags.off")}</Badge>
      </TableCell>
      <TableCell>
        <Select value={choice} disabled={change.isPending} onValueChange={(v) => change.mutate(v as Choice)}>
          <SelectTrigger className="w-full" aria-label={t("featureFlags.overrideFor", { key: featureKey })}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="INHERIT">{t("featureFlags.inherit")}</SelectItem>
            <SelectItem value="ON">{t("featureFlags.on")}</SelectItem>
            <SelectItem value="OFF">{t("featureFlags.off")}</SelectItem>
          </SelectContent>
        </Select>
      </TableCell>
    </TableRow>
  );
}
