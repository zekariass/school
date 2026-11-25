"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2Icon, CircleHelpIcon, InfoIcon, ShieldCheckIcon, XCircleIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import {
  availableActions,
  decisionState,
  isChildGoneProblem,
  isVersionOutdatedProblem,
  mediaOutcome,
  orderedChildItems,
  type ConsentAction,
} from "@school/validation/family";
import { useFormatInstant } from "@/components/calendar-preference";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { ConfirmDialog } from "@/components/school/confirm-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ApiRequestError, unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage } from "@/lib/console/errors";
import { fetchMyConsents, familyKeys, type ChildConsents, type ConsentItem } from "@/lib/family/queries";

interface Target {
  /** Absent for the adult's own agreements (terms, privacy). */
  child?: { studentId: string; schoolId: string; name: string };
  item: ConsentItem;
  action: ConsentAction;
}

/** Consent requests of my children and my own agreements: read the text, then allow, refuse or withdraw. */
export function ConsentView() {
  const t = useTranslations("consent");
  const consents = useQuery({ queryKey: familyKeys.consents, queryFn: fetchMyConsents });
  const [outdated, setOutdated] = useState(false);

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-6">
      <header>
        <h1 className="text-3xl font-bold">{t("title")}</h1>
        <p className="text-muted-foreground">{t("subtitle")}</p>
      </header>

      {outdated ? (
        <p role="alert" className="rounded-xl bg-warning/15 p-3 text-sm font-medium">
          {t("versionOutdated")}
        </p>
      ) : null}

      {consents.isPending ? (
        <LoadingRows rows={3} />
      ) : consents.isError ? (
        <ErrorNotice message={errorMessage(consents.error, t("loadFailed"))} onRetry={() => void consents.refetch()} />
      ) : (
        <>
          {consents.data.children.length === 0 && consents.data.account.length === 0 ? (
            <EmptyNotice>{t("nothingToDecide")}</EmptyNotice>
          ) : null}
          {consents.data.children.map((child) => (
            <ChildSection key={`${child.studentId}:${child.schoolId}`} child={child} onOutdated={() => setOutdated(true)} onClear={() => setOutdated(false)} />
          ))}
          {consents.data.account.length > 0 ? (
            <Card>
              <CardHeader>
                <CardTitle className="text-xl">{t("accountTitle")}</CardTitle>
                <p className="text-sm text-muted-foreground">{t("accountIntro")}</p>
              </CardHeader>
              <CardContent className="flex flex-col gap-4">
                {consents.data.account.map((item) => (
                  <ItemCard key={item.consentType} item={item} onOutdated={() => setOutdated(true)} onClear={() => setOutdated(false)} />
                ))}
              </CardContent>
            </Card>
          ) : null}
        </>
      )}
    </div>
  );
}

function ChildSection({
  child,
  onOutdated,
  onClear,
}: {
  child: ChildConsents;
  onOutdated: () => void;
  onClear: () => void;
}) {
  const t = useTranslations("consent");
  return (
    <Card data-testid="consent-child">
      <CardHeader>
        <CardTitle className="text-xl">{child.childName}</CardTitle>
        <p className="text-sm text-muted-foreground">{child.schoolName}</p>
        <p className="flex items-start gap-2 text-sm font-medium">
          <InfoIcon className="mt-0.5 size-4 shrink-0 text-primary" aria-hidden />
          {t("perChild", { child: child.childName })}
        </p>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {orderedChildItems(child.items).map((item) => (
          <ItemCard
            key={item.consentType}
            item={item}
            child={{ studentId: child.studentId, schoolId: child.schoolId, name: child.childName }}
            onOutdated={onOutdated}
            onClear={onClear}
          />
        ))}
      </CardContent>
    </Card>
  );
}

const STATE_ICON = { GRANTED: CheckCircle2Icon, DENIED: XCircleIcon, WITHDRAWN: XCircleIcon, NONE: CircleHelpIcon } as const;

