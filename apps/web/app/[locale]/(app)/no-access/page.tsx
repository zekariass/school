import { LockKeyholeIcon } from "lucide-react";
import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { SiteHeader } from "@/components/site-header";
import { Card, CardContent } from "@/components/ui/card";
import { requireSession } from "@/lib/server/dal";
import { SignOutButton } from "@/components/sign-out-button";

export async function generateMetadata() {
  const t = await getTranslations("auth");
  return { title: t("noAccessTitle") };
}

/** Signed in, but no role anywhere yet (an administrator has to add them). */
export default function NoAccessPage() {
  return (
    <Suspense fallback={<SiteHeader />}>
      <NoAccess />
    </Suspense>
  );
}

async function NoAccess() {
  const me = await requireSession();
  const t = await getTranslations("auth");
  return (
    <>
      <SiteHeader user={{ name: me.displayName }} />
      <main id="main" className="mx-auto flex w-full max-w-lg flex-1 items-center px-4 py-10">
        <Card className="w-full">
          <CardContent className="flex flex-col items-center gap-4 py-12 text-center">
            <span className="grid size-14 place-items-center rounded-2xl bg-muted text-muted-foreground">
              <LockKeyholeIcon className="size-7" aria-hidden />
            </span>
            <h1 className="text-2xl font-bold">{t("noAccessTitle")}</h1>
            <p className="max-w-sm text-muted-foreground">{t("noAccessBody")}</p>
            <SignOutButton />
          </CardContent>
        </Card>
      </main>
    </>
  );
}
