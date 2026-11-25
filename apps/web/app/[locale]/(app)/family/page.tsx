import { getTranslations } from "next-intl/server";
import { FamilyHome } from "@/components/family/family-home";
import { FamilyShell } from "@/components/family/family-shell";

export async function generateMetadata() {
  const t = await getTranslations("family");
  return { title: t("title") };
}

export default function FamilyPage() {
  return (
    <FamilyShell>
      <FamilyHome />
    </FamilyShell>
  );
}
