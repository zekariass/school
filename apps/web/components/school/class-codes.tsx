"use client";

import { PrinterIcon, QrCodeIcon, TriangleAlertIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { FormField } from "@/components/console/form-field";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { useRouter } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { sheetForClass, type CodeKind } from "@/lib/school/codes";
import type { ClassView } from "@/lib/school/queries";
import { ConfirmDialog } from "./confirm-dialog";
import { SelectField } from "./fields";
import { useIssuedCodes } from "./issued-codes-context";
import { useSchool } from "./school-context";
import { useIssueCodes } from "./use-issue-codes";
import { useSchoolSetting } from "./use-setting";

const KINDS: readonly CodeKind[] = ["BOTH", "QR", "TEXT"];

/** Generate the login-code sheet of the whole class and open the printable view. */
export function ClassCodesTab({ cls }: { cls: ClassView }) {
  const t = useTranslations("school.codes");
  const tKind = useTranslations("school.codes.kind");
  const { school } = useSchool();
  const router = useRouter();
  const { sheet, put } = useIssuedCodes();
  const [kind, setKind] = useState<CodeKind>("BOTH");
  const [ttl, setTtl] = useState("");
  const [ttlError, setTtlError] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const defaultTtl = useSchoolSetting<number>("students.login_code.ttl_days");
  const issue = useIssueCodes();
  const existing = sheetForClass(sheet, cls.id);
  const printPath = `/s/${encodeURIComponent(school.slug)}/classes/${cls.id}/login-codes/print`;

  function start() {
    const parsed = ttl.trim() === "" ? undefined : Number(ttl);
    if (parsed !== undefined && !(Number.isInteger(parsed) && parsed >= 1 && parsed <= 730)) {
      setTtlError(true);
      return;
    }
    setTtlError(false);
    setConfirming(true);
  }

  function generate() {
    issue.mutate(
      {
        classId: cls.id,
        className: cls.name,
        kind,
        ttlDays: ttl.trim() === "" ? undefined : Number(ttl),
      },
      {
        onSuccess: (result) => {
          put(result);
          setConfirming(false);
          issue.reset(); // the codes now live only in the print page's memory
          router.push(printPath);
        },
      },
    );
  }

  return (
    <div className="flex max-w-2xl flex-col gap-4">
      <Card className="shadow-none">
        <CardContent className="flex flex-col gap-4">
          <div className="flex items-start gap-3">
            <span className="grid size-12 shrink-0 place-items-center rounded-2xl bg-primary/10 text-primary">
              <QrCodeIcon className="size-6" aria-hidden />
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
          <div className="grid gap-4 sm:grid-cols-2">
            <SelectField
              label={t("kindLabel")}
              value={kind}
              onChange={(v) => setKind(v as CodeKind)}
              options={KINDS.map((k) => ({ value: k, label: tKind(k) }))}
            />
            <FormField
              label={t("ttlLabel")}
              hint={defaultTtl.value ? t("ttlHint", { days: defaultTtl.value }) : t("ttlHintUnknown")}
              error={ttlError ? t("ttlInvalid") : null}
            >
              {(props) => (
                <Input
                  {...props}
                  inputMode="numeric"
                  placeholder={defaultTtl.value ? String(defaultTtl.value) : ""}
                  value={ttl}
                  onChange={(e) => setTtl(e.target.value)}
                />
              )}
            </FormField>
          </div>
          {issue.isError ? (
            <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
              {errorMessage(issue.error, t("failed"))}
            </p>
          ) : null}
          <div className="flex flex-wrap items-center gap-3">
            <Button size="lg" disabled={!cls.studentCount || cls.status !== "ACTIVE"} onClick={start}>
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
      </Card>

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
    </div>
  );
}
