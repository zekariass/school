import { Suspense } from "react";
import { SiteHeader } from "@/components/site-header";
import { Skeleton } from "@/components/ui/skeleton";
import { requireSession } from "@/lib/server/dal";
import { FamilyNav } from "./family-nav";

/** Header, tabs and a centered column for the guardian screens. Reads the session inside Suspense (cacheComponents). */
export function FamilyShell({ children }: { children: React.ReactNode }) {
  return (
    <Suspense fallback={<FamilySkeleton />}>
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
