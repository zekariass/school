import { Suspense } from "react";
import { isGuardianOnly } from "@/components/announcements/announcements-shell";
import { FamilyNav } from "@/components/family/family-nav";
import { SiteHeader } from "@/components/site-header";
import { Skeleton } from "@/components/ui/skeleton";
import { messagingViewer } from "@/lib/messaging/viewer";
import { requireSession } from "@/lib/server/dal";
import { MessagesFrame } from "./messages-frame";

/**
 * Header and a centered column for `/messages`. Reads the session inside Suspense (cacheComponents) and tells the screens what
 * kind of viewer this is (links only: the API authorises everything). Guardians keep their family tabs.
 */
export function MessagesShell({ children }: { children: React.ReactNode }) {
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
        {isGuardianOnly(me) ? <FamilyNav /> : null}
        <MessagesFrame viewer={messagingViewer(me)}>
          <Suspense fallback={<Skeleton className="h-64 rounded-2xl" />}>{children}</Suspense>
        </MessagesFrame>
      </main>
    </>
  );
}
