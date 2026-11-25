import { getTranslations } from "next-intl/server";
import { AttendanceView } from "@/components/attendance/attendance-view";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("attendance") };
}

export default function AttendancePage() {
  return <AttendanceView />;
}
