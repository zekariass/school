"use client";

import { useQueryClient } from "@tanstack/react-query";
import { ImageOffIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useRef } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";
import { useMediaUrl } from "@/lib/media/queries";
import { mediaUrlKey, type MediaVariantName } from "@/lib/media/url-cache";

interface MediaImageProps {
  mediaId: string | null | undefined;
  variant: MediaVariantName;
  alt: string;
  width: number;
  height: number;
  className?: string;
  /** Shown when there is no media (or it cannot be loaded). */
  fallback?: React.ReactNode;
}

/**
 * An image served through a short-lived signed URL (`GET /media/{id}/url`). A plain `<img>`:
 * `next/image` would try to optimize (and cache) a link that expires in minutes. When the
 * browser fails to load it (the link expired), the link is fetched again once.
 */
export function MediaImage({ mediaId, variant, alt, width, height, className, fallback }: MediaImageProps) {
  const t = useTranslations("media.errors");
  const queryClient = useQueryClient();
  const url = useMediaUrl(mediaId, variant);
  const retried = useRef(false);
  const box = { width, height };

  if (!mediaId) return <>{fallback ?? null}</>;
  if (url.isError) {
    return (
      fallback ?? (
        <span role="img" aria-label={t("unavailable")} style={box} className={cn("grid place-items-center bg-muted text-muted-foreground", className)}>
          <ImageOffIcon className="size-1/3" aria-hidden />
        </span>
      )
    );
  }
  if (!url.data) return <Skeleton style={box} className={className} aria-hidden />;

  return (
    // eslint-disable-next-line @next/next/no-img-element -- signed, expiring URL: not for next/image
    <img
      src={url.data.url}
      alt={alt}
      width={width}
      height={height}
      className={className}
      loading="lazy"
      decoding="async"
      onError={() => {
        if (retried.current) return;
        retried.current = true;
        void queryClient.invalidateQueries({ queryKey: mediaUrlKey(mediaId, variant) });
      }}
    />
  );
}
