"use client";

import { useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { useFormatDate } from "@/components/calendar-preference";
import { FormField } from "@/components/console/form-field";
import { ErrorNotice, LoadingRows } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage, fieldErrors } from "@/lib/console/errors";
import { fetchCampuses, sk, type StaffView } from "@/lib/school/queries";
import { ConfirmDialog } from "./confirm-dialog";
import { SelectField } from "./fields";
import { useSchool } from "./school-context";
import { StudentAvatar } from "./student-avatar";

/** One staff member: details, edit job title, campus and employee number, deactivate. */
export function StaffDetailDialog({ staffId, onClose }: { staffId: string; onClose: () => void }) {
  const t = useTranslations("school.teachers.detail");
  const { school } = useSchool();
  const staff = useQuery({
    queryKey: [...sk.staff(school.id), "one", staffId],
    queryFn: async () =>
      unwrap(
        await getBrowserApi().GET("/api/v1/schools/{schoolId}/staff/{staffId}", {
          params: { path: { schoolId: school.id, staffId } },
        }),
      ),
  });

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto">
        {staff.isPending ? (
          <>
            <DialogHeader>
              <DialogTitle>{t("title")}</DialogTitle>
              <DialogDescription>{t("loading")}</DialogDescription>
            </DialogHeader>
            <LoadingRows rows={3} />
          </>
        ) : staff.isError ? (
          <>
            <DialogHeader>
              <DialogTitle>{t("title")}</DialogTitle>
            </DialogHeader>
            <ErrorNotice message={errorMessage(staff.error, t("loadFailed"))} onRetry={() => void staff.refetch()} />
          </>
        ) : (
          <StaffForm staff={staff.data} onClose={onClose} />
        )}
      </DialogContent>
    </Dialog>
  );
}

function StaffForm({ staff, onClose }: { staff: StaffView; onClose: () => void }) {
  const t = useTranslations("school.teachers.detail");
  const tType = useTranslations("school.teachers.type");
  const tStatus = useTranslations("school.teachers.status");
  const { school } = useSchool();
  const format = useFormatDate();
  const [jobTitle, setJobTitle] = useState(staff.jobTitle ?? "");
  const [employeeNumber, setEmployeeNumber] = useState(staff.employeeNumber ?? "");
  const [campusId, setCampusId] = useState(staff.campusId ?? "");
  const [confirming, setConfirming] = useState(false);
  const campusList = useQuery({
    queryKey: sk.campuses(school.id),
    queryFn: () => fetchCampuses(school.id),
    staleTime: 300_000,
    retry: false,
  });

  const save = useSubmit({
    mutationFn: async () =>
      unwrap(
        await getBrowserApi().PATCH("/api/v1/schools/{schoolId}/staff/{staffId}", {
          params: { path: { schoolId: school.id, staffId: staff.id } },
          body: {
            jobTitle: jobTitle.trim(),
            employeeNumber: employeeNumber.trim(),
            ...(campusId ? { campusId } : {}),
          },
        }),
      ),
    invalidate: [sk.staff(school.id)],
    successMessage: t("saved"),
    errorFallback: t("saveFailed"),
    toastOnError: false,
    onSuccess: onClose,
  });
  const deactivate = useSubmit({
    mutationFn: async () =>
      unwrap(
        await getBrowserApi().POST("/api/v1/schools/{schoolId}/staff/{staffId}/deactivate", {
          params: { path: { schoolId: school.id, staffId: staff.id } },
        }),
      ),
    invalidate: [sk.staff(school.id), sk.summary(school.id), sk.classes(school.id)],
    successMessage: t("deactivated"),
    errorFallback: t("saveFailed"),
    toastOnError: false,
    onSuccess: onClose,
  });
  const server = fieldErrors(save.error);
  const former = staff.status === "FORMER";

  return (
    <form
      noValidate
      className="flex flex-col gap-4"
      onSubmit={(event) => {
        event.preventDefault();
        save.mutate();
      }}
    >
      <DialogHeader>
        <div className="flex items-center gap-3">
          <StudentAvatar id={staff.userId} name={staff.displayName} mediaId={staff.avatar?.id} size={56} />
          <div className="min-w-0 text-start">
            <DialogTitle className="truncate">{staff.displayName}</DialogTitle>
            <DialogDescription className="flex flex-wrap items-center gap-2 pt-1">
              <Badge variant="secondary">{tType(staff.staffType)}</Badge>
              <Badge variant={former ? "outline" : "default"}>{tStatus(staff.status)}</Badge>
            </DialogDescription>
          </div>
        </div>
      </DialogHeader>

      <dl className="grid gap-2 text-sm sm:grid-cols-2">
        <div>
          <dt className="text-muted-foreground">{t("contact")}</dt>
          <dd className="font-mono">{staff.maskedPhone ?? staff.maskedEmail ?? "—"}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">{t("started")}</dt>
          <dd>{staff.startedOn ? format(staff.startedOn) : "—"}</dd>
        </div>
        {staff.endedOn ? (
          <div>
            <dt className="text-muted-foreground">{t("ended")}</dt>
            <dd>{format(staff.endedOn)}</dd>
          </div>
        ) : null}
      </dl>

      {!former ? (
        <>
          <FormField label={t("jobTitle")} error={server.jobTitle ?? null}>
            {(props) => <Input {...props} value={jobTitle} maxLength={100} onChange={(e) => setJobTitle(e.target.value)} />}
          </FormField>
          <FormField label={t("employeeNumber")} error={server.employeeNumber ?? null}>
            {(props) => (
              <Input {...props} value={employeeNumber} maxLength={64} onChange={(e) => setEmployeeNumber(e.target.value)} />
            )}
          </FormField>
          {campusList.data && campusList.data.items.length > 0 ? (
            <SelectField
              label={t("campus")}
              value={campusId}
              onChange={setCampusId}
              noneLabel={t("noCampus")}
              options={campusList.data.items.map((c) => ({ value: c.id, label: c.name }))}
            />
          ) : null}
        </>
      ) : null}

      {save.isError && Object.keys(server).length === 0 ? (
        <p role="alert" className="text-sm font-medium text-destructive">
          {errorMessage(save.error, t("saveFailed"))}
        </p>
      ) : null}

      <DialogFooter className="sm:justify-between">
        {!former ? (
          <Button type="button" variant="outline" className="text-destructive" onClick={() => setConfirming(true)}>
            {t("deactivate")}
          </Button>
        ) : (
          <span />
        )}
        <div className="flex flex-col-reverse gap-2 sm:flex-row">
          <Button type="button" variant="outline" onClick={onClose}>
            {t("close")}
          </Button>
          {!former ? (
            <Button type="submit" disabled={save.isPending}>
              {save.isPending ? t("saving") : t("save")}
            </Button>
          ) : null}
        </div>
      </DialogFooter>

      {confirming ? (
        <ConfirmDialog
          title={t("deactivateTitle", { name: staff.displayName })}
          description={t("deactivateBody")}
          confirmLabel={t("deactivateConfirm")}
          pending={deactivate.isPending}
          error={deactivate.isError ? errorMessage(deactivate.error, t("saveFailed")) : null}
          onConfirm={() => deactivate.mutate()}
          onClose={() => setConfirming(false)}
        />
      ) : null}
    </form>
  );
}
