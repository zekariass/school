"use client";

import { useQuery } from "@tanstack/react-query";
import { PencilIcon, PlusIcon, PowerIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { FormField } from "@/components/console/form-field";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { SelectField } from "@/components/school/fields";
import { LocalizedInputs } from "@/components/school/localized-fields";
import { FormDialog } from "@/components/school/form-dialog";
import { useSchool } from "@/components/school/school-context";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage, fieldErrors } from "@/lib/console/errors";
import { asBadgeType, badgeCriteria, BADGE_CODE, suggestCode, type BadgeCriteriaType } from "@/lib/points/badges";
import { BADGE_ICON_CHOICES } from "@/lib/points/icons";
import { fetchBadges, fetchCategories, pk, type BadgeDefinition } from "@/lib/points/queries";
import { compactTexts, hasText, inputLanguages } from "@/lib/school/localized";
import { cn } from "@/lib/utils";
import { CategoryIcon } from "./category-icon";

const CREATABLE: BadgeCriteriaType[] = ["MANUAL", "POINTS_TOTAL", "CATEGORY_TOTAL"];

/** The school's badges: the default set plus its own. Create, rename, change the rule's numbers, (de)activate. */
export function BadgesManager() {
  const t = useTranslations("school.badges");
  const { school } = useSchool();
  const [editing, setEditing] = useState<BadgeDefinition | "new" | null>(null);
  const typeLabel = (value: string) => {
    const known = asBadgeType(value);
    return known ? t(`type.${known}`) : value;
  };
  const badges =useQuery({ queryKey: [...pk.badges(school.id), "all"], queryFn: () => fetchBadges(school.id, true) });

  const toggle = useSubmit({
    mutationFn: async (badge: BadgeDefinition) =>
      unwrap(
        await getBrowserApi().PATCH("/api/v1/schools/{schoolId}/badges/{badgeId}", {
          params: { path: { schoolId: school.id, badgeId: badge.id } },
          body: { active: badge.active === false },
        }),
      ),
    invalidate: [pk.badges(school.id)],
    errorFallback: t("failed"),
  });

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-muted-foreground">{t("intro")}</p>
        <Button onClick={() => setEditing("new")} data-testid="add-badge">
          <PlusIcon className="size-4" aria-hidden />
          {t("add")}
        </Button>
      </div>
      {badges.isPending ? (
        <LoadingRows rows={4} />
      ) : badges.isError ? (
        <ErrorNotice message={errorMessage(badges.error, t("loadFailed"))} onRetry={() => void badges.refetch()} />
      ) : badges.data.length === 0 ? (
        <EmptyNotice>{t("empty")}</EmptyNotice>
      ) : (
        <ul className="grid gap-3 sm:grid-cols-2" aria-label={t("listLabel")}>
          {badges.data.map((badge) => (
            <li key={badge.id} data-testid="badge-row" className={cn("flex items-center gap-3 rounded-2xl border bg-card p-3", badge.active === false && "opacity-60")}>
              <span aria-hidden className="grid size-12 shrink-0 place-items-center rounded-xl bg-muted text-2xl">
                <CategoryIcon icon={badge.icon} fallback="award" className="size-6 text-2xl" />
              </span>
              <div className="min-w-0 flex-1">
                <p className="truncate font-semibold">{badge.name}</p>
                <p className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                  <Badge variant="secondary">{typeLabel(badge.criteriaType)}</Badge>
                  {badge.active === false ? <Badge variant="outline">{t("inactive")}</Badge> : null}
                  {typeof badge.criteria.threshold === "number" ? <span>{t("threshold", { count: badge.criteria.threshold })}</span> : null}
                  {typeof badge.criteria.days === "number" ? <span>{t("days", { count: badge.criteria.days })}</span> : null}
                </p>
              </div>
              <div className="flex gap-1">
                <Button size="icon-sm" variant="ghost" aria-label={t("edit", { name: badge.name })} onClick={() => setEditing(badge)}>
                  <PencilIcon className="size-4" aria-hidden />
                </Button>
                <Button
                  size="icon-sm"
                  variant="ghost"
                  aria-label={t(badge.active === false ? "activate" : "deactivate", { name: badge.name })}
                  disabled={toggle.isPending}
                  onClick={() => toggle.mutate(badge)}
                >
                  <PowerIcon className="size-4" aria-hidden />
                </Button>
              </div>
            </li>
          ))}
        </ul>
      )}
      {toggle.isError ? (
        <p role="alert" className="text-sm font-medium text-destructive">
          {errorMessage(toggle.error, t("failed"))}
        </p>
      ) : null}
      {editing ? <BadgeForm badge={editing === "new" ? null : editing} onClose={() => setEditing(null)} /> : null}
    </div>
  );
}

