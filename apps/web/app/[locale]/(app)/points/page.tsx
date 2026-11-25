import { getTranslations } from "next-intl/server";
import { StudentPointsPage } from "@/components/points/student-shell";

export async function generateMetadata() {
  const t = await getTranslations("family.points");
  return { title: t("studentTitle") };
}

/** The notification path `/points` (a student's new award): their points timeline and chart. */
export default function PointsPage() {
  return <StudentPointsPage />;
}
