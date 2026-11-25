import { getTranslations } from "next-intl/server";
import { SchoolFeaturesView } from "@/components/school/school-features";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("features") };
}

export default function FeaturesPage() {
  return <SchoolFeaturesView />;
}
