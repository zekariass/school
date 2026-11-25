"use client";

import { useQuery } from "@tanstack/react-query";
import { CheckIcon, MinusIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { ErrorNotice, LoadingRows } from "@/components/console/states";
import { SourceBadge } from "@/components/console/source-badge";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { errorMessage } from "@/lib/console/errors";
import { SP } from "@/lib/school/access";
import { fetchConfiguration, sk } from "@/lib/school/queries";
import { PageHeader } from "./page-header";
import { RequirePermission } from "./require-permission";
import { useSchool } from "./school-context";

/** Read-only: which features this school has, from the feature flag and the plan. Overrides are platform-only. */
export function SchoolFeaturesView() {
  const t = useTranslations("school.features");
  const { school } = useSchool();
  const configuration = useQuery({
    queryKey: sk.configuration(school.id),
    queryFn: () => fetchConfiguration(school.id),
  });

  return (
    <RequirePermission anyOf={[SP.read]}>
      <div className="mx-auto flex max-w-4xl flex-col gap-6">
        <PageHeader title={t("title")} description={t("subtitle")} />
        {configuration.isPending ? (
          <LoadingRows rows={5} />
        ) : configuration.isError ? (
          <ErrorNotice
            message={errorMessage(configuration.error, t("loadFailed"))}
            onRetry={() => void configuration.refetch()}
          />
        ) : (
          <>
            <p className="flex flex-wrap items-center gap-2 text-sm">
              <span className="text-muted-foreground">{t("plan")}</span>
              <Badge>{configuration.data.planCode}</Badge>
              {configuration.data.planIsFreeFallback ? <Badge variant="outline">{t("freeFallback")}</Badge> : null}
            </p>
            <div className="rounded-2xl border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t("feature")}</TableHead>
                    <TableHead>{t("available")}</TableHead>
                    <TableHead>{t("flag")}</TableHead>
                    <TableHead>{t("plan")}</TableHead>
                    <TableHead>{t("limit")}</TableHead>
                    <TableHead>{t("source")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {configuration.data.features.map((f) => (
                    <TableRow key={f.key}>
                      <TableCell className="font-mono text-sm">{f.key}</TableCell>
                      <TableCell>
                        <Tick on={f.enabled} yes={t("yes")} no={t("no")} />
                      </TableCell>
                      <TableCell>
                        <Tick on={f.flagEnabled} yes={t("yes")} no={t("no")} />
                      </TableCell>
                      <TableCell>
                        <Tick on={f.entitled} yes={t("yes")} no={t("no")} />
                      </TableCell>
                      <TableCell>{f.limit ?? "—"}</TableCell>
                      <TableCell>
                        <SourceBadge source={f.flagSource} />
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
            <p className="text-sm text-muted-foreground">{t("note")}</p>
          </>
        )}
      </div>
    </RequirePermission>
  );
}

function Tick({ on, yes, no }: { on: boolean; yes: string; no: string }) {
  const Icon = on ? CheckIcon : MinusIcon;
  return (
    <span className={on ? "text-success" : "text-muted-foreground"}>
      <Icon className="size-4" aria-hidden />
      <span className="sr-only">{on ? yes : no}</span>
    </span>
  );
}
