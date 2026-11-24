import { SchoolIcon } from "lucide-react";
import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { SiteHeader } from "@/components/site-header";
import { Card, CardContent } from "@/components/ui/card";
import { Link } from "@/i18n/navigation";
import { hasConsoleAccess, schoolChoices } from "@/lib/post-sign-in";
import { requireSession } from "@/lib/server/dal";

export async function generateMetadata() {
  const t = await getTranslations("schoolPicker");
  return { title: t("title") };
}

/** For people who work at several schools: choose a workspace. */
export default function SchoolPickerPage() {
  return (
    <Suspense fallback={<SiteHeader />}>
      <SchoolPicker />
    </Suspense>
  );
}

async function SchoolPicker() {
  const me = await requireSession();
  const t = await getTranslations("schoolPicker");
  const schools = schoolChoices(me);
  return (
    <>
      <SiteHeader user={{ name: me.displayName }} />
      <main id="main" className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-6 px-4 py-10">
        <div>
          <h1 className="text-2xl font-bold">{t("title")}</h1>
          <p className="text-muted-foreground">{t("subtitle")}</p>
        </div>
        {schools.length ? (
          <ul className="flex flex-col gap-3">
            {schools.map((school) => (
              <li key={school.slug}>
                <Link
                  href={`/s/${encodeURIComponent(school.slug)}`}
                  className="flex min-h-16 items-center gap-4 rounded-2xl border bg-card p-4 font-semibold shadow-sm transition-colors hover:bg-accent hover:text-accent-foreground"
                >
                  <span className="grid size-10 place-items-center rounded-xl bg-primary text-primary-foreground">
                    <SchoolIcon className="size-5" aria-hidden />
                  </span>
                  {school.name}
                </Link>
              </li>
            ))}
          </ul>
        ) : (
          <Card>
            <CardContent className="py-10 text-center text-muted-foreground">{t("empty")}</CardContent>
          </Card>
        )}
        {hasConsoleAccess(me) ? (
          <Link href="/platform" className="text-sm font-semibold text-primary underline-offset-4 hover:underline">
            {t("openConsole")}
          </Link>
        ) : null}
      </main>
    </>
  );
}
