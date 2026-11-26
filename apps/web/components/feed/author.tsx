"use client";

import { GraduationCapIcon, HeartIcon, SchoolIcon, SmileIcon, type LucideIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { MediaImage } from "@/components/media/media-image";
import type { FeedAuthor } from "@/lib/feed/queries";
import { authorLabel } from "@/lib/feed/view";
import { cn } from "@/lib/utils";

const ROLE_ICON: Record<FeedAuthor["role"], LucideIcon> = {
  TEACHER: GraduationCapIcon,
  SCHOOL_ADMIN: SchoolIcon,
  GUARDIAN: HeartIcon,
  STUDENT: SmileIcon,
};

/** The author's name when the API sent one; otherwise only the role ("A family", "A student"): never a guess. */
export function AuthorName({ author, className }: { author: FeedAuthor; className?: string }) {
  const t = useTranslations("school.feed.roles");
  const label = authorLabel(author);
  return (
    <span className={cn("min-w-0 truncate", className)} data-testid="post-author">
      {label.kind === "name" ? label.name : t(label.role)}
    </span>
  );
}

export function AuthorAvatar({ author, size = 40 }: { author: FeedAuthor; size?: number }) {
  const Icon = ROLE_ICON[author.role];
  const fallback = (
    <span aria-hidden style={{ width: size, height: size }} className="grid shrink-0 place-items-center rounded-full bg-primary/10 text-primary">
      <Icon style={{ width: size * 0.5, height: size * 0.5 }} />
    </span>
  );
  if (!author.avatar?.id) return fallback;
  return (
    <MediaImage
      mediaId={author.avatar.id}
      variant="THUMB"
      alt=""
      width={size}
      height={size}
      className="shrink-0 rounded-full object-cover"
      fallback={fallback}
    />
  );
}
