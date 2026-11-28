import { SchoolIcon } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * The header of a feed screen: the class (or school) name, the school below it and a few soft shapes from the theme.
 * The accent is the theme's primary colour, so a brand change is one token edit. The shapes are decoration only.
 */
export function FeedBanner({
  title,
  subtitle,
  icon,
  titleId,
  as: Heading = "h2",
  className,
}: {
  title: string;
  subtitle?: string;
  /** An emoji or short text the class carries (its icon); the school glyph otherwise. */
  icon?: string | null;
  titleId?: string;
  as?: "h1" | "h2";
  className?: string;
}) {
  return (
    <section
      aria-labelledby={titleId}
      className={cn("relative isolate overflow-hidden rounded-2xl bg-primary px-5 py-6 text-primary-foreground shadow-sm sm:px-7 sm:py-8", className)}
      data-testid="feed-banner"
    >
      <span aria-hidden className="pointer-events-none absolute -end-10 -top-16 -z-10 size-52 rounded-full bg-primary-foreground/10" />
      <span aria-hidden className="pointer-events-none absolute -bottom-20 end-24 -z-10 size-40 rounded-full bg-primary-foreground/10" />
      <span aria-hidden className="pointer-events-none absolute start-1/2 top-4 -z-10 size-6 rounded-full bg-primary-foreground/15" />
      <div className="flex items-center gap-4">
        <span aria-hidden className="grid size-14 shrink-0 place-items-center rounded-2xl bg-primary-foreground/15 text-3xl sm:size-16">
          {icon ? <span>{icon}</span> : <SchoolIcon className="size-7" />}
        </span>
        <div className="min-w-0">
          <Heading id={titleId} className="text-2xl leading-tight font-bold break-words sm:text-3xl" data-testid="feed-banner-title">
            {title}
          </Heading>
          {subtitle ? <p className="text-base break-words text-primary-foreground/85">{subtitle}</p> : null}
        </div>
      </div>
    </section>
  );
}
