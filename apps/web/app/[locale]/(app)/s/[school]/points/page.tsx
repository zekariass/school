import { getTranslations } from "next-intl/server";
import { PointsView } from "@/components/points/points-view";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("points") };
}

export default function PointsPage() {
  return <PointsView />;
}
