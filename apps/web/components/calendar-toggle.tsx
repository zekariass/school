"use client";

import { CalendarDaysIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useCalendarPreference } from "@/components/calendar-preference";
import { isCalendarPreference } from "@/lib/format-instant";

export function CalendarToggle() {
  const t = useTranslations("calendar");
  const { calendar, setCalendar } = useCalendarPreference();

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" aria-label={t("label")} className="size-11 rounded-xl">
          <CalendarDaysIcon className="size-5" aria-hidden />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-44 rounded-xl p-1.5">
        <DropdownMenuRadioGroup
          value={calendar}
          onValueChange={(value) => isCalendarPreference(value) && setCalendar(value)}
        >
          <DropdownMenuRadioItem value="gregorian" className="min-h-10 rounded-lg">
            {t("gregorian")}
          </DropdownMenuRadioItem>
          <DropdownMenuRadioItem value="ethiopian" className="min-h-10 rounded-lg">
            {t("ethiopian")}
          </DropdownMenuRadioItem>
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
