"use client";

import { useQuery } from "@tanstack/react-query";
import { ChevronDownIcon, ChevronRightIcon, PencilIcon, PlusIcon, StarIcon, Trash2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { useFormatDate } from "@/components/calendar-preference";
import { FormField } from "@/components/console/form-field";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { ensureOk, unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage, fieldErrors } from "@/lib/console/errors";
import { dateOrder, required, type FieldError } from "@/lib/school/forms";
import {
  fetchPeriods,
  fetchYears,
  sk,
  type AcademicPeriod,
  type AcademicYear,
} from "@/lib/school/queries";
import { ConfirmDialog } from "./confirm-dialog";
import { CheckboxField, SelectField } from "./fields";
import { FormDialog } from "./form-dialog";
import { useSchool } from "./school-context";

const YEAR_STATUSES = ["PLANNED", "ACTIVE", "CLOSED"] as const;
const PERIOD_TYPES = ["TERM", "SEMESTER", "QUARTER", "CUSTOM"] as const;

export function YearsTab() {
  const t = useTranslations("school.academic.years");
  const { school } = useSchool();
  const format = useFormatDate();
  const [editing, setEditing] = useState<AcademicYear | "new" | null>(null);
  const [deleting, setDeleting] = useState<AcademicYear | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const years = useQuery({ queryKey: sk.years(school.id), queryFn: () => fetchYears(school.id) });

  const makeCurrent = useSubmit({
    mutationFn: async (yearId: string) =>
      unwrap(
        await getBrowserApi().POST("/api/v1/schools/{schoolId}/academic-years/{yearId}/make-current", {
          params: { path: { schoolId: school.id, yearId } },
        }),
      ),
    invalidate: [sk.years(school.id), sk.summary(school.id), sk.classes(school.id)],
    successMessage: t("madeCurrent"),
    errorFallback: t("saveFailed"),
  });
  const remove = useSubmit({
    mutationFn: async (yearId: string) =>
      ensureOk(
        await getBrowserApi().DELETE("/api/v1/schools/{schoolId}/academic-years/{yearId}", {
          params: { path: { schoolId: school.id, yearId } },
        }),
      ),
    invalidate: [sk.years(school.id), sk.summary(school.id)],
    successMessage: t("deleted"),
    errorFallback: t("saveFailed"),
    toastOnError: false,
    onSuccess: () => setDeleting(null),
  });

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-muted-foreground">{t("intro")}</p>
        <Button onClick={() => setEditing("new")}>
          <PlusIcon className="size-4" aria-hidden />
          {t("add")}
        </Button>
      </div>

      {years.isPending ? (
        <LoadingRows rows={2} />
      ) : years.isError ? (
        <ErrorNotice message={errorMessage(years.error, t("loadFailed"))} onRetry={() => void years.refetch()} />
      ) : years.data.length === 0 ? (
        <EmptyNotice>{t("empty")}</EmptyNotice>
      ) : (
        <ul className="flex flex-col gap-3">
          {years.data.map((year) => {
            const expanded = open === year.id;
            return (
              <li key={year.id}>
                <Card className="shadow-none">
                  <CardContent className="flex flex-col gap-3">
                    <div className="flex flex-wrap items-center gap-3">
                      <button
                        type="button"
                        aria-expanded={expanded}
                        aria-controls={`periods-${year.id}`}
                        onClick={() => setOpen(expanded ? null : year.id)}
                        className="flex min-h-11 min-w-0 flex-1 items-center gap-2 rounded-xl text-start outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
                      >
                        {expanded ? (
                          <ChevronDownIcon className="size-5 shrink-0 rtl:rotate-90" aria-hidden />
                        ) : (
                          <ChevronRightIcon className="size-5 shrink-0 rtl:rotate-180" aria-hidden />
                        )}
                        <span className="min-w-0">
                          <span className="block truncate text-lg font-bold">{year.name}</span>
                          <span className="block text-sm text-muted-foreground">
                            {format(year.startsOn)} – {format(year.endsOn)}
                          </span>
                        </span>
                      </button>
                      <div className="flex flex-wrap items-center gap-2">
                        {year.current ? <Badge>{t("current")}</Badge> : null}
                        <Badge variant="outline">{t(`status.${year.status}`)}</Badge>
                        {!year.current && year.status !== "CLOSED" ? (
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={makeCurrent.isPending}
                            onClick={() => makeCurrent.mutate(year.id)}
                          >
                            <StarIcon className="size-4" aria-hidden />
                            {t("makeCurrent")}
                          </Button>
                        ) : null}
                        <Button
                          size="icon-sm"
                          variant="ghost"
                          aria-label={t("edit", { name: year.name })}
                          onClick={() => setEditing(year)}
                        >
                          <PencilIcon className="size-4" aria-hidden />
                        </Button>
                        <Button
                          size="icon-sm"
                          variant="ghost"
                          aria-label={t("delete", { name: year.name })}
                          onClick={() => setDeleting(year)}
                        >
                          <Trash2Icon className="size-4" aria-hidden />
                        </Button>
                      </div>
                    </div>
                    {expanded ? (
                      <div id={`periods-${year.id}`}>
                        <PeriodsOfYear year={year} />
                      </div>
                    ) : null}
                  </CardContent>
                </Card>
              </li>
            );
          })}
        </ul>
      )}

      {editing !== null ? (
        <YearDialog year={editing === "new" ? null : editing} onClose={() => setEditing(null)} />
      ) : null}
      {deleting ? (
        <ConfirmDialog
          title={t("deleteTitle", { name: deleting.name })}
          description={t("deleteBody")}
          confirmLabel={t("deleteConfirm")}
          pending={remove.isPending}
          error={remove.isError ? errorMessage(remove.error, t("saveFailed")) : null}
          onConfirm={() => remove.mutate(deleting.id)}
          onClose={() => setDeleting(null)}
        />
      ) : null}
    </div>
  );
}

