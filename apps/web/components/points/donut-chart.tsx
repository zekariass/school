"use client";

import { useTranslations } from "next-intl";
import { arcDash, donutSlices, type CategoryTotalLike } from "@/lib/points/donut";

const RADIUS = 42;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;

/**
 * Donut of positive points by category. The ring is decoration for sighted users (`role="img"` with a summary);
 * the table beside it carries the same numbers in text, so color is never the only way to read it.
 */
export function PointsDonut({ categories, total }: { categories: readonly CategoryTotalLike[]; total: number }) {
  const t = useTranslations("school.points.chart");
  const slices = donutSlices(categories);
  const summary =
    slices.length === 0
      ? t("empty")
      : t("summary", { parts: slices.map((s) => `${s.name} ${s.points}`).join(", ") });
  return (
    <svg viewBox="0 0 100 100" role="img" aria-label={summary} className="size-40 shrink-0 -rotate-90" data-testid="points-donut">
      <circle cx="50" cy="50" r={RADIUS} fill="none" strokeWidth="14" className="stroke-muted" />
      {slices.map((slice) => {
        const dash = arcDash(slice, CIRCUMFERENCE);
        return (
          <circle
            key={slice.categoryId}
            cx="50"
            cy="50"
            r={RADIUS}
            fill="none"
            strokeWidth="14"
            stroke={slice.color}
            strokeDasharray={dash.dasharray}
            strokeDashoffset={dash.dashoffset}
          />
        );
      })}
      <text x="50" y="50" textAnchor="middle" dominantBaseline="central" transform="rotate(90 50 50)" className="fill-foreground text-[18px] font-extrabold">
        {total}
      </text>
    </svg>
  );
}
