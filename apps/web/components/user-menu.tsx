"use client";

import { ChevronDownIcon, LogOutIcon, UserRoundIcon } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { signOut } from "@/lib/sign-out";

/** The signed-in user's name with a sign-out action, for every top bar. */
export function UserMenu({ name, detail }: { name: string; detail?: string }) {
  const t = useTranslations("auth");
  const locale = useLocale();
  const [pending, setPending] = useState(false);

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" className="h-11 max-w-48 gap-2 rounded-xl px-3" aria-label={t("accountMenu")}>
          <UserRoundIcon className="size-5 shrink-0" aria-hidden />
          <span className="hidden truncate sm:inline">{name}</span>
          <ChevronDownIcon className="size-4 shrink-0 opacity-60" aria-hidden />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-56 rounded-xl p-1.5">
        <DropdownMenuLabel className="flex flex-col gap-0.5">
          <span className="truncate font-semibold">{name}</span>
          {detail ? <span className="truncate text-xs font-normal text-muted-foreground">{detail}</span> : null}
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem
          className="min-h-10 gap-2 rounded-lg"
          disabled={pending}
          onSelect={(event) => {
            event.preventDefault();
            setPending(true);
            void signOut(locale);
          }}
        >
          <LogOutIcon className="size-4" aria-hidden />
          {t("signOut")}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
