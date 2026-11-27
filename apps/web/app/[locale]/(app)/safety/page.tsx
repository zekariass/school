import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { SafetyView } from "@/components/moderation/safety-view";
import { SiteHeader } from "@/components/site-header";
import { requireSession } from "@/lib/server/dal";

export async function generateMetadata() {
  const t = await getTranslations("moderation.safety");
  return { title: t("title") };
}

/** The country's safety information (from the API) for anybody signed in, a child included. */
export default function SafetyPage() {
  return (
    <Suspense fallback={<SiteHeader />}>
      <Safety />
    </Suspense>
  );
}

async function Safety() {
  const me = await requireSession();
  return (
    <>
      <SiteHeader user={{ name: me.displayName }} />
      <main id="main" className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-6 px-4 py-8">
        <SafetyView />
      </main>
    </>
  );
}
