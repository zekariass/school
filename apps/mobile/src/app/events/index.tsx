import { CalendarScreen } from '@/components/calendar/calendar-screen';

/** The calendar as a screen of its own (students open it from their home; the other roles have a tab). */
export default function EventsIndex() {
  return <CalendarScreen standalone />;
}
