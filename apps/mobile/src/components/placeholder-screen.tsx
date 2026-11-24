import { useTranslations } from 'use-intl';

import { PushPromptCard } from './push-prompt-card';
import { TabHeader } from './tab-header';
import { EmptyState, Screen } from './ui';

type EmptyKey = 'teacherClasses' | 'guardianHome' | 'studentHome' | 'portfolio' | 'messages' | 'calendar';

/** The home screens: where the notification permission card is offered. */
const HOME_KINDS: readonly EmptyKey[] = ['teacherClasses', 'guardianHome', 'studentHome'];

const ICONS: Record<EmptyKey, { ios: string; android: string }> = {
  teacherClasses: { ios: 'person.3.fill', android: 'groups' },
  guardianHome: { ios: 'house.fill', android: 'home' },
  studentHome: { ios: 'star.fill', android: 'star' },
  portfolio: { ios: 'photo.on.rectangle.angled', android: 'photo_library' },
  messages: { ios: 'bubble.left.and.bubble.right.fill', android: 'forum' },
  calendar: { ios: 'calendar', android: 'calendar_month' },
};

/** A tab screen whose real content arrives in a later milestone. */
export function PlaceholderScreen({ kind }: { kind: EmptyKey }) {
  const t = useTranslations();
  return (
    <Screen>
      <TabHeader />
      {HOME_KINDS.includes(kind) ? <PushPromptCard /> : null}
      <EmptyState icon={ICONS[kind]} title={t(`mobile.empty.${kind}.title`)} body={t(`mobile.empty.${kind}.body`)} />
    </Screen>
  );
}
