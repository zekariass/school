import { Suspense } from "react";
import { FamilyNav } from "@/components/family/family-nav";
import { SiteHeader } from "@/components/site-header";
import { Skeleton } from "@/components/ui/skeleton";
import { requireSession, type Me } from "@/lib/server/dal";

/** A guardian: only guardian memberships (or none) and no grant anywhere. They get the family tabs; staff and administrators do not. */
export function isGuardianOnly(me: Pick<Me, "memberships" | "scopes">): boolean {
  return me.memberships.every((m) => m.type === "GUARDIAN") && me.scopes.length === 0;
}

/**
 * Header and a centered column for the announcement inbox and for an announcement opened from a notification.
 * Reads the session inside Suspense (cacheComponents) and hands it to `children`, a function of the signed-in user.
 */
export function AnnouncementsShell({ children }: { children: (me: Me) => React.ReactNode }) {
  return (
    <Suspense fallback={<SiteHeader />}>
      <Shell>{children}</Shell>
    </Suspense>
  );
}

async function Shell({ children }: { children: (me: Me) => React.ReactNode }) {
  const me = await requireSession();
  return (
    <>
      <SiteHeader user={{ name: me.displayName }} />
      <main id="main" className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-6 px-4 py-6">
        {isGuardianOnly(me) ? <FamilyNav /> : null}
        <Suspense fallback={<Skeleton className="h-64 rounded-2xl" />}>{children(me)}</Suspense>
      </main>
    </>
  );
}