function BadgeForm({ badge, onClose }: { badge: BadgeDefinition | null; onClose: () => void }) {
  const t = useTranslations("school.badges.form");
  const tb = useTranslations("school.badges");
  const tv = useTranslations("school.validation");
  const { school } = useSchool();
  const [type, setType] = useState<BadgeCriteriaType>((badge?.criteriaType as BadgeCriteriaType | undefined) ?? "MANUAL");
  // One input per language of the school's country (D42), plus any language the saved badge already has.
  const [names, setNames] = useState<Record<string, string>>(() => ({ ...badge?.names }));
  const [descriptions, setDescriptions] = useState<Record<string, string>>(() => ({ ...badge?.descriptions }));
  const nameLanguages = inputLanguages(school.country.languageCodes, badge?.names);
  const descriptionLanguages = inputLanguages(school.country.languageCodes, badge?.descriptions);
  const [code, setCode] = useState(badge?.code ?? "");
  const [icon, setIcon] = useState<string>(badge?.icon || BADGE_ICON_CHOICES[0]);
  const [threshold, setThreshold] = useState(String(badge?.criteria.threshold ?? 50));
  const [days, setDays] = useState(String(badge?.criteria.days ?? 5));
  const [categoryId, setCategoryId] = useState(typeof badge?.criteria.categoryId === "string" ? badge.criteria.categoryId : "");
  const [local, setLocal] = useState<{ name?: boolean; code?: boolean; criteria?: boolean }>({});
  const categories = useQuery({
    queryKey: pk.categories(school.id, null, "badge-form"),
    queryFn: () => fetchCategories(school.id, null),
    enabled: type === "CATEGORY_TOTAL",
  });
  const criteria = badgeCriteria(type, { threshold, days, categoryId });
  const effectiveCode = badge ? badge.code : code.trim();

  const save = useSubmit({
    mutationFn: async (_: void, idempotencyKey) => {
      const api = getBrowserApi();
      if (badge) {
        return unwrap(
          await api.PATCH("/api/v1/schools/{schoolId}/badges/{badgeId}", {
            params: { path: { schoolId: school.id, badgeId: badge.id } },
            // The rule's other parameters (context, category) stay as they are.
            body: { names: compactTexts(names), descriptions: compactTexts(descriptions), icon, ...(criteria ? { criteria: { ...badge.criteria, ...criteria } } : {}) },
          }),
        );
      }
      return unwrap(
        await api.POST("/api/v1/schools/{schoolId}/badges", {
          params: { path: { schoolId: school.id } },
          headers: { "Idempotency-Key": idempotencyKey },
          body: { code: effectiveCode, names: compactTexts(names), descriptions: compactTexts(descriptions), icon, criteriaType: type, ...(criteria ? { criteria } : {}) },
        }),
      );
    },
    invalidate: [pk.badges(school.id)],
    successMessage: t("saved"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: onClose,
  });
  const server = fieldErrors(save.error);
  const hasRule = type !== "MANUAL";

  return (
    <FormDialog
      title={badge ? t("editTitle") : t("addTitle")}
      className="sm:max-w-xl"
      pending={save.isPending}
      error={save.isError && Object.keys(server).length === 0 ? errorMessage(save.error, t("failed")) : null}
      onSubmit={() => {
        const next = {
          name: !hasText(names),
          code: !badge && !BADGE_CODE.test(effectiveCode),
          criteria: hasRule && (criteria === null || (type === "CATEGORY_TOTAL" && !badge && !categoryId)),
        };
        setLocal(next);
        if (!next.name && !next.code && !next.criteria) save.mutate();
      }}
      onClose={onClose}
    >
      <LocalizedInputs
        languages={nameLanguages}
        values={names}
        onChange={(next) => {
          // while the code still follows the name, keep it following
          if (!badge && code === suggestCode(nameLanguages, names)) setCode(suggestCode(nameLanguages, next));
          setNames(next);
        }}
        label={(language) => t("nameIn", { language })}
        maxLength={60}
        error={local.name ? tv("required") : (server.names ?? null)}
        hint={t("nameHint")}
      />
      <LocalizedInputs
        languages={descriptionLanguages}
        values={descriptions}
        onChange={setDescriptions}
        label={(language) => t("descriptionIn", { language })}
        maxLength={300}
      />
      {!badge ? (
        <>
          <FormField label={t("code")} hint={t("codeHint")} error={local.code ? t("codeInvalid") : (server.code ?? null)}>
            {(props) => <Input {...props} value={code} maxLength={41} onChange={(e) => setCode(e.target.value)} className="font-mono" />}
          </FormField>
          <SelectField
            label={t("type")}
            value={type}
            onChange={(v) => setType(CREATABLE.find((x) => x === v) ?? "MANUAL")}
            options={CREATABLE.map((value) => ({ value, label: tb(`type.${value}`) }))}
            hint={t(`typeHint.${type}`)}
          />
        </>
      ) : (
        <p className="text-sm text-muted-foreground">{t("typeFixed", { type: tb(`type.${type}`) })}</p>
      )}
      {type === "POINTS_TOTAL" || type === "CATEGORY_TOTAL" ? (
        <FormField label={t("threshold")} error={local.criteria && !criteria ? t("criteriaInvalid") : null}>
          {(props) => <Input {...props} type="number" inputMode="numeric" min={1} max={100000} value={threshold} onChange={(e) => setThreshold(e.target.value)} className="w-32" />}
        </FormField>
      ) : null}
      {type === "ATTENDANCE_STREAK" ? (
        <FormField label={t("days")} error={local.criteria && !criteria ? t("criteriaInvalid") : null}>
          {(props) => <Input {...props} type="number" inputMode="numeric" min={1} max={365} value={days} onChange={(e) => setDays(e.target.value)} className="w-32" />}
        </FormField>
      ) : null}
      {type === "CATEGORY_TOTAL" && !badge ? (
        <SelectField
          label={t("category")}
          value={categoryId}
          onChange={setCategoryId}
          placeholder={t("categoryPlaceholder")}
          error={local.criteria && !categoryId ? t("criteriaInvalid") : null}
          options={(categories.data ?? []).filter((c) => c.context !== "HOME").map((c) => ({ value: c.id, label: c.name }))}
        />
      ) : null}
      <fieldset className="flex flex-col gap-2">
        <legend className="pb-1 text-sm leading-none font-medium">{t("icon")}</legend>
        <div className="flex flex-wrap gap-2" role="radiogroup" aria-label={t("icon")}>
          {BADGE_ICON_CHOICES.map((choice, index) => (
            <button
              key={choice}
              type="button"
              role="radio"
              aria-checked={icon === choice}
              aria-label={t("iconN", { n: index + 1 })}
              onClick={() => setIcon(choice)}
              className={cn("grid size-11 place-items-center rounded-xl border text-2xl focus-visible:ring-[3px] focus-visible:ring-ring focus-visible:outline-none", icon === choice && "border-primary bg-primary/10 ring-2 ring-primary")}
            >
              <CategoryIcon icon={choice} fallback="award" className="size-6" />
            </button>
          ))}
        </div>
      </fieldset>
    </FormDialog>
  );
}
