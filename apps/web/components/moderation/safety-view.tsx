"use client";

import { useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useDisplayPlace } from "@/components/display-context";
import { ErrorNotice, LoadingRows } from "@/components/console/states";
import { errorMessage } from "@/lib/console/errors";
import { modk } from "@/lib/moderation/keys";
import { outcomeOf } from "@/lib/moderation/outcome";
import { fetchEmergencyInfo } from "@/lib/moderation/queries";
import { EmergencyText } from "./emergency-text";

/**
 * "If a child is in danger": the safety text the API has for the country of this page (the person's, or their school's
 * inside a workspace). A country the API has no text for (404) shows the plain "nothing here yet" line: the text of another
 * country is never shown instead, and nothing is written in this app.
 */
export function SafetyView() {
  const t = useTranslations("moderation.safety");
  const country = useDisplayPlace()?.countryCode;
  const query = useQuery({
    queryKey: modk.emergency(country ?? ""),
    queryFn: () => fetchEmergencyInfo(country!),
    enabled: !!country,
    staleTime: 10 * 60_000,
    retry: false,
  });
  const outcome = query.isError ? outcomeOf(query.error) : null;
  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-4" data-testid="safety-view">
      <h1 className="text-2xl font-bold">{t("title")}</h1>
      <p className="text-muted-foreground">{t("intro")}</p>
      {query.isPending && country ? <LoadingRows rows={2} /> : null}
      {query.data?.text ? <EmergencyText info={query.data} /> : null}
      {outcome?.kind === "nothing" || (query.isSuccess && !query.data.text) ? <p className="rounded-xl border border-dashed p-6 text-center text-muted-foreground">{t("none")}</p> : null}
      {outcome && outcome.kind !== "nothing" ? <ErrorNotice message={errorMessage(query.error, t("loadFailed"))} onRetry={() => void query.refetch()} /> : null}
    </div>
  );
}
