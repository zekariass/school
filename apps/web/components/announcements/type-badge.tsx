"use client";

import { InfoIcon, ShieldAlertIcon, SirenIcon, StarIcon, type LucideIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import type { AnnouncementType } from "@/lib/announcements/view";
import { cn } from "@/lib/utils";

const ICONS: Record<AnnouncementType, LucideIcon> = {
  NORMAL: InfoIcon,
  IMPORTANT: StarIcon,
  URGENT: SirenIcon,
  SAFETY: ShieldAlertIcon,
};

const STYLES: Record<AnnouncementType, string> = {
  NORMAL: "bg-muted text-muted-foreground",
  IMPORTANT: "bg-warning/20 text-foreground",
  URGENT: "bg-destructive text-white",
  SAFETY: "bg-destructive text-white",
};

/**
 * The type of an announcement as an icon and a word, so it never depends on colour alone. Urgent and safety are the
 * strong ones; a normal announcement shows nothing unless `showNormal` is set.
 */
export function TypeBadge({ type, showNormal = false, className }: { type: AnnouncementType; showNormal?: boolean; className?: string }) {
  const t = useTranslations("school.announcements.types");
  if (type === "NORMAL" && !showNormal) return null;
  const Icon = ICONS[type];
  return (
    <span className={cn("inline-flex min-h-6 items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-bold", STYLES[type], className)} data-testid={`type-${type}`}>
      <Icon className="size-3.5" aria-hidden />
      {t(type)}
    </span>
  );
}
