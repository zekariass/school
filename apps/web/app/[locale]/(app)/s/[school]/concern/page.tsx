import { getTranslations } from "next-intl/server";
import { ConcernView } from "@/components/safeguarding/concern-view";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("concern") };
}

export default function ConcernPage() {
  return <ConcernView />;
}
