import { View } from 'react-native';

import { useSession } from '@/lib/session/session-provider';

import { AnnouncementsButton } from './announcements/announcements-button';
import { NotificationBell } from './notification-bell';

/**
 * The top row of the tab screens (the tab groups have no navigation header): the announcements button and the notification
 * bell at the end. Hidden in the developer preview, which has no account behind it.
 */
export function TabHeader() {
  const { preview } = useSession();
  if (preview) return null;
  return (
    <View style={{ flexDirection: 'row', justifyContent: 'flex-end', marginBottom: -8 }}>
      <AnnouncementsButton />
      <NotificationBell />
    </View>
  );
}
