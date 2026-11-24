import { InboxIcon } from "lucide-react";
import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("dashboard") };
}

export default function SchoolDashboardPage({ params }: PageProps<"/[locale]/s/[school]">) {
  // `school` is runtime data, so the greeting streams in behind a skeleton
  // while the rest of the dashboard is part of the static shell.
  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6">
      <Suspense fallback={<Skeleton className="h-32 rounded-2xl" />}>
        <Welcome params={params} />
      </Suspense>
      <EmptyState />
    </div>
  );
}

async function Welcome({ params }: { params: PageProps<"/[locale]/s/[school]">["params"] }) {
  const { school } = await params;
  const t = await getTranslations("dashboard");
  return (
    <Card className="bg-primary/10 shadow-none">
      <CardHeader>
        <CardTitle role="heading" aria-level={1} className="text-2xl">
          {t("welcomeTitle")}
        </CardTitle>
        <CardDescription className="text-base">{t("welcomeBody", { school })}</CardDescription>
      </CardHeader>
    </Card>
  );
}

async function EmptyState() {
  const t = await getTranslations("dashboard");
  return (
    <Card>
      <CardContent className="flex flex-col items-center gap-3 py-10 text-center">
        <span className="grid size-14 place-items-center rounded-2xl bg-muted text-muted-foreground">
          <InboxIcon className="size-7" aria-hidden />
        </span>
        <h2 className="text-lg font-semibold">{t("emptyTitle")}</h2>
        <p className="max-w-sm text-muted-foreground">{t("emptyBody")}</p>
      </CardContent>
    </Card>
  );
}
