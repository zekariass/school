"use client";

import { ArrowDownIcon, ArrowUpIcon, Trash2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { ImageUploader } from "@/components/media/image-uploader";
import { MediaImage } from "@/components/media/media-image";
import { Button } from "@/components/ui/button";
import { MAX_CAPTION, MAX_MEDIA, type ComposerPhoto } from "@/lib/feed/composer";

/**
 * The photos of a post or contribution: upload (purpose POST, context CLASS or SCHOOL), a description for each one
 * (it is the alt text and the caption), reorder and remove. An upload is private to the uploader until the post is
 * published.
 */
export function PhotoField({
  photos,
  onChange,
  context,
  disabled,
}: {
  photos: readonly ComposerPhoto[];
  onChange: (photos: ComposerPhoto[]) => void;
  context: { type: "CLASS" | "SCHOOL"; id: string };
  disabled?: boolean;
}) {
  const t = useTranslations("school.feed.composer");
  return (
    <fieldset className="flex flex-col gap-3" disabled={disabled}>
      <legend className="text-sm font-medium">{t("photos")}</legend>
      {photos.length > 0 ? (
        <ul className="flex flex-col gap-3">
          {photos.map((photo, index) => (
            <li key={photo.mediaId} className="flex items-start gap-3 rounded-xl border p-2" data-testid="composer-photo">
              <MediaImage mediaId={photo.mediaId} variant="THUMB" alt="" width={80} height={80} className="size-20 shrink-0 rounded-lg object-cover" />
              <div className="flex min-w-0 flex-1 flex-col gap-1">
                <label htmlFor={`caption-${photo.mediaId}`} className="text-xs font-medium">
                  {t("photoCaption", { number: index + 1 })}
                </label>
                <input
                  id={`caption-${photo.mediaId}`}
                  className="h-10 w-full rounded-md border border-input bg-transparent px-3 text-sm"
                  value={photo.caption}
                  maxLength={MAX_CAPTION}
                  onChange={(event) => onChange(photos.map((p) => (p.mediaId === photo.mediaId ? { ...p, caption: event.target.value } : p)))}
                  placeholder={t("photoCaptionHint")}
                  data-testid="photo-caption"
                />
              </div>
              <div className="flex flex-col gap-1">
                <Button type="button" variant="ghost" size="icon-sm" aria-label={t("moveUp", { number: index + 1 })} disabled={index === 0} onClick={() => onChange(move(photos, index, -1))}>
                  <ArrowUpIcon aria-hidden />
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  aria-label={t("moveDown", { number: index + 1 })}
                  disabled={index === photos.length - 1}
                  onClick={() => onChange(move(photos, index, 1))}
                >
                  <ArrowDownIcon aria-hidden />
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  aria-label={t("removePhoto", { number: index + 1 })}
                  onClick={() => onChange(photos.filter((p) => p.mediaId !== photo.mediaId))}
                  data-testid="photo-remove"
                >
                  <Trash2Icon aria-hidden />
                </Button>
              </div>
            </li>
          ))}
        </ul>
      ) : null}
      {photos.length < MAX_MEDIA ? (
        <ImageUploader
          purpose="POST"
          context={context}
          hasImage={false}
          disabled={disabled}
          labels={{ choose: t("addPhoto"), change: t("addPhoto"), remove: t("addPhoto") }}
          onUploaded={async (mediaId) => onChange([...photos, { mediaId, caption: "" }])}
        />
      ) : (
        <p className="text-sm text-muted-foreground">{t("photoLimit", { max: MAX_MEDIA })}</p>
      )}
    </fieldset>
  );
}

function move<T>(list: readonly T[], index: number, delta: number): T[] {
  const next = list.slice();
  const [item] = next.splice(index, 1);
  next.splice(index + delta, 0, item as T);
  return next;
}
