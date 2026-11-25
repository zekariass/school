import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { CodeEntry } from "@/components/invite/code-entry";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("family.connect");
  return { title: t("title"), robots: { index: false, follow: false } };
}

/** Public: connect with a printed parent code before having an account. The code is typed, never in the URL. */
export default function ConnectPage() {
  return <CodeEntry />;
}
