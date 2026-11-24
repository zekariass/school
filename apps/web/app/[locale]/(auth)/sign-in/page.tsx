import { getTranslations } from "next-intl/server";
import { SignInForm } from "./sign-in-form";

export async function generateMetadata() {
  const t = await getTranslations("auth");
  return { title: t("signIn") };
}

export default function SignInPage() {
  return <SignInForm />;
}
