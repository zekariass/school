"use client";

import { FileTextIcon, Trash2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { ImageUploader } from "@/components/media/image-uploader";
import { MediaImage } from "@/components/media/media-image";
import { Button } from "@/components/ui/button";
import { MAX_FILES, allowedMimeTypes, type ComposerFile } from "@/lib/portfolio/composer";
import type { PortfolioItemType } from "@/lib/portfolio/queries";

/**
 * The files of an item: upload (purpose PORTFOLIO, context SCHOOL; an upload is private to the uploader until it is
 * attached to an item), preview and remove. Photos and scans show a small rendition; a PDF shows a file chip.
 */
export function FileField({
  schoolId,
  itemType,
  files,
  onChange,
  disabled,
}: {
  schoolId: string;
  itemType: PortfolioItemType;
  files: readonly ComposerFile[];
  onChange: (files: ComposerFile[]) => void;
  disabled?: boolean;
}) {
  const t = useTranslations("school.portfolio.form");
  return (
    <fieldset className="flex flex-col gap-3" disabled={disabled} data-testid="portfolio-files">
      <legend className="text-sm font-medium">{t(`files.${itemType === "DOCUMENT" ? "document" : itemType === "SCAN" ? "scan" : "photo"}`)}</legend>
      {files.length > 0 ? (
        <ul className="flex flex-wrap gap-3">
          {files.map((file, index) => (
            <li key={file.mediaId} className="flex w-28 flex-col gap-1" data-testid="portfolio-file">
              <span className="grid size-28 place-items-center overflow-hidden rounded-xl bg-muted">
                {file.kind === "IMAGE" ? (
                  <MediaImage mediaId={file.mediaId} variant="THUMB" alt={t("fileAlt", { number: index + 1 })} width={112} height={112} className="size-full object-cover" />
                ) : (
                  <span className="flex flex-col items-center gap-1 text-xs font-medium text-muted-foreground">
                    <FileTextIcon className="size-8" aria-hidden />
                    {t("pdf")}
                  </span>
                )}
              </span>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={() => onChange(files.filter((f) => f.mediaId !== file.mediaId))}
                aria-label={t("removeFile", { number: index + 1 })}
                data-testid="portfolio-file-remove"
              >
                <Trash2Icon aria-hidden />
                {t("remove")}
              </Button>
            </li>
          ))}
        </ul>
      ) : null}
      {files.length < MAX_FILES ? (
        <ImageUploader
          purpose="PORTFOLIO"
          context={{ type: "SCHOOL", id: schoolId }}
          hasImage={false}
          disabled={disabled}
          allowedMimeTypes={allowedMimeTypes(itemType)}
          labels={{ choose: t("addFile"), change: t("addFile"), remove: t("remove") }}
          onUploaded={async (mediaId, media) =>
            onChange([...files, { mediaId, kind: media.kind === "IMAGE" ? "IMAGE" : "DOCUMENT" }])
          }
        />
      ) : (
        <p className="text-sm text-muted-foreground">{t("fileLimit", { max: MAX_FILES })}</p>
      )}
    </fieldset>
  );
}