function YearDialog({ year, onClose }: { year: AcademicYear | null; onClose: () => void }) {
  const t = useTranslations("school.academic.years");
  const tv = useTranslations("school.validation");
  const { school } = useSchool();
  const [name, setName] = useState(year?.name ?? "");
  const [startsOn, setStartsOn] = useState(year?.startsOn ?? "");
  const [endsOn, setEndsOn] = useState(year?.endsOn ?? "");
  const [status, setStatus] = useState<string>(year?.status ?? "PLANNED");
  const [makeCurrent, setMakeCurrent] = useState(false);
  const [errors, setErrors] = useState<Record<string, FieldError>>({});

  const save = useSubmit({
    mutationFn: async (_: void, idempotencyKey) => {
      const api = getBrowserApi();
      const base = { name: name.trim(), startsOn, endsOn, status: status as AcademicYear["status"] };
      if (year) {
        return unwrap(
          await api.PATCH("/api/v1/schools/{schoolId}/academic-years/{yearId}", {
            params: { path: { schoolId: school.id, yearId: year.id } },
            body: base,
          }),
        );
      }
      return unwrap(
        await api.POST("/api/v1/schools/{schoolId}/academic-years", {
          params: { path: { schoolId: school.id } },
          headers: { "Idempotency-Key": idempotencyKey },
          body: { ...base, makeCurrent },
        }),
      );
    },
    invalidate: [sk.years(school.id), sk.summary(school.id), sk.classes(school.id)],
    successMessage: t("saved"),
    errorFallback: t("saveFailed"),
    toastOnError: false,
    onSuccess: onClose,
  });
  const server = fieldErrors(save.error);
  const message = (key: string, local?: FieldError) => (local ? tv(local) : (server[key] ?? null));

  function submit() {
    const next = {
      name: required(name),
      startsOn: required(startsOn),
      endsOn: required(endsOn) ?? dateOrder(startsOn, endsOn),
    };
    setErrors(next);
    if (Object.values(next).some(Boolean)) return;
    save.mutate();
  }

  return (
    <FormDialog
      title={year ? t("editTitle") : t("add")}
      pending={save.isPending}
      error={save.isError && Object.keys(server).length === 0 ? errorMessage(save.error, t("saveFailed")) : null}
      onSubmit={submit}
      onClose={onClose}
    >
      <FormField label={t("fields.name")} hint={t("fields.nameHint")} error={message("name", errors.name)}>
        {(props) => <Input {...props} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />}
      </FormField>
      <div className="grid gap-4 sm:grid-cols-2">
        <FormField label={t("fields.startsOn")} error={message("startsOn", errors.startsOn)}>
          {(props) => <Input {...props} type="date" value={startsOn} onChange={(e) => setStartsOn(e.target.value)} />}
        </FormField>
        <FormField label={t("fields.endsOn")} error={message("endsOn", errors.endsOn)}>
          {(props) => <Input {...props} type="date" value={endsOn} onChange={(e) => setEndsOn(e.target.value)} />}
        </FormField>
      </div>
      <SelectField
        label={t("fields.status")}
        value={status}
        onChange={setStatus}
        options={YEAR_STATUSES.map((s) => ({ value: s, label: t(`status.${s}`) }))}
      />
      {!year ? <CheckboxField label={t("fields.makeCurrent")} checked={makeCurrent} onChange={setMakeCurrent} /> : null}
    </FormDialog>
  );
}

