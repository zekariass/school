import { Suspense } from "react";
import { SiteHeader } from "@/components/site-header";
import { Skeleton } from "@/components/ui/skeleton";
import { requireSession } from "@/lib/server/dal";

/** Header and a centered column for a post or story opened from a notification. Reads the session inside Suspense (cacheComponents). */
export function LinkedShell({ children }: { children: React.ReactNode }) {
  return (
    <Suspense fallback={<SiteHeader />}>
      <Shell>{children}</Shell>
    </Suspense>
  );
}

async function Shell({ children }: { children: React.ReactNode }) {
  const me = await requireSession();
  return (
    <>
      <SiteHeader user={{ name: me.displayName }} />
      <main id="main" className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-6 px-4 py-6">
        <Suspense fallback={<Skeleton className="h-64 rounded-2xl" />}>{children}</Suspense>
      </main>
    </>
  );
}
