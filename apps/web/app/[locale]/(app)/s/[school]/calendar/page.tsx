import { getTranslations } from "next-intl/server";
import { SchoolCalendarView } from "@/components/calendar/calendar-page";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("calendar") };
}

export default function SchoolCalendarPage() {
  return <SchoolCalendarView />;
}
