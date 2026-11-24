import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { NotificationsView } from "@/components/notifications/notifications-view";
import { SiteHeader } from "@/components/site-header";
import { Skeleton } from "@/components/ui/skeleton";
import { requireSession } from "@/lib/server/dal";

export async function generateMetadata() {
  const t = await getTranslations("notifications");
  return { title: t("title") };
}

/** The signed-in user's notification inbox. */
export default function NotificationsPage() {
  return (
    <Suspense fallback={<SiteHeader />}>
      <Inbox />
    </Suspense>
  );
}

async function Inbox() {
  const me = await requireSession();
  return (
    <>
      <SiteHeader user={{ name: me.displayName }} />
      <main id="main" className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-6 px-4 py-10">
        <Suspense fallback={<Skeleton className="h-64 rounded-2xl" />}>
          <NotificationsView />
        </Suspense>
      </main>
    </>
  );
}
