"use client";

import { LogOutIcon } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { signOut } from "@/lib/sign-out";

export function SignOutButton() {
  const t = useTranslations("auth");
  const locale = useLocale();
  const [pending, setPending] = useState(false);
  return (
    <Button
      size="lg"
      variant="outline"
      disabled={pending}
      onClick={() => {
        setPending(true);
        void signOut(locale);
      }}
    >
      <LogOutIcon className="size-4" aria-hidden />
      {t("signOut")}
    </Button>
  );
}
