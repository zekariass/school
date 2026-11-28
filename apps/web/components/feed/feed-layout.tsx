import { cn } from "@/lib/utils";

/**
 * The columns of a feed screen: an optional left column (the children list), the wide centre column and the right rail.
 * Three columns from `xl`; from `lg` the rail drops under the centre column (the left column stays); below that
 * everything stacks (the left column becomes a horizontal switcher, the rail follows the feed).
 */
export function FeedColumns({
  left,
  rail,
  railLabel,
  children,
}: {
  left?: React.ReactNode;
  rail?: React.ReactNode;
  railLabel?: string;
  children: React.ReactNode;
}) {
  return (
    <div
      className={cn(
        "grid grid-cols-1 gap-x-6 gap-y-4",
        left ? "lg:grid-cols-[15rem_minmax(0,1fr)] xl:grid-cols-[15rem_minmax(0,1fr)_20rem]" : "xl:grid-cols-[minmax(0,1fr)_20rem]",
      )}
      data-testid="feed-columns"
    >
      {left ? <div className="lg:sticky lg:top-4 lg:row-span-2 lg:self-start xl:row-span-1">{left}</div> : null}
      <div className={cn("flex min-w-0 flex-col gap-4", left && "lg:col-start-2", "xl:row-start-1", left ? "xl:col-start-2" : "xl:col-start-1")}>{children}</div>
      {rail ? (
        <aside
          aria-label={railLabel}
          className={cn("flex min-w-0 flex-col gap-4", left && "lg:col-start-2", left ? "xl:col-start-3" : "xl:col-start-2", "xl:row-start-1 xl:self-start")}
          data-testid="feed-rail"
        >
          {rail}
        </aside>
      ) : null}
    </div>
  );
}