function PeriodsOfYear({ year }: { year: AcademicYear }) {
  const t = useTranslations("school.academic.periods");
  const { school } = useSchool();
  const format = useFormatDate();
  const [editing, setEditing] = useState<AcademicPeriod | "new" | null>(null);
  const [deleting, setDeleting] = useState<AcademicPeriod | null>(null);
  const periods = useQuery({
    queryKey: sk.periods(school.id, year.id),
    queryFn: () => fetchPeriods(school.id, year.id),
  });
  const remove = useSubmit({
    mutationFn: async (periodId: string) =>
      ensureOk(
        await getBrowserApi().DELETE("/api/v1/schools/{schoolId}/academic-years/{yearId}/periods/{periodId}", {
          params: { path: { schoolId: school.id, yearId: year.id, periodId } },
        }),
      ),
    invalidate: [sk.periods(school.id, year.id)],
    successMessage: t("deleted"),
    errorFallback: t("saveFailed"),
    toastOnError: false,
    onSuccess: () => setDeleting(null),
  });

  return (
    <div className="flex flex-col gap-3 rounded-xl bg-muted/40 p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="font-semibold">{t("title")}</h3>
        <Button size="sm" variant="outline" onClick={() => setEditing("new")}>
          <PlusIcon className="size-4" aria-hidden />
          {t("add")}
        </Button>
      </div>
      {periods.isPending ? (
        <LoadingRows rows={2} />
      ) : periods.isError ? (
        <ErrorNotice message={errorMessage(periods.error, t("loadFailed"))} onRetry={() => void periods.refetch()} />
      ) : periods.data.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t("empty")}</p>
      ) : (
        <ul className="flex flex-col gap-1">
          {[...periods.data]
            .sort((a, b) => a.sequence - b.sequence)
            .map((period) => (
              <li key={period.id} className="flex flex-wrap items-center gap-3 rounded-lg bg-card px-3 py-2">
                <span className="grid size-8 place-items-center rounded-full bg-primary/10 text-sm font-bold text-primary">
                  {period.sequence}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block font-semibold">{period.name}</span>
                  <span className="block text-sm text-muted-foreground">
                    {t(`type.${period.periodType}`)} · {format(period.startsOn)} – {format(period.endsOn)}
                  </span>
                </span>
                <Button
                  size="icon-sm"
                  variant="ghost"
                  aria-label={t("edit", { name: period.name })}
                  onClick={() => setEditing(period)}
                >
                  <PencilIcon className="size-4" aria-hidden />
                </Button>
                <Button
                  size="icon-sm"
                  variant="ghost"
                  aria-label={t("delete", { name: period.name })}
                  onClick={() => setDeleting(period)}
                >
                  <Trash2Icon className="size-4" aria-hidden />
                </Button>
              </li>
            ))}
        </ul>
      )}
      {editing !== null ? (
        <PeriodDialog year={year} period={editing === "new" ? null : editing} onClose={() => setEditing(null)} />
      ) : null}
      {deleting ? (
        <ConfirmDialog
          title={t("deleteTitle", { name: deleting.name })}
          description={t("deleteBody")}
          confirmLabel={t("deleteConfirm")}
          pending={remove.isPending}
          error={remove.isError ? errorMessage(remove.error, t("saveFailed")) : null}
          onConfirm={() => remove.mutate(deleting.id)}
          onClose={() => setDeleting(null)}
        />
      ) : null}
    </div>
  );
}

