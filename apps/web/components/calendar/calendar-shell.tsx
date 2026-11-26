import { Suspense } from "react";
import { isGuardianOnly } from "@/components/announcements/announcements-shell";
import { FamilyNav } from "@/components/family/family-nav";
import { SiteHeader } from "@/components/site-header";
import { Skeleton } from "@/components/ui/skeleton";
import { requireSession, type Me } from "@/lib/server/dal";

/**
 * Header and a wide centered column for the calendar and for an event opened from a notification. Reads the session
 * inside Suspense (cacheComponents) and hands it to `children`, a function of the signed-in user.
 */
export function CalendarShell({ children }: { children: (me: Me) => React.ReactNode }) {
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
      <main id="main" className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-6 px-4 py-6">
        {isGuardianOnly(me) ? <FamilyNav /> : null}
        <Suspense fallback={<Skeleton className="h-96 rounded-2xl" />}>{children(me)}</Suspense>
      </main>
    </>
  );
}
