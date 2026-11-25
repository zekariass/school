"use client";

import { makeGroups } from "@school/classroom-toolkit";
import { useQuery } from "@tanstack/react-query";
import { SaveIcon, ShuffleIcon, UsersRoundIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { useSchool } from "@/components/school/school-context";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage } from "@/lib/console/errors";
import { fetchRoster, sk, type ClassView } from "@/lib/school/queries";

type By = "count" | "size";

/** Random groups by count or size (sizes differ by at most one). "Save" stores the whole set as the class's saved groups. */
export function GroupsTool({ cls }: { cls: ClassView }) {
  const t = useTranslations("school.toolkit.groups");
  const { school } = useSchool();
  const roster = useQuery({ queryKey: sk.classRoster(school.id, cls.id), queryFn: () => fetchRoster(school.id, cls.id) });
  const [by, setBy] = useState<By>("count");
  const [amountText, setAmountText] = useState("3");
  const [groups, setGroups] = useState<string[][]>([]);
  const [invalid, setInvalid] = useState(false);

  const save = useSubmit({
    mutationFn: async (_: void, idempotencyKey) =>
      unwrap(
        await getBrowserApi().POST("/api/v1/schools/{schoolId}/classes/{classId}/group-sets", {
          params: { path: { schoolId: school.id, classId: cls.id } },
          headers: { "Idempotency-Key": idempotencyKey },
          body: { groups: groups.map((ids, i) => ({ name: t("groupName", { n: i + 1 }), studentIds: ids })) },
        }),
      ),
    invalidate: [sk.classGroups(school.id, cls.id)],
    successMessage: t("saved"),
    errorFallback: t("saveFailed"),
    toastOnError: false,
  });

  if (roster.isPending) return <LoadingRows rows={3} />;
  if (roster.isError) return <ErrorNotice message={errorMessage(roster.error, t("loadFailed"))} onRetry={() => void roster.refetch()} />;
  const students = roster.data;
  if (students.length === 0) return <EmptyNotice>{t("empty")}</EmptyNotice>;
  const names = new Map(students.map((s) => [s.studentId, s.displayName]));

  function make() {
    const amount = Number(amountText);
    if (!Number.isInteger(amount) || amount < 1) {
      setInvalid(true);
      return;
    }
    setInvalid(false);
    save.reset();
    setGroups(makeGroups(students.map((s) => s.studentId), by === "count" ? { groupCount: amount } : { groupSize: amount }));
  }

  return (
    <Card>
      <CardContent className="flex flex-col gap-4">
        <div className="flex flex-wrap items-end gap-3">
          <fieldset className="flex flex-col gap-1.5">
            <legend className="pb-1 text-sm font-medium">{t("by")}</legend>
            <div className="flex gap-2" role="group">
              <Button variant={by === "count" ? "default" : "outline"} aria-pressed={by === "count"} onClick={() => setBy("count")} data-testid="by-count">
                {t("byCount")}
              </Button>
              <Button variant={by === "size" ? "default" : "outline"} aria-pressed={by === "size"} onClick={() => setBy("size")} data-testid="by-size">
                {t("bySize")}
              </Button>
            </div>
          </fieldset>
          <label className="flex flex-col gap-1.5 text-sm font-medium">
            {by === "count" ? t("countLabel") : t("sizeLabel")}
            <Input
              type="number"
              inputMode="numeric"
              min={1}
              max={students.length}
              value={amountText}
              aria-invalid={invalid || undefined}
              onChange={(e) => setAmountText(e.target.value)}
              className="w-28"
              data-testid="groups-amount"
            />
          </label>
          <Button onClick={make} data-testid="make-groups">
            <ShuffleIcon className="size-4" aria-hidden />
            {groups.length > 0 ? t("again") : t("make")}
          </Button>
        </div>
        {invalid ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {t("invalid")}
          </p>
        ) : null}

        {groups.length > 0 ? (
          <>
            <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3" aria-label={t("result")} data-testid="groups-result">
              {groups.map((ids, index) => (
                <li key={index} className="flex flex-col gap-2 rounded-2xl border bg-card p-4" data-testid="group-card">
                  <h3 className="flex items-center gap-2 font-bold">
                    <UsersRoundIcon className="size-5 text-primary" aria-hidden />
                    {t("groupName", { n: index + 1 })}
                    <span className="text-sm font-normal text-muted-foreground">{t("members", { count: ids.length })}</span>
                  </h3>
                  <ul className="text-sm">
                    {ids.map((id) => (
                      <li key={id}>{names.get(id) ?? "…"}</li>
                    ))}
                  </ul>
                </li>
              ))}
            </ul>
            <div className="flex flex-wrap items-center gap-3">
              <Button variant="outline" onClick={() => save.mutate()} disabled={save.isPending || save.isSuccess} data-testid="save-groups">
                <SaveIcon className="size-4" aria-hidden />
                {save.isSuccess ? t("savedButton") : t("save")}
              </Button>
              <p className="text-sm text-muted-foreground">{t("saveHint")}</p>
            </div>
            {save.isError ? (
              <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
                {errorMessage(save.error, t("saveFailed"))}
              </p>
            ) : null}
          </>
        ) : null}
      </CardContent>
    </Card>
  );
}
