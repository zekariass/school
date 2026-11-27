"use client";

import { EyeOffIcon, FileTextIcon, MessageCircleIcon, TypeIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useFormatDate } from "@/components/calendar-preference";
import { MediaImage } from "@/components/media/media-image";
import { StudentAvatar } from "@/components/school/student-avatar";
import { Link } from "@/i18n/navigation";
import type { PortfolioItem } from "@/lib/portfolio/queries";
import { coverFile } from "@/lib/portfolio/view";
import { FeaturedBadge, StatusBadge, VisibilityBadge } from "./badges";

/**
 * One item in a list: a cover picture (the small rendition, lazy), the title, the status and who can see it. What it
 * shows is what the API sent: when the files are withheld there is no picture and the card says so.
 */
export function ItemCard({ item, href, showStudent = false }: { item: PortfolioItem; href: string; showStudent?: boolean }) {
  const t = useTranslations("school.portfolio.card");
  const tt = useTranslations("school.portfolio.types");
  const formatDate = useFormatDate();
  // The API sends no files when it withholds them (`media` is empty); the student and the reviewers still get them, with the flag.
  const cover = coverFile(item.media);
  const name = item.student.displayName;
  const title = item.title?.trim() || t("untitled");
  return (
    <li>
      <Link
        href={href}
        className="flex h-full gap-3 rounded-2xl border bg-card p-3 transition-colors hover:bg-accent/40 focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
        data-testid="portfolio-card"
        data-item-id={item.id}
        data-status={item.status}
      >
        <span className="grid size-24 shrink-0 place-items-center overflow-hidden rounded-xl bg-muted text-muted-foreground sm:size-28">
          {cover ? (
            <MediaImage mediaId={cover.mediaId} variant="THUMB" alt="" width={112} height={112} className="size-full object-cover" />
          ) : item.mediaWithheld ? (
            <span className="flex flex-col items-center gap-1 p-2 text-center text-xs font-medium">
              <EyeOffIcon className="size-6" aria-hidden />
              {t("withheldShort")}
            </span>
          ) : item.itemType === "TEXT" ? (
            <TypeIcon className="size-8" aria-hidden />
          ) : (
            <FileTextIcon className="size-8" aria-hidden />
          )}
        </span>
        <span className="flex min-w-0 flex-1 flex-col gap-1.5">
          <span className="line-clamp-2 text-base font-bold break-words">{title}</span>
          {showStudent && name ? (
            <span className="flex items-center gap-2 text-sm text-muted-foreground">
              <StudentAvatar id={item.student.studentId} name={name} mediaId={item.student.avatar?.id} config={item.student.avatarConfig} size={20} />
              <span className="min-w-0 truncate">{name}</span>
            </span>
          ) : null}
          <span className="flex flex-wrap items-center gap-1.5">
            <StatusBadge status={item.status} />
            <VisibilityBadge visibility={item.effectiveVisibility} />
            {item.featured ? <FeaturedBadge /> : null}
          </span>
          <span className="flex flex-wrap items-center gap-x-3 text-xs text-muted-foreground">
            <span>{tt(item.itemType)}</span>
            {item.workDate ? <span>{formatDate(item.workDate)}</span> : null}
            {item.commentCount ? (
              <span className="inline-flex items-center gap-1">
                <MessageCircleIcon className="size-3.5" aria-hidden />
                {t("comments", { count: item.commentCount })}
              </span>
            ) : null}
          </span>
          {item.status === "CHANGES_REQUESTED" ? <span className="text-sm font-medium">{t("changesAsked")}</span> : null}
          {item.mediaWithheld ? <span className="text-sm text-muted-foreground">{cover ? t("withheldOthers") : t("withheld")}</span> : null}
        </span>
      </Link>
    </li>
  );
}
