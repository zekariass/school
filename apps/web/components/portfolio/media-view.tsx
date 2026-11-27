"use client";

import { EyeOffIcon, FileTextIcon, Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { MediaImage } from "@/components/media/media-image";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { errorMessage } from "@/lib/console/errors";
import { fetchOriginalUrl } from "@/lib/media/queries";
import type { PortfolioItem, PortfolioMedia } from "@/lib/portfolio/queries";
import { documentFiles, imageFiles } from "@/lib/portfolio/view";

/**
 * The files of an item, exactly as the API sent them. When it withheld them (`mediaWithheld`, no `media`) the item
 * says so instead of guessing why. Pictures use the small rendition and open larger on request; a PDF opens in a new
 * tab through a short-lived signed link that is used once and never kept.
 */
export function ItemMedia({ item }: { item: PortfolioItem }) {
  const t = useTranslations("school.portfolio.media");
  const [open, setOpen] = useState<number | null>(null);
  const images = imageFiles(item.media);
  const documents = documentFiles(item.media);
  const withheld = item.mediaWithheld && item.media.length === 0;

  return (
    <div className="flex flex-col gap-3">
      {withheld ? (
        <p role="note" className="flex items-start gap-3 rounded-xl border border-dashed bg-muted/50 p-4 text-sm" data-testid="portfolio-withheld">
          <EyeOffIcon className="mt-0.5 size-5 shrink-0" aria-hidden />
          <span>
            <span className="block font-bold">{t("withheldTitle")}</span>
            {t("withheldBody")}
          </span>
        </p>
      ) : null}
      {item.mediaWithheld && item.media.length > 0 ? (
        <p role="note" className="flex items-start gap-3 rounded-xl border border-warning/50 bg-warning/10 p-4 text-sm" data-testid="portfolio-withheld-warning">
          <EyeOffIcon className="mt-0.5 size-5 shrink-0" aria-hidden />
          <span>
            <span className="block font-bold">{t("warningTitle")}</span>
            {t("warningBody")}
          </span>
        </p>
      ) : null}
      {images.length > 0 ? (
        <ul className="grid grid-cols-2 gap-2 sm:grid-cols-3" aria-label={t("pictures", { count: images.length })}>
          {images.map((file, index) => (
            <li key={file.mediaId}>
              <button
                type="button"
                onClick={() => setOpen(index)}
                aria-label={t("open", { number: index + 1, total: images.length })}
                className="block aspect-square w-full overflow-hidden rounded-xl bg-muted focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
                data-testid="portfolio-picture"
              >
                <MediaImage mediaId={file.mediaId} variant="SMALL" alt={t("alt", { number: index + 1, total: images.length })} width={file.width ?? 400} height={file.height ?? 400} className="size-full object-cover" />
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      {documents.length > 0 ? (
        <ul className="flex flex-col gap-2">
          {documents.map((file, index) => (
            <DocumentRow key={file.mediaId} file={file} number={index + 1} total={documents.length} />
          ))}
        </ul>
      ) : null}
      {open !== null && images[open] ? (
        <Dialog open onOpenChange={(next) => !next && setOpen(null)}>
          <DialogContent className="max-h-[92dvh] overflow-auto sm:max-w-3xl">
            <DialogTitle className="sr-only">{t("viewer")}</DialogTitle>
            <DialogDescription className="sr-only">{t("alt", { number: open + 1, total: images.length })}</DialogDescription>
            <MediaImage
              mediaId={images[open].mediaId}
              variant="MEDIUM"
              alt={t("alt", { number: open + 1, total: images.length })}
              width={images[open].width ?? 1024}
              height={images[open].height ?? 768}
              className="mx-auto max-h-[75dvh] w-auto max-w-full rounded-lg object-contain"
            />
          </DialogContent>
        </Dialog>
      ) : null}
    </div>
  );
}

function DocumentRow({ file, number, total }: { file: PortfolioMedia; number: number; total: number }) {
  const t = useTranslations("school.portfolio.media");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  async function openDocument() {
    setBusy(true);
    setProblem(null);
    try {
      const signed = await fetchOriginalUrl(file.mediaId);
      window.open(signed.url, "_blank", "noopener,noreferrer");
    } catch (error) {
      setProblem(errorMessage(error, t("openFailed")));
    } finally {
      setBusy(false);
    }
  }

  return (
    <li className="flex flex-col gap-1" data-testid="portfolio-document">
      <div className="flex items-center gap-3 rounded-xl border p-3">
        <FileTextIcon className="size-8 shrink-0 text-muted-foreground" aria-hidden />
        <span className="min-w-0 flex-1 text-sm font-medium">{t("document", { number, total })}</span>
        <Button type="button" variant="outline" size="sm" onClick={() => void openDocument()} disabled={busy} data-testid="portfolio-document-open">
          {busy ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : null}
          {t("openDocument")}
        </Button>
      </div>
      {problem ? (
        <p role="alert" className="text-sm font-medium text-destructive">
          {problem}
        </p>
      ) : null}
    </li>
  );
}
