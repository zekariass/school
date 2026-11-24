import { CalendarToggle } from "@/components/calendar-toggle";
import { LocaleSwitcher } from "@/components/locale-switcher";
import { ThemeToggle } from "@/components/theme-toggle";

/** Language, calendar and theme controls shared by every top bar. */
export function HeaderControls() {
  return (
    <div className="flex items-center gap-1">
      <LocaleSwitcher />
      <CalendarToggle />
      <ThemeToggle />
    </div>
  );
}
