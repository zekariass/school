import { CalendarScreen } from '@/components/calendar/calendar-screen';
import { PlaceholderScreen } from '@/components/placeholder-screen';
import { useSession } from '@/lib/session/session-provider';

/** The developer role preview has no account behind it, so it keeps the placeholder. */
export default function Screen() {
  const { preview } = useSession();
  return preview ? <PlaceholderScreen kind="calendar" /> : <CalendarScreen />;
}