function ItemCard({
  item,
  child,
  onOutdated,
  onClear,
}: {
  item: ConsentItem;
  child?: { studentId: string; schoolId: string; name: string };
  onOutdated: () => void;
  onClear: () => void;
}) {
  const t = useTranslations("consent");
  const formatInstant = useFormatInstant();
  const [reading, setReading] = useState(false);
  const [target, setTarget] = useState<Target | null>(null);
  const state = decisionState(item);
  const actions = availableActions(item);
  const Icon = STATE_ICON[state];
  const isMedia = item.consentType === "MEDIA_PUBLICATION" && !!child;

  return (
    <section className="flex flex-col gap-3 rounded-2xl border p-4" aria-label={t(`type.${item.consentType}`)} data-testid={`consent-${item.consentType}`}>
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="flex items-center gap-2 text-lg font-bold">
          <ShieldCheckIcon className="size-5 shrink-0 text-primary" aria-hidden />
          {t(`type.${item.consentType}`)}
        </h3>
        <Badge variant={state === "GRANTED" ? "default" : state === "NONE" ? "outline" : "destructive"}>
          <Icon aria-hidden />
          {t(`state.${state}`)}
        </Badge>
        {item.needsDecision && item.currentVersion ? <Badge variant="secondary">{t("needsDecision")}</Badge> : null}
      </div>
      <p className="text-sm text-muted-foreground">
        {child ? t(`typeHelp.${item.consentType}`, { child: child.name }) : t(`typeHelp.${item.consentType}`, { child: "" })}
      </p>
      {item.outdated ? <p className="rounded-xl bg-warning/15 p-2 text-sm font-medium">{t("outdated")}</p> : null}
      {item.myDecision ? (
        <p className="text-sm text-muted-foreground">{t("decidedOn", { date: formatInstant(item.myDecision.decidedAt) })}</p>
      ) : null}

      {isMedia && child ? (
        <div className="flex flex-col gap-1 rounded-xl bg-muted/60 p-3 text-sm">
          <p className="font-medium">
            {mediaOutcome(item.effectiveStatus?.status) === "allowed"
              ? t("mediaAllowed", { child: child.name })
              : t("mediaBlocked", { child: child.name })}
          </p>
          <p className="text-muted-foreground">{t("strictest", { child: child.name })}</p>
        </div>
      ) : null}

      {item.currentVersion ? (
        <div className="flex flex-col gap-2">
          <Button variant="ghost" size="sm" className="self-start" aria-expanded={reading} onClick={() => setReading(!reading)}>
            {reading ? t("hideText") : t("readText")}
          </Button>
          {reading ? (
            <div className="flex flex-col gap-1">
              <p className="text-xs text-muted-foreground">
                {t("version", { version: item.currentVersion.versionLabel })} · {item.currentVersion.languageCode}
              </p>
              <pre tabIndex={0} className="max-h-72 overflow-auto rounded-xl bg-muted/60 p-3 text-sm whitespace-pre-wrap">
                {item.currentVersion.content}
              </pre>
            </div>
          ) : null}
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">{t("noPolicy")}</p>
      )}

      {actions.length > 0 ? (
        <div className="flex flex-wrap gap-2">
          {actions.map((action) => (
            <Button
              key={action}
              variant={action === "GRANTED" ? "default" : "outline"}
              onClick={() => setTarget({ child, item, action })}
            >
              {action === "GRANTED" ? t("grant") : action === "DENIED" ? t("deny") : t("withdraw")}
            </Button>
          ))}
        </div>
      ) : null}

      {target ? <DecisionDialog target={target} onClose={() => setTarget(null)} onOutdated={onOutdated} onClear={onClear} /> : null}
    </section>
  );
}

function DecisionDialog({
  target,
  onClose,
  onOutdated,
  onClear,
}: {
  target: Target;
  onClose: () => void;
  onOutdated: () => void;
  onClear: () => void;
}) {
  const t = useTranslations("consent");
  const queryClient = useQueryClient();
  const { child, item, action } = target;
  const name = child?.name ?? "";
  const version = item.currentVersion;

  const decide = useSubmit({
    mutationFn: async (_: void, idempotencyKey) => {
      if (!version) throw new Error("no policy");
      return unwrap(
        await getBrowserApi().POST("/api/v1/me/consents", {
          headers: { "Idempotency-Key": idempotencyKey },
          body: {
            consentType: item.consentType,
            policyVersionId: version.versionId,
            decision: action,
            ...(child ? { studentId: child.studentId, schoolId: child.schoolId } : {}),
          },
        }),
      );
    },
    invalidate: [familyKeys.all],
    successMessage: t("saved"),
    errorFallback: t("saveFailed"),
    toastOnError: false,
    onSuccess: () => {
      onClear();
      onClose();
    },
  });

  const error = decide.error;
  const problem = error instanceof ApiRequestError ? error.problem : undefined;
  const outdated = error instanceof ApiRequestError && isVersionOutdatedProblem(problem, error.status);
  const gone = isChildGoneProblem(problem);
  let message: string | null = null;
  if (decide.isError) {
    message = outdated ? t("versionOutdated") : gone ? t("childNotFound") : errorMessage(error, t("saveFailed"));
  }

  // The text changed while the guardian was reading: load the new version, close, and say so.
  async function reloadAndClose() {
    await queryClient.invalidateQueries({ queryKey: familyKeys.consents });
    onOutdated();
    onClose();
  }

  const body = item.consentType === "MEDIA_PUBLICATION" && child ? t("confirmBodyMedia", { child: name }) : t("confirmBody", { child: name });

  return (
    <ConfirmDialog
      title={t(`confirmTitle.${action}`, { child: name || "—" })}
      description={`${t(`type.${item.consentType}`)}. ${child ? body : ""}`.trim()}
      confirmLabel={outdated ? t("reloadText") : t(`confirm.${action}`)}
      destructive={action !== "GRANTED"}
      pending={decide.isPending}
      error={message}
      onConfirm={() => (outdated ? void reloadAndClose() : decide.mutate())}
      onClose={onClose}
    />
  );
}
