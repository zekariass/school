"use client";

import { HeartHandshakeIcon, PrinterIcon, TriangleAlertIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { useRouter } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { parentSheetForClass } from "@/lib/school/parent-codes";
import type { ClassView } from "@/lib/school/queries";
import { ConfirmDialog } from "./confirm-dialog";
import { useIssuedCodes } from "./issued-codes-context";
import { useSchool } from "./school-context";
import { useIssueParentCodes } from "./use-issue-parent-codes";

/** Print parent codes for the whole class (one card per child), shown once and printed from memory. */
export function ClassParentCodes({ cls }: { cls: ClassView }) {
  const t = useTranslations("school.families.classCodes");
  const { school } = useSchool();
  const router = useRouter();
  const { parentSheet, putParent } = useIssuedCodes();
  const [confirming, setConfirming] = useState(false);
  const issue = useIssueParentCodes();
  const existing = parentSheetForClass(parentSheet, cls.id);
  const printPath = `/s/${encodeURIComponent(school.slug)}/classes/${cls.id}/parent-codes/print`;

  function generate() {
    issue.mutate(
      { classId: cls.id, className: cls.name },
      {
        onSuccess: (sheet) => {
          putParent(sheet);
          setConfirming(false);
          issue.reset(); // the codes now live only in the print page's memory
          router.push(printPath);
        },
      },
    );
  }

  return (
    <Card className="shadow-none">
      <CardContent className="flex flex-col gap-4">
        <div className="flex items-start gap-3">
          <span className="grid size-12 shrink-0 place-items-center rounded-2xl bg-primary/10 text-primary">
            <HeartHandshakeIcon className="size-6" aria-hidden />
          </span>
          <div>
            <h2 className="text-lg font-bold">{t("title")}</h2>
            <p className="text-muted-foreground">{t("intro")}</p>
          </div>
        </div>
        <p className="flex items-start gap-2 rounded-xl bg-warning/15 p-3 text-sm font-medium" role="note">
          <TriangleAlertIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
          {t("replaceWarning")}
        </p>
        <div className="flex flex-wrap items-center gap-3">
          <Button size="lg" disabled={!cls.studentCount || cls.status !== "ACTIVE"} onClick={() => setConfirming(true)}>
            <PrinterIcon className="size-4" aria-hidden />
            {t("generate")}
          </Button>
          {!cls.studentCount ? <span className="text-sm text-muted-foreground">{t("noStudents")}</span> : null}
          {existing ? (
            <Button variant="outline" onClick={() => router.push(printPath)}>
              {t("openLast", { count: existing.codes.length })}
            </Button>
          ) : null}
        </div>
      </CardContent>
      {confirming ? (
        <ConfirmDialog
          title={t("confirmTitle", { class: cls.name })}
          description={t("confirmBody", { count: cls.studentCount })}
          confirmLabel={t("confirm")}
          destructive={false}
          pending={issue.isPending}
          error={issue.isError ? errorMessage(issue.error, t("failed")) : null}
          onConfirm={generate}
          onClose={() => setConfirming(false)}
        />
      ) : null}
    </Card>
  );
}
