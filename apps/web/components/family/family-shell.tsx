import { Suspense } from "react";
import { SiteHeader } from "@/components/site-header";
import { Skeleton } from "@/components/ui/skeleton";
import { requireSession } from "@/lib/server/dal";
import { cn } from "@/lib/utils";
import { FamilyNav } from "./family-nav";

/**
 * Header, tabs and a centered column for the guardian screens (`wide` for the home feed, which has three columns).
 * Reads the session inside Suspense (cacheComponents).
 */
export function FamilyShell({ children, wide = false }: { children: React.ReactNode; wide?: boolean }) {
  return (
    <Suspense fallback={<FamilySkeleton />}>
      <Shell wide={wide}>{children}</Shell>
    </Suspense>
  );
}

async function Shell({ children, wide }: { children: React.ReactNode; wide: boolean }) {
  const me = await requireSession();
  return (
    <>
      <SiteHeader user={{ name: me.displayName }} />
      <main id="main" className={cn("mx-auto flex w-full flex-1 flex-col gap-6 px-4 py-6", wide ? "max-w-7xl" : "max-w-3xl")}>
        <FamilyNav />
        {children}
      </main>
    </>
  );
}

function FamilySkeleton() {
  return (
    <div className="flex min-h-dvh flex-col gap-4 p-4" aria-hidden>
      <Skeleton className="h-16 rounded-2xl" />
      <Skeleton className="h-64 rounded-2xl" />
    </div>
  );
}
