import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { PreferencesView } from "@/components/notifications/preferences-view";
import { SiteHeader } from "@/components/site-header";
import { Skeleton } from "@/components/ui/skeleton";
import { requireSession } from "@/lib/server/dal";

export async function generateMetadata() {
  const t = await getTranslations("notifications");
  return { title: t("preferences.title") };
}

/** Which notifications reach the user, and quiet hours. */
export default function NotificationPreferencesPage() {
  return (
    <Suspense fallback={<SiteHeader />}>
      <Preferences />
    </Suspense>
  );
}

async function Preferences() {
  const me = await requireSession();
  return (
    <>
      <SiteHeader user={{ name: me.displayName }} />
      <main id="main" className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-6 px-4 py-10">
        <Suspense fallback={<Skeleton className="h-64 rounded-2xl" />}>
          <PreferencesView />
        </Suspense>
      </main>
    </>
  );
}
