import { getTranslations } from "next-intl/server";
import { StudentCodeForm } from "@/components/auth/student-code-form";

export async function generateMetadata() {
  const t = await getTranslations("auth.studentCode");
  return { title: t("title") };
}

/**
 * Class-code sign-in for managed students (D41): the QR code or the text code their teacher gave them. Public like the
 * adult sign-in; the code is read in the browser, sent once to `/api/auth/login-code` and never put in the address.
 */
export default function StudentCodePage() {
  return <StudentCodeForm />;
}
