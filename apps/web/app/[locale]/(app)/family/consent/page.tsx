import { getTranslations } from "next-intl/server";
import { ConsentView } from "@/components/family/consent-view";
import { FamilyShell } from "@/components/family/family-shell";

export async function generateMetadata() {
  const t = await getTranslations("consent");
  return { title: t("title") };
}

export default function FamilyConsentPage() {
  return (
    <FamilyShell>
      <ConsentView />
    </FamilyShell>
  );
}
