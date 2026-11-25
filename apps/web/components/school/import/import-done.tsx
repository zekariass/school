"use client";

import { CircleCheckIcon, DownloadIcon, Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Link } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { errorReportName } from "@/lib/school/import/files";
import { downloadFile, errorReportPath } from "@/lib/school/import/queries";
import type { ImportJob, ImportKind } from "@/lib/school/import/state";
import { useSchool } from "../school-context";

/** Where the imported people or classes can be seen. */
const DESTINATION: Record<ImportKind, string> = {
  STUDENTS: "students",
  STAFF: "teachers",
  CLASSES: "classes",
  ENROLLMENTS: "classes",
  GRADE_LEVELS: "academic",
  SUBJECTS: "academic",
};

/** Step 5, finished: what the import did. Parent contacts are only counted: nobody is invited by an import. */
export function DoneStage({ job }: { job: ImportJob }) {
  const t = useTranslations("school.import.done");
  const { school } = useSchool();
  const [downloading, setDownloading] = useState(false);
  const result = job.result;
  const base = `/s/${encodeURIComponent(school.slug)}`;
  const contacts = result?.guardianContacts ?? 0;
  const hasReport = !job.rowsPurged && ((job.rows?.invalid ?? 0) > 0 || (result?.skipped ?? 0) > 0);

  async function report() {
    setDownloading(true);
    try {
      await downloadFile(errorReportPath(school.id, job.id), errorReportName(job.filename));
    } catch (error) {
      toast.error(errorMessage(error, t("reportFailed")));
    } finally {
      setDownloading(false);
    }
  }

  const tiles = [
    { key: "created", value: result?.created ?? 0 },
    { key: "updated", value: result?.updated ?? 0 },
    { key: "unchanged", value: result?.unchanged ?? 0 },
    { key: "skipped", value: result?.skipped ?? 0 },
  ] as const;

  return (
    <Card>
      <CardContent className="flex flex-col gap-5 py-8">
        <div className="flex items-center gap-3">
          <CircleCheckIcon className="size-7 shrink-0 text-success" aria-hidden />
          <h2 className="text-xl font-bold">{t("title")}</h2>
        </div>
        <dl className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          {tiles.map((tile) => (
            <div key={tile.key} className="rounded-2xl border bg-card p-3">
              <dd className="text-2xl font-bold tabular-nums">{tile.value}</dd>
              <dt className="text-sm text-muted-foreground">{t(tile.key)}</dt>
            </div>
          ))}
        </dl>
        {job.importType === "STUDENTS" && contacts > 0 ? (
          <div className="rounded-xl bg-muted/60 p-3 text-sm" role="note">
            <p className="font-semibold">{t("contacts", { count: contacts })}</p>
            <p className="text-muted-foreground">{t("contactsNote")}</p>
          </div>
        ) : null}
        <div className="flex flex-wrap gap-2">
          <Button asChild>
            <Link href={`${base}/${DESTINATION[job.importType]}`}>{t(`goTo.${job.importType}`)}</Link>
          </Button>
          <Button asChild variant="outline">
            <Link href={`${base}/import/new`}>{t("another")}</Link>
          </Button>
          {hasReport ? (
            <Button type="button" variant="ghost" onClick={() => void report()} disabled={downloading}>
              {downloading ? <Loader2Icon className="animate-spin" aria-hidden /> : <DownloadIcon aria-hidden />}
              {t("report")}
            </Button>
          ) : null}
        </div>
      </CardContent>
    </Card>
  );
}
