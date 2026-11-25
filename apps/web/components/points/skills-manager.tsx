"use client";

import { useQuery } from "@tanstack/react-query";
import { PencilIcon, PlusIcon, PowerIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { FormField } from "@/components/console/form-field";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { FormDialog } from "@/components/school/form-dialog";
import { SelectField } from "@/components/school/fields";
import { useSchool } from "@/components/school/school-context";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage, fieldErrors } from "@/lib/console/errors";
import {
  CATEGORY_COLORS,
  DEFAULT_CATEGORY_COLOR,
  DEFAULT_CATEGORY_ICON,
  categoryColor,
  isClassOwn,
  manageableCategories,
  readableOn,
} from "@/lib/points/categories";
import { SKILL_ICON_CHOICES } from "@/lib/points/icons";
import { fetchCategories, pk, type PointCategory } from "@/lib/points/queries";
import { cn } from "@/lib/utils";
import { CategoryIcon } from "./category-icon";

export type SkillsScope = { kind: "school" } | { kind: "class"; classId: string };

/** The skills of a class (in a dialog on the Points tab) or of the school (the school's Points page). */
export function SkillsManager({ scope, onClose }: { scope: SkillsScope; onClose: () => void }) {
  const t = useTranslations("school.points.skills");
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{t("dialogTitle")}</DialogTitle>
          <DialogDescription>{t("dialogIntro")}</DialogDescription>
        </DialogHeader>
        <SkillsPanel scope={scope} />
      </DialogContent>
    </Dialog>
  );
}

export function SkillsPanel({ scope }: { scope: SkillsScope }) {
  const t = useTranslations("school.points.skills");
  const { school } = useSchool();
  const classId = scope.kind === "class" ? scope.classId : null;
  const [editing, setEditing] = useState<PointCategory | "new" | null>(null);
  const categories = useQuery({
    queryKey: pk.categories(school.id, classId, "manage"),
    queryFn: () => fetchCategories(school.id, classId, { includeInactive: true }),
  });

  const toggle = useSubmit({
    mutationFn: async (category: PointCategory) => {
      const body = { active: category.active === false };
      return scope.kind === "class"
        ? unwrap(
            await getBrowserApi().PATCH("/api/v1/schools/{schoolId}/classes/{classId}/point-categories/{categoryId}", {
              params: { path: { schoolId: school.id, classId: scope.classId, categoryId: category.id } },
              body,
            }),
          )
        : unwrap(
            await getBrowserApi().PATCH("/api/v1/schools/{schoolId}/point-categories/{categoryId}", {
              params: { path: { schoolId: school.id, categoryId: category.id } },
              body,
            }),
          );
    },
    invalidate: [pk.allCategories(school.id)],
    errorFallback: t("failed"),
  });

  const editable = (category: PointCategory) => (scope.kind === "class" ? isClassOwn(category, scope.classId) : !category.classId);

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-muted-foreground">{scope.kind === "class" ? t("introClass") : t("introSchool")}</p>
        <Button onClick={() => setEditing("new")} data-testid="add-skill">
          <PlusIcon className="size-4" aria-hidden />
          {t("add")}
        </Button>
      </div>
      {categories.isPending ? (
        <LoadingRows rows={4} />
      ) : categories.isError ? (
        <ErrorNotice message={errorMessage(categories.error, t("loadFailed"))} onRetry={() => void categories.refetch()} />
      ) : categories.data.length === 0 ? (
        <EmptyNotice>{t("empty")}</EmptyNotice>
      ) : (
        <ul className="flex flex-col gap-2" aria-label={t("listLabel")}>
          {manageableCategories(categories.data).map((category) => (
            <li
              key={category.id}
              data-testid="skill-row"
              className={cn("flex flex-wrap items-center gap-3 rounded-2xl border p-3", category.active === false && "opacity-60")}
            >
              <span
                aria-hidden
                style={{ backgroundColor: categoryColor(category), color: readableOn(categoryColor(category)) }}
                className="grid size-11 shrink-0 place-items-center rounded-xl text-2xl"
              >
                <CategoryIcon icon={category.icon} className="size-6 text-2xl" />
              </span>
              <div className="min-w-0 flex-1">
                <p className="truncate font-semibold">{category.name}</p>
                <p className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                  <Badge variant={category.polarity === "NEEDS_WORK" ? "destructive" : "secondary"}>
                    {t(category.polarity === "NEEDS_WORK" ? "polarity.NEEDS_WORK" : "polarity.POSITIVE")}
                  </Badge>
                  <Badge variant="outline">{category.classId ? t("scope.class") : category.context === "HOME" ? t("scope.home") : t("scope.school")}</Badge>
                  {category.active === false ? <Badge variant="outline">{t("inactive")}</Badge> : null}
                  <span>{t("defaultPoints", { count: category.defaultPoints ?? 1 })}</span>
                </p>
              </div>
              {editable(category) ? (
                <div className="flex gap-1">
                  <Button size="icon-sm" variant="ghost" aria-label={t("edit", { name: category.name })} onClick={() => setEditing(category)}>
                    <PencilIcon className="size-4" aria-hidden />
                  </Button>
                  <Button
                    size="icon-sm"
                    variant="ghost"
                    aria-label={t(category.active === false ? "activate" : "deactivate", { name: category.name })}
                    disabled={toggle.isPending}
                    onClick={() => toggle.mutate(category)}
                  >
                    <PowerIcon className="size-4" aria-hidden />
                  </Button>
                </div>
              ) : (
                <span className="text-xs text-muted-foreground">{t("readOnly")}</span>
              )}
            </li>
          ))}
        </ul>
      )}
      {toggle.isError ? (
        <p role="alert" className="text-sm font-medium text-destructive">
          {errorMessage(toggle.error, t("failed"))}
        </p>
      ) : null}
      {editing ? <CategoryForm scope={scope} category={editing === "new" ? null : editing} onClose={() => setEditing(null)} /> : null}
    </div>
  );
}

