"use client";

import { useQuery } from "@tanstack/react-query";
import { PencilIcon, PlusIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage, fieldErrors } from "@/lib/console/errors";
import { P } from "@/lib/console/permissions";
import { fetchCampuses, queryKeys, type Campus, type School } from "@/lib/console/queries";
import { useCan } from "./console-context";
import { FormField } from "./form-field";
import { EmptyNotice, ErrorNotice, LoadingRows } from "./states";
import { StatusBadge } from "./status-badge";
import { useSubmit } from "./use-submit";

export function SchoolCampuses({ school }: { school: School }) {
  const t = useTranslations("console");
  const canManage = useCan(P.schoolManage);
  const [editing, setEditing] = useState<Campus | "new" | null>(null);
  const campuses = useQuery({
    queryKey: queryKeys.campuses(school.id),
    queryFn: () => fetchCampuses(school.id),
  });
  const rows = campuses.data?.items ?? [];

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-lg font-semibold">{t("schoolTabs.campuses")}</h2>
        {canManage ? (
          <Button onClick={() => setEditing("new")}>
            <PlusIcon className="size-4" aria-hidden />
            {t("campuses.add")}
          </Button>
        ) : null}
      </div>

      {campuses.isPending ? (
        <LoadingRows rows={2} />
      ) : campuses.isError ? (
        <ErrorNotice message={errorMessage(campuses.error, t("loadFailed"))} onRetry={() => void campuses.refetch()} />
      ) : rows.length === 0 ? (
        <EmptyNotice>{t("campuses.empty")}</EmptyNotice>
      ) : (
        <div className="rounded-2xl border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("fields.name")}</TableHead>
                <TableHead>{t("fields.code")}</TableHead>
                <TableHead>{t("fields.timezone")}</TableHead>
                <TableHead>{t("fields.status")}</TableHead>
                <TableHead className="w-12" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((campus) => (
                <TableRow key={campus.id}>
                  <TableCell className="font-semibold">{campus.name}</TableCell>
                  <TableCell className="font-mono text-sm">{campus.code ?? "—"}</TableCell>
                  <TableCell>{campus.timezone ?? school.timezone}</TableCell>
                  <TableCell>
                    <StatusBadge status={campus.status} />
                  </TableCell>
                  <TableCell>
                    {canManage ? (
                      <Button
                        size="icon"
                        variant="ghost"
                        aria-label={t("campuses.edit", { name: campus.name })}
                        onClick={() => setEditing(campus)}
                      >
                        <PencilIcon className="size-4" aria-hidden />
                      </Button>
                    ) : null}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}

      {editing !== null ? (
        <CampusDialog school={school} campus={editing === "new" ? null : editing} onClose={() => setEditing(null)} />
      ) : null}
    </div>
  );
}

function CampusDialog({
  school,
  campus,
  onClose,
}: {
  school: School;
  campus: Campus | null;
  onClose: () => void;
}) {
  const t = useTranslations("console");
  const [name, setName] = useState(campus?.name ?? "");
  const [code, setCode] = useState(campus?.code ?? "");
  const [timezone, setTimezone] = useState(campus?.timezone ?? "");
  const [status, setStatus] = useState<Campus["status"]>(campus?.status ?? "ACTIVE");
  const [nameError, setNameError] = useState<string | null>(null);

  const save = useSubmit({
    mutationFn: async (_: void, idempotencyKey) => {
      const api = getBrowserApi();
      const body = {
        name: name.trim(),
        ...(code.trim() ? { code: code.trim() } : {}),
        ...(timezone.trim() ? { timezone: timezone.trim() } : {}),
      };
      if (campus) {
        return unwrap(
          await api.PUT("/api/v1/platform/schools/{schoolId}/campuses/{campusId}", {
            params: { path: { schoolId: school.id, campusId: campus.id } },
            body: { ...body, status },
          }),
        );
      }
      return unwrap(
        await api.POST("/api/v1/platform/schools/{schoolId}/campuses", {
          params: { path: { schoolId: school.id } },
          headers: { "Idempotency-Key": idempotencyKey },
          body,
        }),
      );
    },
    invalidate: [queryKeys.campuses(school.id)],
    successMessage: t("campuses.saved"),
    errorFallback: t("saveFailed"),
    toastOnError: false,
    onSuccess: onClose,
  });
  const serverErrors = fieldErrors(save.error);

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            if (!name.trim()) {
              setNameError(t("validation.required"));
              return;
            }
            setNameError(null);
            save.mutate();
          }}
        >
          <DialogHeader>
            <DialogTitle>{campus ? t("campuses.editTitle") : t("campuses.add")}</DialogTitle>
            <DialogDescription>{school.name}</DialogDescription>
          </DialogHeader>
          <FormField label={t("fields.name")} error={nameError ?? serverErrors.name}>
            {(props) => <Input {...props} value={name} maxLength={200} onChange={(e) => setName(e.target.value)} />}
          </FormField>
          <div className="grid gap-4 sm:grid-cols-2">
            <FormField label={t("fields.code")} error={serverErrors.code}>
              {(props) => <Input {...props} value={code} maxLength={32} onChange={(e) => setCode(e.target.value)} />}
            </FormField>
            <FormField label={t("fields.timezone")} hint={school.timezone} error={serverErrors.timezone}>
              {(props) => (
                <Input {...props} value={timezone} spellCheck={false} onChange={(e) => setTimezone(e.target.value)} />
              )}
            </FormField>
          </div>
          {campus ? (
            <FormField label={t("fields.status")}>
              {(props) => (
                <Select value={status} onValueChange={(v) => setStatus(v as Campus["status"])}>
                  <SelectTrigger {...props} className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="ACTIVE">{t("status.ACTIVE")}</SelectItem>
                    <SelectItem value="ARCHIVED">{t("status.ARCHIVED")}</SelectItem>
                  </SelectContent>
                </Select>
              )}
            </FormField>
          ) : null}
          {save.isError && Object.keys(serverErrors).length === 0 ? (
            <p role="alert" className="text-sm font-medium text-destructive">
              {errorMessage(save.error, t("saveFailed"))}
            </p>
          ) : null}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              {t("cancel")}
            </Button>
            <Button type="submit" disabled={save.isPending}>
              {save.isPending ? t("saving") : t("save")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
