"use client";

import { MediaImage } from "@/components/media/media-image";
import { avatarLook } from "@/lib/school/avatar";
import { cn } from "@/lib/utils";

/**
 * A student's picture: their photo when they have a media avatar, otherwise a friendly
 * generated avatar (emoji and color from `avatarConfig`, else initials on a stable color).
 */
export function StudentAvatar({
  id,
  name,
  mediaId,
  config,
  size = 48,
  className,
}: {
  id: string;
  name: string;
  mediaId?: string | null;
  config?: unknown;
  /** Pixel size (a square). */
  size?: number;
  className?: string;
}) {
  const look = avatarLook(id, name, config);
  const box = { width: size, height: size };
  const fallback = (
    <span
      aria-hidden
      style={{ ...box, background: look.background, color: look.color, fontSize: size * (look.emoji ? 0.5 : 0.38) }}
      className={cn("grid shrink-0 place-items-center rounded-full font-bold select-none", className)}
    >
      {look.emoji ?? look.initials}
    </span>
  );
  if (!mediaId) return fallback;
  return (
    <MediaImage
      mediaId={mediaId}
      variant={size > 96 ? "MEDIUM" : "THUMB"}
      alt=""
      width={size}
      height={size}
      className={cn("shrink-0 rounded-full object-cover", className)}
      fallback={fallback}
    />
  );
}
