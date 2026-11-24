import { ShieldCheckIcon } from "lucide-react";
import { getTranslations } from "next-intl/server";
import { Card, CardContent } from "@/components/ui/card";

export async function generateMetadata() {
  const t = await getTranslations("platform");
  return { title: t("title") };
}

export default async function PlatformPage() {
  const t = await getTranslations("platform");
  return (
    <Card>
      <CardContent className="flex flex-col items-center gap-3 py-12 text-center">
        <span className="grid size-14 place-items-center rounded-2xl bg-accent text-accent-foreground">
          <ShieldCheckIcon className="size-7" aria-hidden />
        </span>
        <h1 className="text-2xl font-bold">{t("title")}</h1>
        <p className="max-w-md text-muted-foreground">{t("body")}</p>
      </CardContent>
    </Card>
  );
}
