import { PaletteIcon, SmartphoneIcon } from "lucide-react";
import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { SignOutButton } from "@/components/sign-out-button";
import { SiteHeader } from "@/components/site-header";
import { StudentHome } from "@/components/points/student-points-view";
import { Card, CardContent } from "@/components/ui/card";
import { Link } from "@/i18n/navigation";
import { requireSession } from "@/lib/server/dal";

export async function generateMetadata() {
  const t = await getTranslations("school.studentApp");
  return { title: t("title") };
}

/** Managed students sign in on the web with their class code (D41) and land here: their home (points, portfolio), not a workspace. */
export default function StudentAppPage() {
  return (
    <Suspense fallback={<SiteHeader />}>
      <StudentApp />
    </Suspense>
  );
}

async function StudentApp() {
  const me = await requireSession();
  const t = await getTranslations("school.studentApp");
  const schools = me.memberships
    .filter((m) => m.type === "STUDENT" && m.studentId)
    .map((m) => ({ schoolId: m.schoolId, schoolName: m.schoolName, studentId: m.studentId! }));
  return (
    <>
      <SiteHeader user={{ name: me.displayName }} />
      <main id="main" className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-8 px-4 py-8">
        <StudentHome schools={schools} name={me.givenName || me.displayName} />
        {schools.length > 0 ? (
          <Link
            href="/portfolio"
            className="flex min-h-14 items-center gap-3 rounded-2xl border bg-card p-4 text-lg font-bold hover:bg-accent/40"
            data-testid="student-portfolio-link"
          >
            <PaletteIcon className="size-6 text-primary" aria-hidden />
            {t("portfolioLink")}
          </Link>
        ) : null}
        <Card className="w-full">
          <CardContent className="flex flex-col items-center gap-4 py-8 text-center">
            <span className="grid size-14 place-items-center rounded-2xl bg-primary/10 text-primary">
              <SmartphoneIcon className="size-7" aria-hidden />
            </span>
            <h1 className="text-2xl font-bold">{t("title")}</h1>
            <p className="max-w-sm text-muted-foreground">{t("body")}</p>
            <SignOutButton />
          </CardContent>
        </Card>
      </main>
    </>
  );
}
