"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import { useFormatInstant } from "@/components/calendar-preference";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { P } from "@/lib/console/permissions";
import { queryKeys, type School, type SchoolStatus } from "@/lib/console/queries";
import { useCan } from "./console-context";
import { SchoolBranding } from "./school-branding";
import { StatusBadge } from "./status-badge";
import { useSubmit } from "./use-submit";

const ALL_STATUSES: SchoolStatus[] = ["ONBOARDING", "ACTIVE", "SUSPENDED", "ARCHIVED"];

/** The API's rule: ARCHIVED is final and a school never returns to ONBOARDING. */
export function allowedStatusChanges(current: SchoolStatus): SchoolStatus[] {
  if (current === "ARCHIVED") return [];
  return ALL_STATUSES.filter((s) => s !== current && s !== "ONBOARDING");
}

export function SchoolOverview({ school }: { school: School }) {
  const t = useTranslations("console");
  const formatInstant = useFormatInstant();
  const canManage = useCan(P.schoolsManage);
  const [confirm, setConfirm] = useState<SchoolStatus | null>(null);

  const change = useSubmit({
    mutationFn: async (status: SchoolStatus) =>
      unwrap(
        await getBrowserApi().PUT("/api/v1/platform/schools/{schoolId}/status", {
          params: { path: { schoolId: school.id } },
          body: { status },
        }),
      ),
    invalidate: [queryKeys.school(school.id), queryKeys.schoolsAll],
    successMessage: t("schools.statusChanged"),
    errorFallback: t("saveFailed"),
    onSuccess: () => setConfirm(null),
  });

  const details: Array<[string, React.ReactNode]> = [
    [t("fields.name"), school.name],
    [t("fields.slug"), <span key="slug" className="font-mono">{school.slug}</span>],
    [t("fields.country"), school.countryCode],
    [t("fields.language"), school.languages.map((l) => l.code + (l.isDefault ? "*" : "")).join(", ")],
    [t("fields.timezone"), school.timezone],
    [t("fields.calendar"), school.defaultCalendarCode],
    [t("fields.currency"), school.currencyCode],
    [t("fields.created"), formatInstant(school.createdAt)],
  ];
  const next = allowedStatusChanges(school.status);

  return (
    <div className="grid gap-6 lg:grid-cols-[2fr_1fr]">
      <Card>
        <CardHeader>
          <CardTitle>{t("schoolTabs.overview")}</CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-2">
            {details.map(([label, value]) => (
              <div key={label} className="flex flex-col">
                <dt className="text-sm text-muted-foreground">{label}</dt>
                <dd className="font-semibold">{value}</dd>
              </div>
            ))}
          </dl>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>{t("fields.status")}</CardTitle>
          <CardDescription className="flex items-center gap-2">
            <StatusBadge status={school.status} />
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          {canManage && next.length ? (
            next.map((status) => (
              <Button
                key={status}
                variant={status === "ARCHIVED" || status === "SUSPENDED" ? "outline" : "default"}
                onClick={() => (status === "ARCHIVED" ? setConfirm(status) : change.mutate(status))}
                disabled={change.isPending}
              >
                {t(`schools.moveTo.${status}`)}
              </Button>
            ))
          ) : (
            <p className="text-sm text-muted-foreground">
              {school.status === "ARCHIVED" ? t("schools.archivedFinal") : t("schools.noStatusChange")}
            </p>
          )}
        </CardContent>
      </Card>

      <SchoolBranding school={school} />

      <Dialog open={confirm !== null} onOpenChange={(open) => !open && setConfirm(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("schools.archiveTitle")}</DialogTitle>
            <DialogDescription>{t("schools.archiveBody", { name: school.name })}</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirm(null)}>
              {t("cancel")}
            </Button>
            <Button
              variant="destructive"
              disabled={change.isPending}
              onClick={() => confirm && change.mutate(confirm)}
            >
              {t("schools.moveTo.ARCHIVED")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
