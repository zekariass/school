"use client";

import { useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage, fieldErrors } from "@/lib/console/errors";
import { P } from "@/lib/console/permissions";
import {
  fetchPlans,
  fetchSchoolConfiguration,
  queryKeys,
  type School,
} from "@/lib/console/queries";
import { useCan } from "./console-context";
import { FormField } from "./form-field";
import { ErrorNotice, LoadingRows } from "./states";
import { useSubmit } from "./use-submit";

type AssignStatus = "ACTIVE" | "TRIALING";

export function SchoolSubscription({ school }: { school: School }) {
  const t = useTranslations("console");
  const canAssign = useCan(P.subscriptionsManage);
  const configuration = useQuery({
    queryKey: queryKeys.configuration(school.id),
    queryFn: () => fetchSchoolConfiguration(school.id),
  });
  const plans = useQuery({ queryKey: queryKeys.plans, queryFn: fetchPlans, enabled: canAssign, staleTime: 600_000 });

  const [plan, setPlan] = useState<string | null>(null);
  const [status, setStatus] = useState<AssignStatus>("ACTIVE");
  const [periodEnd, setPeriodEnd] = useState("");

  const current = configuration.data?.planCode;
  const chosen = plan ?? current ?? "";

  const assign = useSubmit({
    mutationFn: async () =>
      unwrap(
        await getBrowserApi().PUT("/api/v1/platform/schools/{schoolId}/subscription", {
          params: { path: { schoolId: school.id } },
          body: {
            planCode: chosen,
            status,
            // The last moment of the chosen day, UTC; the API rejects a past end.
            ...(periodEnd ? { currentPeriodEnd: `${periodEnd}T23:59:59Z` } : {}),
          },
        }),
      ),
    invalidate: [queryKeys.configuration(school.id)],
    successMessage: t("subscription.assigned"),
    errorFallback: t("saveFailed"),
    toastOnError: false,
    onSuccess: () => {
      setPlan(null);
      setPeriodEnd("");
    },
  });
  const serverErrors = fieldErrors(assign.error);

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <Card>
        <CardHeader>
          <CardTitle>{t("subscription.current")}</CardTitle>
          <CardDescription>{t("subscription.currentHint")}</CardDescription>
        </CardHeader>
        <CardContent>
          {configuration.isPending ? (
            <LoadingRows rows={1} />
          ) : configuration.isError ? (
            <ErrorNotice
              message={errorMessage(configuration.error, t("loadFailed"))}
              onRetry={() => void configuration.refetch()}
            />
          ) : (
            <div className="flex flex-wrap items-center gap-3">
              <span className="text-xl font-bold">
                {plans.data?.find((p) => p.code === current)?.name ?? current}
              </span>
              {configuration.data.planIsFreeFallback ? (
                <Badge variant="outline">{t("subscription.freeFallback")}</Badge>
              ) : null}
            </div>
          )}
        </CardContent>
      </Card>

      {canAssign ? (
        <Card>
          <CardHeader>
            <CardTitle>{t("subscription.assign")}</CardTitle>
            <CardDescription>{t("subscription.assignHint")}</CardDescription>
          </CardHeader>
          <CardContent>
            <form
              noValidate
              className="flex flex-col gap-4"
              onSubmit={(event) => {
                event.preventDefault();
                if (chosen) assign.mutate();
              }}
            >
              <FormField label={t("subscription.plan")} error={serverErrors.planCode}>
                {(props) => (
                  <Select value={chosen} onValueChange={setPlan}>
                    <SelectTrigger {...props} className="w-full">
                      <SelectValue placeholder={plans.isPending ? t("loading") : undefined} />
                    </SelectTrigger>
                    <SelectContent>
                      {(plans.data ?? []).map((p) => (
                        <SelectItem key={p.code} value={p.code}>
                          {p.name}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                )}
              </FormField>
              <FormField label={t("fields.status")}>
                {(props) => (
                  <Select value={status} onValueChange={(v) => setStatus(v as AssignStatus)}>
                    <SelectTrigger {...props} className="w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="ACTIVE">{t("subscription.statusActive")}</SelectItem>
                      <SelectItem value="TRIALING">{t("subscription.statusTrialing")}</SelectItem>
                    </SelectContent>
                  </Select>
                )}
              </FormField>
              <FormField
                label={t("subscription.periodEnd")}
                hint={t("subscription.periodEndHint")}
                error={serverErrors.currentPeriodEnd}
              >
                {(props) => (
                  <Input {...props} type="date" value={periodEnd} onChange={(e) => setPeriodEnd(e.target.value)} />
                )}
              </FormField>
              {assign.isError && Object.keys(serverErrors).length === 0 ? (
                <p role="alert" className="text-sm font-medium text-destructive">
                  {errorMessage(assign.error, t("saveFailed"))}
                </p>
              ) : null}
              <Button type="submit" disabled={assign.isPending || !chosen}>
                {assign.isPending ? t("saving") : t("subscription.assign")}
              </Button>
            </form>
          </CardContent>
        </Card>
      ) : null}
    </div>
  );
}
