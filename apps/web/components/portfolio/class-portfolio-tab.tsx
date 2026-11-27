"use client";

import { CameraIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { ErrorNotice, LoadingRows } from "@/components/console/states";
import { useSchool } from "@/components/school/school-context";
import { Button } from "@/components/ui/button";
import { errorMessage } from "@/lib/console/errors";
import { workspaceItemPath } from "@/lib/portfolio/paths";
import { fetchClassGallery, fetchQueue, pfk } from "@/lib/portfolio/queries";
import { ItemFormDialog } from "./item-form";
import { ItemList } from "./item-list";
import { usePortfolioCapabilities } from "./use-portfolio";

/**
 * The portfolio tab of a class page: the review queue for the teachers who review this class, the class gallery
 * (approved items the class may see) and the quick capture of a student's work. Sections come from the API's
 * `GET /portfolio/capabilities`; what each list shows is exactly what the API answers (its 403 is shown, not guessed).
 */
export function ClassPortfolioTab({ classId }: { classId: string }) {
  const t = useTranslations("school.portfolio.classTab");
  const { school } = useSchool();
  const [capturing, setCapturing] = useState(false);
  const caps = usePortfolioCapabilities(school.id);
  const href = (item: { id: string }) => workspaceItemPath(school.slug, item.id);

  if (caps.isPending) return <LoadingRows rows={3} />;
  if (caps.isError) return <ErrorNotice message={errorMessage(caps.error, t("unavailable"))} onRetry={() => void caps.refetch()} />;
  const canCapture = !!caps.data.canCapture && caps.data.captureClassIds.includes(classId);

  return (
    <div className="flex flex-col gap-8" data-testid="class-portfolio">
      {canCapture ? (
        <section className="flex flex-col gap-2">
          <p className="text-sm text-muted-foreground">{t("captureHelp")}</p>
          <Button type="button" className="self-start" onClick={() => setCapturing(true)} data-testid="portfolio-capture-open">
            <CameraIcon aria-hidden />
            {t("capture")}
          </Button>
        </section>
      ) : null}

      {caps.data.reviewer ? (
        <section aria-labelledby="portfolio-queue-title" className="flex flex-col gap-3">
          <h2 id="portfolio-queue-title" className="text-xl font-bold">
            {t("queue")}
          </h2>
          <p className="text-sm text-muted-foreground">{t("queueHelp")}</p>
          <ItemList
            queryKey={pfk.queue(school.id, classId)}
            fetchPage={(cursor) => fetchQueue(school.id, classId, cursor)}
            itemHref={href}
            showStudent
            emptyText={t("queueEmpty")}
            failedText={t("queueFailed")}
            testId="portfolio-queue"
          />
        </section>
      ) : null}

      <section aria-labelledby="portfolio-gallery-title" className="flex flex-col gap-3">
        <h2 id="portfolio-gallery-title" className="text-xl font-bold">
          {t("gallery")}
        </h2>
        <p className="text-sm text-muted-foreground">{t("galleryHelp")}</p>
        <ItemList
          queryKey={pfk.classGallery(school.id, classId)}
          fetchPage={(cursor) => fetchClassGallery(school.id, classId, cursor)}
          itemHref={href}
          showStudent
          emptyText={t("galleryEmpty")}
          failedText={t("galleryFailed")}
          testId="portfolio-class-gallery"
        />
      </section>

      {capturing ? <ItemFormDialog schoolId={school.id} mode={{ kind: "capture", classId }} onClose={() => setCapturing(false)} /> : null}
    </div>
  );
}
