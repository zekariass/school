import { badgeText } from '@/lib/messaging/derive';
import { useUnreadCount } from '@/lib/messaging/queries';

/** The text of the Messages tab badge ("3", "99+"), or undefined when there is nothing unread. A count only; never a name or a text. */
export function useMessagesBadge(): string | undefined {
  const unread = useUnreadCount();
  return badgeText(unread.data);
}
