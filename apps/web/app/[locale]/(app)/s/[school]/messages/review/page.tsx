import { getTranslations } from "next-intl/server";
import { ReviewView } from "@/components/messaging/review-view";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("messageReview") };
}

export default function ReviewPage() {
  return <ReviewView />;
}
