"use client";

import { ChevronLeftIcon, ChevronRightIcon, EyeOffIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { MediaImage } from "@/components/media/media-image";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import type { FeedMediaItem } from "@/lib/feed/queries";
import { COLLAGE_LIMIT, collagePhotos, collageShape } from "@/lib/feed/view";
import { cn } from "@/lib/utils";

/**
 * Photos of a post as a collage: one photo is wide, two sit side by side, three or more are one large tile with two
 * stacked beside it (a "+n" on the last tile when there are more). Lists use the small renditions (low data,
 * lazy-loaded); the larger one is fetched only when a photo is opened. The alt text is the author's caption, else a
 * numbered description.
 */
export function MediaGrid({ items }: { items: readonly FeedMediaItem[] }) {
  const t = useTranslations("school.feed.media");
  const [open, setOpen] = useState<number | null>(null);
  const images = items.filter((m) => m.kind === "IMAGE");
  if (images.length === 0) return null;
  const { shown, extra } = collagePhotos(images);
  const shape = collageShape(images.length);
  const alt = (item: FeedMediaItem, index: number) => item.caption?.trim() || t("alt", { number: index + 1, total: images.length });

  return (
    <>
      <ul
        className={cn(
          "grid gap-2 overflow-hidden rounded-2xl",
          shape === "one" && "grid-cols-1",
          shape === "two" && "grid-cols-2",
          shape === "three" && "aspect-[4/3] grid-cols-5 grid-rows-2 sm:aspect-[16/9]",
        )}
        aria-label={t("photos", { count: images.length })}
        data-testid="photo-collage"
        data-shape={shape}
      >
        {shown.map((item, index) => {
          const large = shape === "one" || (shape === "three" && index === 0);
          return (
            <li
              key={item.mediaId}
              className={cn(
                "relative overflow-hidden rounded-xl bg-muted",
                shape === "three" && (index === 0 ? "col-span-3 row-span-2" : "col-span-2"),
              )}
            >
              <button
                type="button"
                onClick={() => setOpen(index)}
                className={cn(
                  "block w-full focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none",
                  shape === "one" && "aspect-[4/3] max-h-[28rem] sm:aspect-[16/10]",
                  shape === "two" && "aspect-square sm:aspect-[4/3]",
                  shape === "three" && "h-full",
                )}
                aria-label={t("open", { number: index + 1, total: images.length })}
                data-testid="post-photo"
              >
                <MediaImage
                  mediaId={item.mediaId}
                  variant={large ? "SMALL" : "THUMB"}
                  alt={alt(item, index)}
                  width={item.width ?? 640}
                  height={item.height ?? 480}
                  className="size-full object-cover"
                />
                {extra > 0 && index === COLLAGE_LIMIT - 1 ? (
                  <span className="absolute inset-0 grid place-items-center bg-foreground/60 text-2xl font-bold text-background" data-testid="photo-extra">
                    +{extra}
                  </span>
                ) : null}
              </button>
            </li>
          );
        })}
      </ul>
      {open !== null ? <Lightbox items={images} index={open} onIndex={setOpen} alt={alt} onClose={() => setOpen(null)} /> : null}
    </>
  );
}

function Lightbox({
  items,
  index,
  onIndex,
  alt,
  onClose,
}: {
  items: readonly FeedMediaItem[];
  index: number;
  onIndex: (index: number) => void;
  alt: (item: FeedMediaItem, index: number) => string;
  onClose: () => void;
}) {
  const t = useTranslations("school.feed.media");
  const item = items[index];
  if (!item) return null;
  const go = (delta: number) => onIndex((index + delta + items.length) % items.length);
  return (
    <Dialog open onOpenChange={(next) => !next && onClose()}>
      <DialogContent
        className="max-h-[92dvh] max-w-3xl overflow-y-auto sm:max-w-3xl"
        onKeyDown={(event) => {
          if (event.key === "ArrowRight") go(1);
          if (event.key === "ArrowLeft") go(-1);
        }}
      >
        <DialogTitle className="sr-only">{t("viewer", { number: index + 1, total: items.length })}</DialogTitle>
        <DialogDescription className="sr-only">{t("viewerHelp")}</DialogDescription>
        <div className="flex justify-center bg-muted">
          <MediaImage
            mediaId={item.mediaId}
            variant="MEDIUM"
            alt={alt(item, index)}
            width={item.width ?? 1280}
            height={item.height ?? 960}
            className="max-h-[70dvh] w-auto max-w-full object-contain"
          />
        </div>
        {item.caption ? <p className="text-center">{item.caption}</p> : null}
        {items.length > 1 ? (
          <div className="flex items-center justify-between">
            <Button type="button" variant="outline" onClick={() => go(-1)} aria-label={t("previous")}>
              <ChevronLeftIcon className="rtl:rotate-180" aria-hidden />
            </Button>
            <span className="text-sm text-muted-foreground" aria-live="polite">
              {t("position", { number: index + 1, total: items.length })}
            </span>
            <Button type="button" variant="outline" onClick={() => go(1)} aria-label={t("next")}>
              <ChevronRightIcon className="rtl:rotate-180" aria-hidden />
            </Button>
          </div>
        ) : null}
      </DialogContent>
    </Dialog>
  );
}

/** Shown instead of photos the API withheld (a tagged child's media consent is not granted right now). The text stays. */
export function WithheldPlaceholder() {
  const t = useTranslations("school.feed.media");
  return (
    <div
      role="note"
      data-testid="media-withheld"
      className="flex items-center gap-3 rounded-xl border border-dashed bg-muted/50 p-4 text-sm text-muted-foreground"
    >
      <EyeOffIcon className="size-5 shrink-0" aria-hidden />
      <p>{t("withheld")}</p>
    </div>
  );
}
