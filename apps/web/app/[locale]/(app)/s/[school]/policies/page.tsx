import { getTranslations } from "next-intl/server";
import { PoliciesView } from "@/components/school/policies-view";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("policies") };
}

export default function PoliciesPage() {
  return <PoliciesView />;
}
