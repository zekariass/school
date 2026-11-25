import { Suspense } from "react";
import { SiteHeader } from "@/components/site-header";
import { Skeleton } from "@/components/ui/skeleton";
import { requireSession } from "@/lib/server/dal";
import { StudentHome } from "./student-points-view";

/**
 * A signed-in student's own points page. The session is read inside Suspense (cacheComponents). Students are
 * recognised by their STUDENT school memberships in `GET /me`; everybody else gets a friendly empty state.
 */
export function StudentPointsPage() {
  return (
    <Suspense fallback={<StudentSkeleton />}>
      <Page />
    </Suspense>
  );
}

async function Page() {
  const me = await requireSession();
  const schools = me.memberships
    .filter((m) => m.type === "STUDENT" && m.studentId)
    .map((m) => ({ schoolId: m.schoolId, schoolName: m.schoolName, studentId: m.studentId! }));
  return (
    <>
      <SiteHeader user={{ name: me.displayName }} />
      <main id="main" className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-6 px-4 py-6">
        <StudentHome schools={schools} name={me.givenName || me.displayName} />
      </main>
    </>
  );
}

function StudentSkeleton() {
  return (
    <div className="flex min-h-dvh flex-col gap-4 p-4" aria-hidden>
      <Skeleton className="h-16 rounded-2xl" />
      <Skeleton className="h-64 rounded-2xl" />
    </div>
  );
}