function CategoryForm({ scope, category, onClose }: { scope: SkillsScope; category: PointCategory | null; onClose: () => void }) {
  const t = useTranslations("school.points.skills.form");
  const tv = useTranslations("school.validation");
  const { school } = useSchool();
  const [en, setEn] = useState(category?.names.en ?? "");
  const [am, setAm] = useState(category?.names.am ?? "");
  const [pointsText, setPointsText] = useState(String(category?.defaultPoints ?? 1));
  const [polarity, setPolarity] = useState<"POSITIVE" | "NEEDS_WORK">(category?.polarity ?? "POSITIVE");
  const [context, setContext] = useState<"CLASS" | "HOME">(category?.context === "HOME" ? "HOME" : "CLASS");
  const [icon, setIcon] = useState(category?.icon || DEFAULT_CATEGORY_ICON);
  const [color, setColor] = useState(category?.color || DEFAULT_CATEGORY_COLOR);
  const [localError, setLocalError] = useState<{ name?: boolean; points?: boolean }>({});

  const save = useSubmit({
    mutationFn: async (_: void, idempotencyKey) => {
      const names: Record<string, string> = {};
      if (en.trim()) names.en = en.trim();
      if (am.trim()) names.am = am.trim();
      const defaultPoints = Number(pointsText);
      const api = getBrowserApi();
      if (category) {
        const body = { names, icon, color, defaultPoints };
        return scope.kind === "class"
          ? unwrap(
              await api.PATCH("/api/v1/schools/{schoolId}/classes/{classId}/point-categories/{categoryId}", {
                params: { path: { schoolId: school.id, classId: scope.classId, categoryId: category.id } },
                body,
              }),
            )
          : unwrap(
              await api.PATCH("/api/v1/schools/{schoolId}/point-categories/{categoryId}", {
                params: { path: { schoolId: school.id, categoryId: category.id } },
                body,
              }),
            );
      }
      const body = { names, icon, color, defaultPoints, polarity, ...(scope.kind === "school" ? { context } : {}) };
      return scope.kind === "class"
        ? unwrap(
            await api.POST("/api/v1/schools/{schoolId}/classes/{classId}/point-categories", {
              params: { path: { schoolId: school.id, classId: scope.classId } },
              headers: { "Idempotency-Key": idempotencyKey },
              body,
            }),
          )
        : unwrap(
            await api.POST("/api/v1/schools/{schoolId}/point-categories", {
              params: { path: { schoolId: school.id } },
              headers: { "Idempotency-Key": idempotencyKey },
              body,
            }),
          );
    },
    invalidate: [pk.allCategories(school.id)],
    successMessage: t("saved"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: onClose,
  });
  const server = fieldErrors(save.error);
  const homeOnly = scope.kind === "school" && context === "HOME";

  return (
    <FormDialog
      title={category ? t("editTitle") : t("addTitle")}
      pending={save.isPending}
      error={save.isError && Object.keys(server).length === 0 ? errorMessage(save.error, t("failed")) : null}
      onSubmit={() => {
        const points = Number(pointsText);
        const next = { name: !en.trim() && !am.trim(), points: !Number.isInteger(points) || points < 1 || points > 10 };
        setLocalError(next);
        if (!next.name && !next.points) save.mutate();
      }}
      onClose={onClose}
    >
      <FormField label={t("nameEn")} error={localError.name ? tv("required") : (server.names ?? null)}>
        {(props) => <Input {...props} value={en} maxLength={60} onChange={(e) => setEn(e.target.value)} />}
      </FormField>
      <FormField label={t("nameAm")} hint={t("nameHint")}>
        {(props) => <Input {...props} value={am} maxLength={60} lang="am" onChange={(e) => setAm(e.target.value)} />}
      </FormField>
      <FormField label={t("points")} error={localError.points ? t("pointsInvalid") : (server.defaultPoints ?? null)}>
        {(props) => (
          <Input {...props} type="number" inputMode="numeric" min={1} max={10} value={pointsText} onChange={(e) => setPointsText(e.target.value)} className="w-28" />
        )}
      </FormField>
      {!category ? (
        <SelectField
          label={t("polarity")}
          value={homeOnly ? "POSITIVE" : polarity}
          onChange={(v) => setPolarity(v === "NEEDS_WORK" ? "NEEDS_WORK" : "POSITIVE")}
          disabled={homeOnly}
          options={[
            { value: "POSITIVE", label: t("positive") },
            { value: "NEEDS_WORK", label: t("needsWork") },
          ]}
        />
      ) : null}
      {!category && scope.kind === "school" ? (
        <SelectField
          label={t("context")}
          value={context}
          onChange={(v) => setContext(v === "HOME" ? "HOME" : "CLASS")}
          hint={context === "HOME" ? t("contextHomeHint") : undefined}
          options={[
            { value: "CLASS", label: t("contextClass") },
            { value: "HOME", label: t("contextHome") },
          ]}
        />
      ) : null}
      <fieldset className="flex flex-col gap-2">
        <legend className="pb-1 text-sm leading-none font-medium">{t("icon")}</legend>
        <div className="flex flex-wrap gap-2" role="radiogroup" aria-label={t("icon")}>
          {SKILL_ICON_CHOICES.map((choice, index) => (
            <button
              key={choice}
              type="button"
              role="radio"
              aria-checked={icon === choice}
              aria-label={t("iconN", { n: index + 1 })}
              onClick={() => setIcon(choice)}
              className={cn("grid size-11 place-items-center rounded-xl border text-2xl focus-visible:ring-[3px] focus-visible:ring-ring focus-visible:outline-none", icon === choice && "border-primary bg-primary/10 ring-2 ring-primary")}
            >
              <CategoryIcon icon={choice} fallback="star" className="size-6" />
            </button>
          ))}
        </div>
      </fieldset>
      <fieldset className="flex flex-col gap-2">
        <legend className="pb-1 text-sm leading-none font-medium">{t("color")}</legend>
        <div className="flex flex-wrap gap-2" role="radiogroup" aria-label={t("color")}>
          {CATEGORY_COLORS.map((choice, index) => (
            <button
              key={choice}
              type="button"
              role="radio"
              aria-checked={color.toLowerCase() === choice.toLowerCase()}
              aria-label={t("colorN", { n: index + 1 })}
              onClick={() => setColor(choice)}
              style={{ backgroundColor: choice }}
              className={cn(
                "size-11 rounded-full border-2 border-transparent focus-visible:ring-[3px] focus-visible:ring-ring focus-visible:outline-none",
                color.toLowerCase() === choice.toLowerCase() && "border-foreground ring-2 ring-background ring-offset-2 ring-offset-foreground",
              )}
            />
          ))}
        </div>
      </fieldset>
    </FormDialog>
  );
}
