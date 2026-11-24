import { useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";
import { Link } from "@/i18n/navigation";

export default function LandingPage() {
  const t = useTranslations();
  return (
    <section className="m-auto flex max-w-2xl flex-col items-center gap-6 py-12 text-center">
      <h1 className="text-4xl font-extrabold tracking-tight text-balance sm:text-5xl">
        {t("marketing.headline")}
      </h1>
      <p className="text-lg text-muted-foreground text-balance">
        {t("marketing.valueProposition")}
      </p>
      <div className="flex flex-wrap items-center justify-center gap-3">
        <Button asChild size="lg">
          <Link href="/sign-in">{t("auth.signIn")}</Link>
        </Button>
        <Button asChild size="lg" variant="ghost">
          <Link href="/status">{t("marketing.statusLink")}</Link>
        </Button>
      </div>
    </section>
  );
}
