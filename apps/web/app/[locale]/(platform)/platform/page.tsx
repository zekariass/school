import { ShieldCheckIcon } from "lucide-react";
import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { TestNotificationButton } from "@/components/notifications/test-notification-button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Link } from "@/i18n/navigation";
import { visibleSections } from "@/lib/console/permissions";
import { canSendTestNotification } from "@/lib/notifications/permissions";
import { permissionsOf, requireSession } from "@/lib/server/dal";

export async function generateMetadata() {
  const t = await getTranslations("platform");
  return { title: t("title") };
}

export default function PlatformHomePage() {
  return (
    <div className="flex flex-col gap-6">
      <PageHeader />
      <Suspense fallback={<Skeleton className="h-40 rounded-2xl" />}>
        <Sections />
      </Suspense>
    </div>
  );
}

async function PageHeader() {
  const t = await getTranslations("platform");
  return (
    <div className="flex items-center gap-3">
      <span className="grid size-12 place-items-center rounded-2xl bg-accent text-accent-foreground">
        <ShieldCheckIcon className="size-6" aria-hidden />
      </span>
      <div>
        <h1 className="text-2xl font-bold">{t("title")}</h1>
        <p className="text-muted-foreground">{t("body")}</p>
      </div>
    </div>
  );
}

async function Sections() {
  const me = await requireSession();
  const t = await getTranslations("console");
  const sections = visibleSections(permissionsOf(me));
  return (
    <div className="flex flex-col gap-6">
      <ul className="grid gap-4 sm:grid-cols-2">
        {sections.map((section) => (
          <li key={section.id}>
            <Link href={section.href} className="block h-full rounded-2xl transition-shadow hover:shadow-md">
              <Card className="h-full">
                <CardHeader>
                  <CardTitle>{t(`nav.${section.id}`)}</CardTitle>
                  <CardDescription>{t(`overview.${section.id}`)}</CardDescription>
                </CardHeader>
                <CardContent />
              </Card>
            </Link>
          </li>
        ))}
      </ul>
      {canSendTestNotification(me) ? <TestNotificationButton /> : null}
    </div>
  );
}
