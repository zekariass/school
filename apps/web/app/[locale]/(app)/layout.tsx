import { Suspense } from "react";
import { userPlaces } from "@school/validation/country";
import { UserPlaceProvider } from "@/components/display-place";
import { RealtimeMount } from "@/components/notifications/realtime-mount";
import { Skeleton } from "@/components/ui/skeleton";
import { requireSession } from "@/lib/server/dal";

/**
 * Everything under (app) needs a valid session. The proxy only checks that a
 * cookie exists; this asks the API (`GET /me`) and sends invalid sessions to
 * sign-in. The check sits inside Suspense because it reads cookies (cacheComponents).
 */
export default function AppLayout({ children }: LayoutProps<"/[locale]">) {
  return (
    <Suspense fallback={<PageSkeleton />}>
      <SessionGate>{children}</SessionGate>
    </Suspense>
  );
}

async function SessionGate({ children }: { children: React.ReactNode }) {
  const me = await requireSession();
  // Zone and calendars of the person and of their schools, from /me (D42): never a constant.
  const { place, schools, preference } = userPlaces(me);
  return (
    <UserPlaceProvider place={place} schools={schools} preference={preference}>
      {/* live notifications for everything signed-in under (app) */}
      <RealtimeMount />
      {children}
    </UserPlaceProvider>
  );
}

function PageSkeleton() {
  return (
    <div className="flex min-h-dvh flex-col gap-4 p-4" aria-hidden>
      <Skeleton className="h-16 rounded-2xl" />
      <Skeleton className="h-64 rounded-2xl" />
    </div>
  );
}
