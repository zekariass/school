"use client";

import { useQuery } from "@tanstack/react-query";
import { ArrowDownIcon, ArrowUpIcon, ListChecksIcon, PencilIcon, PlayIcon, PlusIcon, Trash2Icon, XIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { FormField } from "@/components/console/form-field";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { ConfirmDialog } from "@/components/school/confirm-dialog";
import { SelectField } from "@/components/school/fields";
import { FormDialog } from "@/components/school/form-dialog";
import { useSchool } from "@/components/school/school-context";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { ensureOk, unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { newIdempotencyKey } from "@/lib/console/idempotency";
import { errorMessage, fieldErrors } from "@/lib/console/errors";
import { classPointsAbilities } from "@/lib/points/access";
import { fetchTemplates, pk, type ActivityTemplate, type TemplateType, type TemplateVisibility } from "@/lib/points/queries";
import { draftsFrom, itemBodies, moveDraft, progress, totalMinutes, visibilityChoices, type ItemDraft } from "@/lib/points/routines";
import type { ClassView } from "@/lib/school/queries";
import { cn } from "@/lib/utils";

const TYPES: TemplateType[] = ["ROUTINE", "CHECKLIST", "ACTIVITY"];

/** Routines and checklists: saved per teacher, per class or school-wide; "Use" opens a tick-list for the lesson. */
export function RoutinesTool({ cls }: { cls: ClassView }) {
  const t = useTranslations("school.toolkit.routines");
  const { school, permissions } = useSchool();
  const abilities = classPointsAbilities(cls, permissions);
  const [using, setUsing] = useState<ActivityTemplate | null>(null);
  const [editing, setEditing] = useState<ActivityTemplate | "new" | null>(null);
  const [deleting, setDeleting] = useState<ActivityTemplate | null>(null);
  const templates = useQuery({
    queryKey: pk.templates(school.id, cls.id),
    queryFn: () => fetchTemplates(school.id, cls.id),
  });

  const remove = useSubmit({
    mutationFn: async (id: string) =>
      ensureOk(
        await getBrowserApi().DELETE("/api/v1/schools/{schoolId}/activity-templates/{templateId}", {
          params: { path: { schoolId: school.id, templateId: id } },
        }),
      ),
    invalidate: [pk.allTemplates(school.id)],
    successMessage: t("deleted"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: () => setDeleting(null),
  });

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-muted-foreground">{t("intro")}</p>
        <Button onClick={() => setEditing("new")} data-testid="new-routine">
          <PlusIcon className="size-4" aria-hidden />
          {t("new")}
        </Button>
      </div>

      {templates.isPending ? (
        <LoadingRows rows={3} />
      ) : templates.isError ? (
        <ErrorNotice message={errorMessage(templates.error, t("loadFailed"))} onRetry={() => void templates.refetch()} />
      ) : templates.data.items.length === 0 ? (
        <EmptyNotice>{t("empty")}</EmptyNotice>
      ) : (
        <ul className="grid gap-3 sm:grid-cols-2" aria-label={t("listLabel")}>
          {templates.data.items.map((template) => (
            <li key={template.id}>
              <Card data-testid="routine-card" className="h-full">
                <CardContent className="flex h-full flex-col gap-3">
                  <div className="flex items-start gap-3">
                    <ListChecksIcon className="mt-0.5 size-5 shrink-0 text-primary" aria-hidden />
                    <div className="min-w-0 flex-1">
                      <h3 className="font-bold break-words">{template.title}</h3>
                      <p className="flex flex-wrap items-center gap-1.5 pt-1 text-xs text-muted-foreground">
                        <Badge variant="secondary">{t(`type.${template.templateType}`)}</Badge>
                        <Badge variant="outline">{t(`visibility.${template.visibility}`)}</Badge>
                        <span>{t("items", { count: template.items.length })}</span>
                        {totalMinutes(template.items) > 0 ? <span>{t("minutes", { count: totalMinutes(template.items) })}</span> : null}
                      </p>
                      {!template.mine ? <p className="pt-1 text-xs text-muted-foreground">{t("by", { name: template.ownerName })}</p> : null}
                    </div>
                  </div>
                  <div className="mt-auto flex flex-wrap gap-2">
                    <Button size="sm" onClick={() => setUsing(template)} disabled={template.items.length === 0} data-testid="use-routine">
                      <PlayIcon className="size-4" aria-hidden />
                      {t("use")}
                    </Button>
                    {template.mine || abilities.canShareSchoolWide ? (
                      <>
                        <Button size="sm" variant="outline" onClick={() => setEditing(template)}>
                          <PencilIcon className="size-4" aria-hidden />
                          {t("edit")}
                          <span className="sr-only">{template.title}</span>
                        </Button>
                        <Button
                          size="icon-sm"
                          variant="ghost"
                          aria-label={t("delete", { title: template.title })}
                          onClick={() => {
                            remove.reset();
                            setDeleting(template);
                          }}
                        >
                          <Trash2Icon className="size-4" aria-hidden />
                        </Button>
                      </>
                    ) : null}
                  </div>
                </CardContent>
              </Card>
            </li>
          ))}
        </ul>
      )}

      {using ? <TickList template={using} onClose={() => setUsing(null)} /> : null}
      {editing ? (
        <TemplateForm cls={cls} template={editing === "new" ? null : editing} canShareSchoolWide={abilities.canShareSchoolWide} onClose={() => setEditing(null)} />
      ) : null}
      {deleting ? (
        <ConfirmDialog
          title={t("deleteTitle", { title: deleting.title })}
          description={t("deleteBody")}
          confirmLabel={t("deleteConfirm")}
          pending={remove.isPending}
          error={remove.isError ? errorMessage(remove.error, t("failed")) : null}
          onConfirm={() => remove.mutate(deleting.id)}
          onClose={() => setDeleting(null)}
        />
      ) : null}
    </div>
  );
}

/** The routine as a tick-list for the lesson. Ticks live in this dialog's state only. */
function TickList({ template, onClose }: { template: ActivityTemplate; onClose: () => void }) {
  const t = useTranslations("school.toolkit.routines");
  const [checked, setChecked] = useState<ReadonlySet<number>>(new Set());
  const p = progress(template.items.length, checked);

  function toggle(index: number) {
    setChecked((current) => {
      const next = new Set(current);
      if (next.has(index)) next.delete(index);
      else next.add(index);
      return next;
    });
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>{template.title}</DialogTitle>
          <DialogDescription>{template.instructions || t("tickIntro")}</DialogDescription>
        </DialogHeader>
        <div
          role="progressbar"
          aria-valuemin={0}
          aria-valuemax={p.total}
          aria-valuenow={p.done}
          aria-label={t("progress", { done: p.done, total: p.total })}
          className="h-3 overflow-hidden rounded-full bg-muted"
        >
          <div className="h-full rounded-full bg-primary motion-safe:transition-all" style={{ width: `${Math.round(p.ratio * 100)}%` }} />
        </div>
        <ul className="flex flex-col gap-1" data-testid="tick-list">
          {template.items.map((item, index) => (
            <li key={index}>
              <label className={cn("flex min-h-12 cursor-pointer items-center gap-3 rounded-xl px-3 hover:bg-accent", checked.has(index) && "text-muted-foreground")}>
                <input type="checkbox" className="size-6 accent-primary" checked={checked.has(index)} onChange={() => toggle(index)} />
                <span className={cn("flex-1 text-base", checked.has(index) && "line-through")}>{item.text}</span>
                {item.minutes ? <span className="text-sm text-muted-foreground">{t("minutes", { count: item.minutes })}</span> : null}
              </label>
            </li>
          ))}
        </ul>
        <p role="status" className="text-center font-semibold">
          {p.complete ? t("allDone") : t("progress", { done: p.done, total: p.total })}
        </p>
        <div className="flex justify-between gap-2">
          <Button variant="outline" onClick={() => setChecked(new Set())}>
            {t("clearTicks")}
          </Button>
          <Button onClick={onClose}>{t("close")}</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function TemplateForm({
  cls,
  template,
  canShareSchoolWide,
  onClose,
}: {
  cls: ClassView;
  template: ActivityTemplate | null;
  canShareSchoolWide: boolean;
  onClose: () => void;
}) {
  const t = useTranslations("school.toolkit.routines.form");
  const tr = useTranslations("school.toolkit.routines");
  const tv = useTranslations("school.validation");
  const { school } = useSchool();
  const [title, setTitle] = useState(template?.title ?? "");
  const [type, setType] = useState<TemplateType>(template?.templateType ?? "ROUTINE");
  const [visibility, setVisibility] = useState<TemplateVisibility>(template?.visibility ?? "PRIVATE");
  const [instructions, setInstructions] = useState(template?.instructions ?? "");
  const [items, setItems] = useState<ItemDraft[]>(() =>
    template && template.items.length > 0 ? draftsFrom(template.items, newIdempotencyKey) : draftsFrom([{}, {}, {}], newIdempotencyKey),
  );
  const [local, setLocal] = useState<{ title?: boolean; items?: boolean }>({});
  const choices = visibilityChoices(canShareSchoolWide);
  const allChoices = template && !choices.includes(template.visibility) ? [...choices, template.visibility] : choices;

  const save = useSubmit({
    mutationFn: async (_: void, idempotencyKey) => {
      const body = {
        title: title.trim(),
        templateType: type,
        visibility,
        ...(visibility === "CLASS" ? { classId: cls.id } : {}),
        instructions: instructions.trim() || undefined,
        items: itemBodies(items),
      };
      const api = getBrowserApi();
      return template
        ? unwrap(
            await api.PATCH("/api/v1/schools/{schoolId}/activity-templates/{templateId}", {
              params: { path: { schoolId: school.id, templateId: template.id } },
              body,
            }),
          )
        : unwrap(
            await api.POST("/api/v1/schools/{schoolId}/activity-templates", {
              params: { path: { schoolId: school.id } },
              headers: { "Idempotency-Key": idempotencyKey },
              body,
            }),
          );
    },
    invalidate: [pk.allTemplates(school.id)],
    successMessage: t("saved"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: onClose,
  });
  const server = fieldErrors(save.error);

  return (
    <FormDialog
      title={template ? t("editTitle") : t("newTitle")}
      className="sm:max-w-xl"
      pending={save.isPending}
      error={save.isError && Object.keys(server).length === 0 ? errorMessage(save.error, t("failed")) : null}
      onSubmit={() => {
        const next = { title: title.trim() === "", items: itemBodies(items).length === 0 };
        setLocal(next);
        if (!next.title && !next.items) save.mutate();
      }}
      onClose={onClose}
    >
      <FormField label={t("title")} error={local.title ? tv("required") : (server.title ?? null)}>
        {(props) => <Input {...props} value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} />}
      </FormField>
      <div className="grid gap-3 sm:grid-cols-2">
        <SelectField
          label={t("type")}
          value={type}
          onChange={(v) => setType(TYPES.find((x) => x === v) ?? "ROUTINE")}
          options={(TYPES.includes(type) ? TYPES : [...TYPES, type]).map((value) => ({ value, label: tr(`type.${value}`) }))}
        />
        <SelectField
          label={t("visibility")}
          value={visibility}
          onChange={(v) => setVisibility(allChoices.find((x) => x === v) ?? "PRIVATE")}
          options={allChoices.map((value) => ({ value, label: tr(`visibility.${value}`) }))}
          hint={visibility === "CLASS" ? t("visibilityClassHint", { name: cls.name }) : visibility === "SCHOOL" ? t("visibilitySchoolHint") : undefined}
        />
      </div>
      <FormField label={t("instructions")} error={server.instructions ?? null}>
        {(props) => <Textarea {...props} value={instructions} maxLength={5000} rows={2} onChange={(e) => setInstructions(e.target.value)} />}
      </FormField>
      <fieldset className="flex flex-col gap-2">
        <legend className="pb-1 text-sm leading-none font-medium">{t("items")}</legend>
        <ol className="flex flex-col gap-2">
          {items.map((item, index) => (
            <li key={item.key} className="flex items-center gap-1.5">
              <span className="w-5 text-center text-sm text-muted-foreground">{index + 1}</span>
              <Input
                aria-label={t("itemText", { n: index + 1 })}
                value={item.text}
                maxLength={300}
                onChange={(e) => setItems(items.map((x) => (x.key === item.key ? { ...x, text: e.target.value } : x)))}
                className="min-w-0 flex-1"
              />
              <Input
                aria-label={t("itemMinutes", { n: index + 1 })}
                inputMode="numeric"
                placeholder={t("minutesShort")}
                value={item.minutes}
                onChange={(e) => setItems(items.map((x) => (x.key === item.key ? { ...x, minutes: e.target.value } : x)))}
                className="w-16"
              />
              <Button type="button" size="icon-sm" variant="ghost" aria-label={t("moveUp", { n: index + 1 })} disabled={index === 0} onClick={() => setItems(moveDraft(items, index, -1))}>
                <ArrowUpIcon className="size-4" aria-hidden />
              </Button>
              <Button type="button" size="icon-sm" variant="ghost" aria-label={t("moveDown", { n: index + 1 })} disabled={index === items.length - 1} onClick={() => setItems(moveDraft(items, index, 1))}>
                <ArrowDownIcon className="size-4" aria-hidden />
              </Button>
              <Button type="button" size="icon-sm" variant="ghost" aria-label={t("removeItem", { n: index + 1 })} onClick={() => setItems(items.filter((x) => x.key !== item.key))}>
                <XIcon className="size-4" aria-hidden />
              </Button>
            </li>
          ))}
        </ol>
        <Button type="button" variant="outline" size="sm" className="self-start" onClick={() => setItems([...items, ...draftsFrom([{}], newIdempotencyKey)])} disabled={items.length >= 100}>
          <PlusIcon className="size-4" aria-hidden />
          {t("addItem")}
        </Button>
        {local.items ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {t("needItems")}
          </p>
        ) : null}
      </fieldset>
    </FormDialog>
  );
}
