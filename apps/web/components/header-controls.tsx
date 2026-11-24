import { CalendarToggle } from "@/components/calendar-toggle";
import { LocaleSwitcher } from "@/components/locale-switcher";
import { ThemeToggle } from "@/components/theme-toggle";
import { UserMenu } from "@/components/user-menu";

/** Language, calendar and theme controls shared by every top bar, plus the account menu when signed in. */
export function HeaderControls({ user }: { user?: { name: string; detail?: string } }) {
  return (
    <div className="flex items-center gap-1">
      <LocaleSwitcher />
      <CalendarToggle />
      <ThemeToggle />
      {user ? <UserMenu name={user.name} detail={user.detail} /> : null}
    </div>
  );
}
