import { getTranslations } from "next-intl/server";
import { AnnouncementsShell } from "@/components/announcements/announcements-shell";
import { InboxView } from "@/components/announcements/inbox-view";

export async function generateMetadata() {
  const t = await getTranslations("family.announcements");
  return { title: t("title") };
}

/** The announcements the school sent to the signed-in person: guardians, students (where the school allows) and staff. */
export default function AnnouncementsPage() {
  return <AnnouncementsShell>{() => <InboxView />}</AnnouncementsShell>;
}
