"use client";

import { useQuery } from "@tanstack/react-query";
import { CheckIcon, MinusIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { summarizeValue } from "@/lib/console/definitions";
import { errorMessage } from "@/lib/console/errors";
import { fetchSchoolConfiguration, queryKeys, type School } from "@/lib/console/queries";
import { ErrorNotice, LoadingRows } from "./states";
import { SourceBadge } from "./source-badge";

/** A school's effective features and settings, read-only, with the level each value comes from. */
export function SchoolConfigurationView({ school }: { school: School }) {
  const t = useTranslations("console");
  const query = useQuery({
    queryKey: queryKeys.configuration(school.id),
    queryFn: () => fetchSchoolConfiguration(school.id),
  });

  if (query.isPending) return <LoadingRows />;
  if (query.isError) {
    return <ErrorNotice message={errorMessage(query.error, t("loadFailed"))} onRetry={() => void query.refetch()} />;
  }
  const { features, settings } = query.data;

  return (
    <div className="flex flex-col gap-8">
      <section className="flex flex-col gap-3">
        <h2 className="text-lg font-semibold">{t("configuration.features")}</h2>
        <p className="text-sm text-muted-foreground">{t("configuration.featuresHint")}</p>
        <div className="rounded-2xl border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("configuration.key")}</TableHead>
                <TableHead>{t("configuration.enabled")}</TableHead>
                <TableHead>{t("configuration.flag")}</TableHead>
                <TableHead>{t("configuration.entitled")}</TableHead>
                <TableHead>{t("configuration.limit")}</TableHead>
                <TableHead>{t("configuration.source")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {features.map((f) => (
                <TableRow key={f.key}>
                  <TableCell className="font-mono text-sm">{f.key}</TableCell>
                  <TableCell>
                    <Tick on={f.enabled} label={f.enabled ? t("yes") : t("no")} />
                  </TableCell>
                  <TableCell>
                    <Tick on={f.flagEnabled} label={f.flagEnabled ? t("yes") : t("no")} />
                  </TableCell>
                  <TableCell>
                    <Tick on={f.entitled} label={f.entitled ? t("yes") : t("no")} />
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
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-lg font-semibold">{t("configuration.settings")}</h2>
        <div className="rounded-2xl border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("configuration.key")}</TableHead>
                <TableHead>{t("configuration.value")}</TableHead>
                <TableHead>{t("configuration.source")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {settings.map((s) => (
                <TableRow key={s.key}>
                  <TableCell>
                    <div className="font-mono text-sm">{s.key}</div>
                    <Badge variant="outline" className="mt-1">
                      {s.module}
                    </Badge>
                  </TableCell>
                  <TableCell className="font-mono text-sm">{summarizeValue(s.value, 80)}</TableCell>
                  <TableCell>
                    <SourceBadge source={s.source} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </section>
    </div>
  );
}

function Tick({ on, label }: { on: boolean; label: string }) {
  const Icon = on ? CheckIcon : MinusIcon;
  return (
    <span className={on ? "text-success" : "text-muted-foreground"}>
      <Icon className="size-4" aria-hidden />
      <span className="sr-only">{label}</span>
    </span>
  );
}
