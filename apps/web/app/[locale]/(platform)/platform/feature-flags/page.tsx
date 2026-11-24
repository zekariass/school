import { getTranslations } from "next-intl/server";
import { FeatureFlagsView } from "@/components/console/feature-flags-view";

export async function generateMetadata() {
  const t = await getTranslations("console.nav");
  return { title: t("featureFlags") };
}

export default function FeatureFlagsPage() {
  return <FeatureFlagsView />;
}
