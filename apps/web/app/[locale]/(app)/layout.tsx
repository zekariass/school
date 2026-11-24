import { Suspense } from "react";
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
  await requireSession();
  return children;
}

function PageSkeleton() {
  return (
    <div className="flex min-h-dvh flex-col gap-4 p-4" aria-hidden>
      <Skeleton className="h-16 rounded-2xl" />
      <Skeleton className="h-64 rounded-2xl" />
    </div>
  );
}
