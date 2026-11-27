"use client";

import { EyeIcon, StarIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { PortfolioStatus, PortfolioVisibility } from "@/lib/portfolio/queries";
import { statusTone, type StatusTone } from "@/lib/portfolio/view";

const TONE_CLASS: Record<StatusTone, string> = {
  neutral: "",
  pending: "",
  attention: "border-warning/60 bg-warning/15 text-foreground",
  good: "",
  bad: "",
};

const TONE_VARIANT: Record<StatusTone, "outline" | "secondary" | "default" | "destructive"> = {
  neutral: "outline",
  pending: "secondary",
  attention: "outline",
  good: "default",
  bad: "destructive",
};

/** The status of an item, always in words (never colour alone). */
export function StatusBadge({ status, className }: { status: PortfolioStatus; className?: string }) {
  const t = useTranslations("school.portfolio.status");
  const tone = statusTone(status);
  return (
    <Badge variant={TONE_VARIANT[tone]} className={cn(TONE_CLASS[tone], className)} data-testid="portfolio-status" data-status={status}>
      {t(status)}
    </Badge>
  );
}

/** Who can see the item now (the API's effective audience, which the school's cap may have lowered). */
export function VisibilityBadge({ visibility, className }: { visibility: PortfolioVisibility; className?: string }) {
  const t = useTranslations("school.portfolio.visibility");
  return (
    <Badge variant="outline" className={className} data-testid="portfolio-visibility" data-visibility={visibility}>
      <EyeIcon aria-hidden />
      {t(visibility)}
    </Badge>
  );
}

export function FeaturedBadge() {
  const t = useTranslations("school.portfolio.card");
  return (
    <Badge variant="secondary" data-testid="portfolio-featured">
      <StarIcon aria-hidden />
      {t("featured")}
    </Badge>
  );
}
