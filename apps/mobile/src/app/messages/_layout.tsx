import { Stack } from 'expo-router';
import { useTranslations } from 'use-intl';

import { HeaderBack } from '@/components/header-back';
import { useStackHeader } from '@/components/use-stack-header';

/**
 * The messaging screens above the tabs of every role (a notification can open any of them first, so each screen has its own back
 * button): one conversation, its people, starting a conversation or a group, scheduled messages, blocked people and the teachers'
 * group list. Nothing here is persisted.
 */
export default function MessagesStack() {
  const t = useTranslations();
  const header = useStackHeader();
  return (
    <Stack screenOptions={{ ...header, title: '', headerLeft: () => <HeaderBack /> }}>
      <Stack.Screen name="[conversationId]/index" options={{ title: t('mobile.messaging.conversation.title') }} />
      <Stack.Screen name="[conversationId]/people" options={{ title: t('mobile.messaging.people.title') }} />
      <Stack.Screen name="new" options={{ title: t('mobile.messaging.start.title') }} />
      <Stack.Screen name="new-group" options={{ title: t('mobile.messaging.newGroup.title') }} />
      <Stack.Screen name="groups" options={{ title: t('mobile.messaging.groups.title') }} />
      <Stack.Screen name="scheduled" options={{ title: t('mobile.messaging.scheduled.title') }} />
      <Stack.Screen name="blocked" options={{ title: t('mobile.messaging.blocked.title') }} />
    </Stack>
  );
}
