"use client";

import { MonitorIcon, MoonIcon, SunIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useTheme } from "@/components/theme-provider";
import { isThemePreference } from "@/lib/theme";

export function ThemeToggle() {
  const t = useTranslations("theme");
  const { theme, setTheme } = useTheme();

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" aria-label={t("label")} className="size-11 rounded-xl">
          <SunIcon className="size-5 dark:hidden" aria-hidden />
          <MoonIcon className="hidden size-5 dark:block" aria-hidden />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-40 rounded-xl p-1.5">
        <DropdownMenuRadioGroup
          value={theme}
          onValueChange={(value) => isThemePreference(value) && setTheme(value)}
        >
          <DropdownMenuRadioItem value="light" className="min-h-10 rounded-lg">
            <SunIcon aria-hidden /> {t("light")}
          </DropdownMenuRadioItem>
          <DropdownMenuRadioItem value="dark" className="min-h-10 rounded-lg">
            <MoonIcon aria-hidden /> {t("dark")}
          </DropdownMenuRadioItem>
          <DropdownMenuRadioItem value="system" className="min-h-10 rounded-lg">
            <MonitorIcon aria-hidden /> {t("system")}
          </DropdownMenuRadioItem>
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
