"use client";

import { ArchiveIcon, ArchiveRestoreIcon, PencilIcon, Trash2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { useSubmit } from "@/components/console/use-submit";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { useRouter } from "@/i18n/navigation";
import { ensureOk, unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage, hasProblemCode } from "@/lib/console/errors";
import type { ClassAbilities } from "@/lib/school/class-view";
import { sk, type ClassView } from "@/lib/school/queries";
import { ClassFormDialog } from "./class-form";
import { ConfirmDialog } from "./confirm-dialog";
import { useSchool } from "./school-context";

/** Rename and restyle, archive or restore, delete when the class never had students. */
export function ClassSettingsTab({ cls, abilities }: { cls: ClassView; abilities: ClassAbilities }) {
  const t = useTranslations("school.classSettings");
  const { school } = useSchool();
  const router = useRouter();
  const [editing, setEditing] = useState(false);
  const [confirm, setConfirm] = useState<"archive" | "delete" | null>(null);
  const archived = cls.status === "ARCHIVED";

  const toggleArchive = useSubmit({
    mutationFn: async () =>
      unwrap(
        archived
          ? await getBrowserApi().POST("/api/v1/schools/{schoolId}/classes/{classId}/restore", {
              params: { path: { schoolId: school.id, classId: cls.id } },
            })
          : await getBrowserApi().POST("/api/v1/schools/{schoolId}/classes/{classId}/archive", {
              params: { path: { schoolId: school.id, classId: cls.id } },
            }),
      ),
    invalidate: [sk.classes(school.id), sk.summary(school.id)],
    successMessage: archived ? t("restored") : t("archivedDone"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: () => setConfirm(null),
  });
  const remove = useSubmit({
    mutationFn: async () =>
      ensureOk(
        await getBrowserApi().DELETE("/api/v1/schools/{schoolId}/classes/{classId}", {
          params: { path: { schoolId: school.id, classId: cls.id } },
        }),
      ),
    invalidate: [sk.classes(school.id), sk.summary(school.id)],
    successMessage: t("deleted"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: () => router.replace(`/s/${encodeURIComponent(school.slug)}/classes`),
  });
  const notEmpty = hasProblemCode(remove.error, "class.not_empty");

  return (
    <div className="flex max-w-2xl flex-col gap-4">
      <Card className="shadow-none">
        <CardContent className="flex flex-col gap-3">
          <h2 className="text-lg font-bold">{t("details")}</h2>
          <p className="text-muted-foreground">{t("detailsBody")}</p>
          <div>
            <Button variant="outline" onClick={() => setEditing(true)} disabled={archived}>
              <PencilIcon className="size-4" aria-hidden />
              {t("edit")}
            </Button>
          </div>
        </CardContent>
      </Card>

      <Card className="shadow-none">
        <CardContent className="flex flex-col gap-3">
          <h2 className="text-lg font-bold">{archived ? t("restoreTitle") : t("archiveTitle")}</h2>
          <p className="text-muted-foreground">{archived ? t("restoreBody") : t("archiveBody")}</p>
          <div>
            <Button
              variant="outline"
              onClick={() => {
                toggleArchive.reset();
                if (archived) toggleArchive.mutate();
                else setConfirm("archive");
              }}
              disabled={toggleArchive.isPending}
            >
              {archived ? <ArchiveRestoreIcon className="size-4" aria-hidden /> : <ArchiveIcon className="size-4" aria-hidden />}
              {archived ? t("restore") : t("archive")}
            </Button>
            {archived && toggleArchive.isError ? (
              <p role="alert" className="pt-2 text-sm font-medium text-destructive">
                {errorMessage(toggleArchive.error, t("failed"))}
              </p>
            ) : null}
          </div>
        </CardContent>
      </Card>

      {abilities.canLead ? (
        <Card className="border-destructive/30 shadow-none">
          <CardContent className="flex flex-col gap-3">
            <h2 className="text-lg font-bold">{t("deleteTitle")}</h2>
            <p className="text-muted-foreground">{t("deleteBody")}</p>
            <div>
              <Button
                variant="outline"
                className="text-destructive"
                onClick={() => {
                  remove.reset();
                  setConfirm("delete");
                }}
              >
                <Trash2Icon className="size-4" aria-hidden />
                {t("delete")}
              </Button>
            </div>
          </CardContent>
        </Card>
      ) : null}

      {editing ? <ClassFormDialog cls={cls} onClose={() => setEditing(false)} /> : null}
      {confirm === "archive" ? (
        <ConfirmDialog
          title={t("archiveConfirmTitle", { name: cls.name })}
          description={t("archiveConfirmBody")}
          confirmLabel={t("archive")}
          destructive={false}
          pending={toggleArchive.isPending}
          error={toggleArchive.isError ? errorMessage(toggleArchive.error, t("failed")) : null}
          onConfirm={() => toggleArchive.mutate()}
          onClose={() => setConfirm(null)}
        />
      ) : null}
      {confirm === "delete" ? (
        <ConfirmDialog
          title={t("deleteConfirmTitle", { name: cls.name })}
          description={t("deleteConfirmBody")}
          confirmLabel={t("delete")}
          pending={remove.isPending}
          error={remove.isError ? (notEmpty ? t("notEmpty") : errorMessage(remove.error, t("failed"))) : null}
          onConfirm={() => remove.mutate()}
          onClose={() => setConfirm(null)}
        />
      ) : null}
    </div>
  );
}
