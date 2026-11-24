"use client";

import { useQuery } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useFormatInstant } from "@/components/calendar-preference";
import { getBrowserApi } from "@/lib/api-client";
import { ApiRequestError, unwrap } from "@/lib/api-error";

async function fetchMeta() {
  const started = Date.now();
  const meta = unwrap(await getBrowserApi().GET("/api/v1/meta"));
  const finished = Date.now();
  // Assume the server stamped its time halfway through the round trip.
  const skewMs = new Date(meta.serverTime).getTime() - (started + finished) / 2;
  return { meta, skewMs, roundTripMs: finished - started };
}

export function StatusView() {
  const t = useTranslations("status");
  const locale = useLocale();
  const formatInstant = useFormatInstant();
  const query = useQuery({ queryKey: ["meta"], queryFn: fetchMeta, staleTime: 0 });

  const seconds = (ms: number) =>
    new Intl.NumberFormat(locale, {
      signDisplay: "always",
      maximumFractionDigits: 2,
    }).format(ms / 1000);

  return (
    <Card className="mx-auto w-full max-w-2xl">
      <CardHeader>
        <CardTitle role="heading" aria-level={1} className="text-2xl">
          {t("title")}
        </CardTitle>
        <CardDescription>{t("description")}</CardDescription>
      </CardHeader>
      <CardContent aria-live="polite">
        {query.isPending ? (
          <DetailsSkeleton />
        ) : query.isError ? (
          <StatusError error={query.error} onRetry={() => query.refetch()} />
        ) : (
          <dl className="grid gap-4 sm:grid-cols-2">
            <Detail label={t("service")} value={query.data.meta.service} />
            <Detail label={t("apiVersion")} value={query.data.meta.apiVersion} />
            <Detail label={t("buildVersion")} value={query.data.meta.buildVersion} />
            <Detail
              label={t("serverTime")}
              value={
                <time dateTime={query.data.meta.serverTime}>
                  {formatInstant(query.data.meta.serverTime)}
                </time>
              }
            />
            <Detail
              label={t("clockSkew")}
              value={t("clockSkewValue", { seconds: seconds(query.data.skewMs) })}
            />
          </dl>
        )}
      </CardContent>
    </Card>
  );
}

function Detail({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="rounded-xl bg-muted/60 p-4">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="mt-1 text-lg font-semibold break-words">{value}</dd>
    </div>
  );
}

function DetailsSkeleton() {
  return (
    <div className="grid gap-4 sm:grid-cols-2" aria-hidden>
      {Array.from({ length: 5 }, (_, i) => (
        <Skeleton key={i} className="h-20 rounded-xl" />
      ))}
    </div>
  );
}

function StatusError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const t = useTranslations();
  const problem = error instanceof ApiRequestError ? error.problem : undefined;
  return (
    <div role="alert" className="flex flex-col gap-3 rounded-xl bg-destructive/10 p-4 text-destructive">
      <p className="text-lg font-semibold">{problem?.title ?? t("status.errorTitle")}</p>
      <p className="text-sm">{problem?.detail ?? t("status.errorBody")}</p>
      {problem?.code ? (
        <p className="font-mono text-sm">{t("status.errorCode", { code: problem.code })}</p>
      ) : null}
      <Button variant="outline" className="self-start" onClick={onRetry}>
        {t("common.retry")}
      </Button>
    </div>
  );
}
