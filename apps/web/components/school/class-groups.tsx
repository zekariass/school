"use client";

import { useQuery } from "@tanstack/react-query";
import { PlusIcon, Trash2Icon, UsersRoundIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { FormField } from "@/components/console/form-field";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ensureOk, unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage, fieldErrors } from "@/lib/console/errors";
import { fetchGroups, fetchRoster, sk, type ClassView, type StudentGroup } from "@/lib/school/queries";
import { ConfirmDialog } from "./confirm-dialog";
import { FormDialog } from "./form-dialog";
import { useSchool } from "./school-context";
import { cn } from "@/lib/utils";

/** Saved groups of a class. Making random groups is the M6 toolkit; here: list, delete, and a manual group. */
export function ClassGroupsTab({ cls }: { cls: ClassView }) {
  const t = useTranslations("school.groups");
  const { school } = useSchool();
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState<StudentGroup | null>(null);
  const groups = useQuery({ queryKey: sk.classGroups(school.id, cls.id), queryFn: () => fetchGroups(school.id, cls.id) });
  const roster = useQuery({ queryKey: sk.classRoster(school.id, cls.id), queryFn: () => fetchRoster(school.id, cls.id) });
  const names = new Map((roster.data ?? []).map((s) => [s.studentId, s.displayName]));

  const remove = useSubmit({
    mutationFn: async (groupId: string) =>
      ensureOk(
        await getBrowserApi().DELETE("/api/v1/schools/{schoolId}/classes/{classId}/groups/{groupId}", {
          params: { path: { schoolId: school.id, classId: cls.id, groupId } },
        }),
      ),
    invalidate: [sk.classGroups(school.id, cls.id)],
    successMessage: t("deleted"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: () => setDeleting(null),
  });

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-muted-foreground">{t("intro")}</p>
        <Button onClick={() => setCreating(true)} disabled={(roster.data ?? []).length === 0}>
          <PlusIcon className="size-4" aria-hidden />
          {t("create")}
        </Button>
      </div>
      {groups.isPending ? (
        <LoadingRows rows={2} />
      ) : groups.isError ? (
        <ErrorNotice message={errorMessage(groups.error, t("loadFailed"))} onRetry={() => void groups.refetch()} />
      ) : groups.data.length === 0 ? (
        <EmptyNotice>{t("empty")}</EmptyNotice>
      ) : (
        <ul className="grid gap-3 sm:grid-cols-2">
          {groups.data.map((group) => (
            <li key={group.id} className="flex flex-col gap-2 rounded-2xl border bg-card p-4">
              <div className="flex items-start gap-3">
                <UsersRoundIcon className="mt-0.5 size-5 shrink-0 text-primary" aria-hidden />
                <div className="min-w-0 flex-1">
                  <h3 className="truncate font-bold">{group.name}</h3>
                  <p className="text-sm text-muted-foreground">{t("members", { count: group.studentIds.length })}</p>
                </div>
                <Button
                  size="icon-sm"
                  variant="ghost"
                  aria-label={t("delete", { name: group.name })}
                  onClick={() => {
                    remove.reset();
                    setDeleting(group);
                  }}
                >
                  <Trash2Icon className="size-4" aria-hidden />
                </Button>
              </div>
              <p className="text-sm">
                {group.studentIds.map((id) => names.get(id) ?? "…").join(", ")}
              </p>
            </li>
          ))}
        </ul>
      )}
      {creating ? <GroupDialog cls={cls} onClose={() => setCreating(false)} /> : null}
      {deleting ? (
        <ConfirmDialog
          title={t("deleteTitle", { name: deleting.name })}
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

function GroupDialog({ cls, onClose }: { cls: ClassView; onClose: () => void }) {
  const t = useTranslations("school.groups.dialog");
  const tv = useTranslations("school.validation");
  const { school } = useSchool();
  const [name, setName] = useState("");
  const [ids, setIds] = useState<string[]>([]);
  const [local, setLocal] = useState<{ name?: boolean; students?: boolean }>({});
  const roster = useQuery({ queryKey: sk.classRoster(school.id, cls.id), queryFn: () => fetchRoster(school.id, cls.id) });
  const save = useSubmit({
    mutationFn: async (_: void, idempotencyKey) =>
      unwrap(
        await getBrowserApi().POST("/api/v1/schools/{schoolId}/classes/{classId}/groups", {
          params: { path: { schoolId: school.id, classId: cls.id } },
          headers: { "Idempotency-Key": idempotencyKey },
          body: { name: name.trim(), studentIds: ids },
        }),
      ),
    invalidate: [sk.classGroups(school.id, cls.id)],
    successMessage: t("saved"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: onClose,
  });
  const server = fieldErrors(save.error);
  return (
    <FormDialog
      title={t("title")}
      pending={save.isPending}
      error={save.isError && Object.keys(server).length === 0 ? errorMessage(save.error, t("failed")) : null}
      onSubmit={() => {
        const next = { name: name.trim() === "", students: ids.length === 0 };
        setLocal(next);
        if (!next.name && !next.students) save.mutate();
      }}
      onClose={onClose}
    >
      <FormField label={t("name")} error={local.name ? tv("required") : (server.name ?? null)}>
        {(props) => <Input {...props} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />}
      </FormField>
      <fieldset className="flex flex-col gap-2">
        <legend className="pb-1 text-sm leading-none font-medium">{t("members")}</legend>
        <div className="grid max-h-64 gap-1 overflow-y-auto rounded-xl border p-2 sm:grid-cols-2">
          {(roster.data ?? []).map((s) => {
            const on = ids.includes(s.studentId);
            return (
              <label
                key={s.studentId}
                className={cn("flex min-h-11 cursor-pointer items-center gap-2 rounded-lg px-2 hover:bg-accent", on && "bg-primary/5")}
              >
                <input
                  type="checkbox"
                  className="size-5 accent-primary"
                  checked={on}
                  onChange={() => setIds(on ? ids.filter((id) => id !== s.studentId) : [...ids, s.studentId])}
                />
                <span className="truncate text-sm font-medium">{s.displayName}</span>
              </label>
            );
          })}
        </div>
        {local.students ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {t("needMembers")}
          </p>
        ) : null}
      </fieldset>
    </FormDialog>
  );
}