function PeriodDialog({
  year,
  period,
  onClose,
}: {
  year: AcademicYear;
  period: AcademicPeriod | null;
  onClose: () => void;
}) {
  const t = useTranslations("school.academic.periods");
  const tv = useTranslations("school.validation");
  const { school } = useSchool();
  const format = useFormatDate();
  const [name, setName] = useState(period?.name ?? "");
  const [periodType, setPeriodType] = useState<string>(period?.periodType ?? "TERM");
  const [sequence, setSequence] = useState(period ? String(period.sequence) : "");
  const [startsOn, setStartsOn] = useState(period?.startsOn ?? "");
  const [endsOn, setEndsOn] = useState(period?.endsOn ?? "");
  const [errors, setErrors] = useState<Record<string, FieldError>>({});

  const save = useSubmit({
    mutationFn: async (_: void, idempotencyKey) => {
      const api = getBrowserApi();
      const base = {
        name: name.trim(),
        periodType: periodType as AcademicPeriod["periodType"],
        startsOn,
        endsOn,
        ...(sequence.trim() ? { sequence: Number(sequence) } : {}),
      };
      if (period) {
        return unwrap(
          await api.PATCH("/api/v1/schools/{schoolId}/academic-years/{yearId}/periods/{periodId}", {
            params: { path: { schoolId: school.id, yearId: year.id, periodId: period.id } },
            body: base,
          }),
        );
      }
      return unwrap(
        await api.POST("/api/v1/schools/{schoolId}/academic-years/{yearId}/periods", {
          params: { path: { schoolId: school.id, yearId: year.id } },
          headers: { "Idempotency-Key": idempotencyKey },
          body: base,
        }),
      );
    },
    invalidate: [sk.periods(school.id, year.id)],
    successMessage: t("saved"),
    errorFallback: t("saveFailed"),
    toastOnError: false,
    onSuccess: onClose,
  });
  const server = fieldErrors(save.error);
  const message = (key: string, local?: FieldError) => (local ? tv(local) : (server[key] ?? null));

  function submit() {
    const next = {
      name: required(name),
      startsOn: required(startsOn),
      endsOn: required(endsOn) ?? dateOrder(startsOn, endsOn),
      sequence: sequence.trim() && !/^[1-9]\d{0,3}$/.test(sequence.trim()) ? ("invalid" as const) : null,
    };
    setErrors(next);
    if (Object.values(next).some(Boolean)) return;
    save.mutate();
  }

  return (
    <FormDialog
      title={period ? t("editTitle") : t("add")}
      description={t("withinYear", { name: year.name, from: format(year.startsOn), to: format(year.endsOn) })}
      pending={save.isPending}
      error={save.isError && Object.keys(server).length === 0 ? errorMessage(save.error, t("saveFailed")) : null}
      onSubmit={submit}
      onClose={onClose}
    >
      <FormField label={t("fields.name")} error={message("name", errors.name)}>
        {(props) => <Input {...props} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />}
      </FormField>
      <div className="grid gap-4 sm:grid-cols-2">
        <SelectField
          label={t("fields.type")}
          value={periodType}
          onChange={setPeriodType}
          options={PERIOD_TYPES.map((p) => ({ value: p, label: t(`type.${p}`) }))}
        />
        <FormField label={t("fields.sequence")} hint={t("fields.sequenceHint")} error={message("sequence", errors.sequence)}>
          {(props) => (
            <Input {...props} inputMode="numeric" value={sequence} onChange={(e) => setSequence(e.target.value)} />
          )}
        </FormField>
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        <FormField label={t("fields.startsOn")} error={message("startsOn", errors.startsOn)}>
          {(props) => <Input {...props} type="date" value={startsOn} onChange={(e) => setStartsOn(e.target.value)} />}
        </FormField>
        <FormField label={t("fields.endsOn")} error={message("endsOn", errors.endsOn)}>
          {(props) => <Input {...props} type="date" value={endsOn} onChange={(e) => setEndsOn(e.target.value)} />}
        </FormField>
      </div>
    </FormDialog>
  );
}
