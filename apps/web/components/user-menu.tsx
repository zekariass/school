"use client";

import { CalendarClockIcon, ChevronDownIcon, LifeBuoyIcon, LogOutIcon, UserRoundIcon, UserRoundPenIcon } from "lucide-react";
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
import { Link } from "@/i18n/navigation";
import { signOut } from "@/lib/sign-out";

/** The signed-in user's name with a sign-out action, for every top bar. */
export function UserMenu({ name, detail }: { name: string; detail?: string }) {
  const t = useTranslations("auth");
  const tAccount = useTranslations("account");
  const tNav = useTranslations("nav");
  const locale = useLocale();
  const [pending, setPending] = useState(false);

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" className="h-11 max-w-48 gap-2 rounded-xl px-2.5 sm:px-3" aria-label={t("accountMenu")}>
          <UserRoundIcon className="size-5 shrink-0" aria-hidden />
          <span className="hidden truncate sm:inline">{name}</span>
          <ChevronDownIcon className="hidden size-4 shrink-0 opacity-60 sm:block" aria-hidden />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-56 rounded-xl p-1.5">
        <DropdownMenuLabel className="flex flex-col gap-0.5">
          <span className="truncate font-semibold">{name}</span>
          {detail ? <span className="truncate text-xs font-normal text-muted-foreground">{detail}</span> : null}
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem asChild className="min-h-10 gap-2 rounded-lg">
          <Link href="/calendar" data-testid="calendar-link">
            <CalendarClockIcon className="size-4" aria-hidden />
            {tNav("calendar")}
          </Link>
        </DropdownMenuItem>
        <DropdownMenuItem asChild className="min-h-10 gap-2 rounded-lg">
          <Link href="/account">
            <UserRoundPenIcon className="size-4" aria-hidden />
            {tAccount("link")}
          </Link>
        </DropdownMenuItem>
        <DropdownMenuItem asChild className="min-h-10 gap-2 rounded-lg">
          <Link href="/safety" data-testid="safety-link">
            <LifeBuoyIcon className="size-4" aria-hidden />
            {tNav("safety")}
          </Link>
        </DropdownMenuItem>
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
