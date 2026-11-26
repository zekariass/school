import { Image } from 'expo-image';
import { randomUUID } from 'expo-crypto';
import { SymbolView } from 'expo-symbols';
import { useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, TextInput, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { problemOf } from '@/lib/api-error';
import { sendable } from '@/lib/messaging/derive';
import { scheduleMessage } from '@/lib/messaging/requests';
import { defaultPick, toInstant, scheduleProblem, usableZone, type SchedulePick } from '@/lib/feed/schedule';
import { formatInstant } from '@/lib/calendar/format-instant';
import { useClock } from '@/lib/feed/use-clock';
import { usePreferences } from '@/lib/preferences/preferences-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { SchedulePicker } from '../feed/schedule-picker';
import { Button, Sheet, Text } from '../ui';

import { useMessagePhotos } from './use-message-photos';

export interface ComposerProps {
  conversationId: string;
  maxLength: number;
  /** Photos may be attached (students only when the school allows it: `capabilities.studentMediaEnabled`). */
  canAttach: boolean;
  /** Adults only: a message can be scheduled in the school's time zone (`capabilities.timeZone`). */
  schedule: { timeZone: string } | null;
  reply: { id: string; author: string } | null;
  onCancelReply: () => void;
  /** Hands the message to the outbox. Returns when it was queued (the text is then cleared). */
  onSend: (input: { body: string; mediaIds: string[]; replyToMessageId: string | undefined }) => void;
  onScheduled: (when: string) => void;
}

/**
 * The composer: plain text, up to the school's limit, up to four photos (converted on the device, uploaded for this conversation),
 * a reply, and for adults a scheduled send picked in the SCHOOL's time zone. The draft lives only in this component's state; nothing
 * is saved if the screen closes.
 */
export function Composer({ conversationId, maxLength, canAttach, schedule, reply, onCancelReply, onSend, onScheduled }: ComposerProps) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  const [text, setText] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const [attachMenu, setAttachMenu] = useState(false);
  const [scheduling, setScheduling] = useState(false);
  const photos = useMessagePhotos(conversationId);

  const failedPhoto = photos.photos.some((photo) => photo.status === 'failed');
  const canSend = sendable(text, photos.ready.length, maxLength) && !photos.busy && !failedPhoto;
  const nearLimit = text.length > maxLength * 0.85;

  function send() {
    if (!canSend) return;
    onSend({ body: text.trim(), mediaIds: photos.ready, replyToMessageId: reply?.id });
    photos.adopt(photos.ready);
    setText('');
    setNotice(null);
  }

  async function pick(source: 'camera' | 'library') {
    setAttachMenu(false);
    setNotice(await photos.add(source));
  }

  return (
    <View style={{ borderTopWidth: 1, borderTopColor: colors.border, backgroundColor: colors.background, padding: spacing[3], gap: spacing[2] }}>
      {reply ? (
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[2], padding: spacing[2], borderRadius: radii.lg, backgroundColor: colors.muted }}>
          <Text variant="caption" numberOfLines={1} style={{ flex: 1 }}>
            {t('mobile.messaging.composer.replyingTo', { name: reply.author })}
          </Text>
          <Pressable accessibilityRole="button" accessibilityLabel={t('mobile.messaging.composer.cancelReply')} onPress={onCancelReply} style={{ width: minTouchTarget, height: 36, alignItems: 'center', justifyContent: 'center' }}>
            <SymbolView name={{ ios: 'xmark', android: 'close', web: 'close' }} size={16} tintColor={colors.mutedForeground} />
          </Pressable>
        </View>
      ) : null}

      {photos.photos.length > 0 ? (
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: spacing[2] }}>
          {photos.photos.map((photo, index) => (
            <View key={photo.key} style={{ width: 76, gap: 4 }}>
              <View style={{ width: 76, height: 76, borderRadius: radii.lg, overflow: 'hidden', backgroundColor: colors.muted }}>
                <Image source={{ uri: photo.uri }} cachePolicy="memory" contentFit="cover" accessibilityLabel={t('mobile.messaging.message.photoLabel', { n: index + 1, total: photos.photos.length })} style={{ width: 76, height: 76, opacity: photo.status === 'ready' ? 1 : 0.5 }} />
                {photo.status === 'uploading' ? (
                  <View style={{ position: 'absolute', inset: 0, alignItems: 'center', justifyContent: 'center' }}>
                    <ActivityIndicator color={colors.primary} />
                  </View>
                ) : null}
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel={t('mobile.messaging.composer.removePhoto')}
                  onPress={() => photos.remove(photo)}
                  hitSlop={8}
                  style={{ position: 'absolute', top: 2, end: 2, width: 28, height: 28, borderRadius: 14, backgroundColor: colors.card, alignItems: 'center', justifyContent: 'center' }}>
                  <SymbolView name={{ ios: 'xmark', android: 'close', web: 'close' }} size={12} tintColor={colors.foreground} />
                </Pressable>
              </View>
              {photo.status === 'failed' ? (
                <Pressable accessibilityRole="button" accessibilityLabel={photo.message ?? t('mobile.messaging.composer.photoFailed')} onPress={() => photos.retry(photo.key)}>
                  <Text variant="caption" tone="destructive" numberOfLines={2}>
                    {t('mobile.messaging.composer.photoRetry')}
                  </Text>
                </Pressable>
              ) : null}
            </View>
          ))}
        </ScrollView>
      ) : null}
      {photos.photos.find((photo) => photo.status === 'failed')?.message ? (
        <Text variant="caption" tone="destructive" accessibilityLiveRegion="polite">
          {photos.photos.find((photo) => photo.status === 'failed')?.message}
        </Text>
      ) : null}
      {notice ? (
        <Text variant="caption" tone="destructive" accessibilityLiveRegion="polite">
          {notice}
        </Text>
      ) : null}

      <View style={{ flexDirection: 'row', alignItems: 'flex-end', gap: spacing[2] }}>
        {canAttach && photos.canAdd ? (
          <RoundButton label={t('mobile.messaging.composer.addPhoto')} ios="photo" android="image" onPress={() => setAttachMenu(true)} />
        ) : null}
        <TextInput
          accessibilityLabel={t('mobile.messaging.composer.placeholder')}
          placeholder={t('mobile.messaging.composer.placeholder')}
          placeholderTextColor={colors.mutedForeground}
          selectionColor={colors.primary}
          value={text}
          onChangeText={setText}
          multiline
          // Plain text only: no autocorrect-driven rich content; the server cleans the text again.
          autoCapitalize="sentences"
          style={{
            flex: 1,
            minHeight: minTouchTarget + 4,
            maxHeight: 132,
            color: colors.foreground,
            backgroundColor: colors.card,
            borderColor: colors.input,
            borderWidth: 1,
            borderRadius: radii.xl,
            paddingHorizontal: spacing[3],
            paddingTop: 12,
            paddingBottom: 12,
            fontSize: 16,
          }}
        />
        {schedule ? <RoundButton label={t('mobile.messaging.composer.schedule')} ios="clock" android="schedule" disabled={!sendable(text, photos.ready.length, maxLength) || photos.busy || failedPhoto} onPress={() => setScheduling(true)} /> : null}
        <RoundButton label={t('mobile.messaging.composer.send')} ios="arrow.up" android="send" primary disabled={!canSend} onPress={send} />
      </View>
      {nearLimit ? (
        <Text variant="caption" tone={text.length > maxLength ? 'destructive' : 'muted'} style={{ textAlign: 'right' }} accessibilityLiveRegion="polite">
          {t('mobile.messaging.composer.count', { count: text.length, max: maxLength })}
        </Text>
      ) : null}

      <Sheet visible={attachMenu} onClose={() => setAttachMenu(false)} title={t('mobile.messaging.composer.addPhoto')}>
        <Button label={t('mobile.messaging.composer.camera')} onPress={() => void pick('camera')} />
        <Button variant="ghost" label={t('mobile.messaging.composer.library')} onPress={() => void pick('library')} />
        <Text variant="caption">{t('mobile.messaging.composer.photoPrivacy')}</Text>
      </Sheet>
      {schedule ? (
        <ScheduleSheet
          visible={scheduling}
          timeZone={schedule.timeZone}
          onClose={() => setScheduling(false)}
          onSubmit={async (pick) => {
            const sendAt = toInstant(pick, schedule.timeZone);
            await scheduleMessage(conversationId, { clientMessageId: randomUUID(), body: text.trim(), mediaIds: photos.ready, replyToMessageId: reply?.id, sendAt: sendAt.toISOString() });
            photos.adopt(photos.ready);
            setText('');
            onScheduled(sendAt.toISOString());
          }}
        />
      ) : null}
    </View>
  );
}

function RoundButton({ label, ios, android, onPress, disabled, primary }: { label: string; ios: string; android: string; onPress: () => void; disabled?: boolean; primary?: boolean }) {
  const { colors, minTouchTarget } = useAppTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled: !!disabled }}
      disabled={disabled}
      onPress={onPress}
      style={{ width: minTouchTarget + 4, height: minTouchTarget + 4, borderRadius: (minTouchTarget + 4) / 2, alignItems: 'center', justifyContent: 'center', backgroundColor: primary ? colors.primary : colors.muted, opacity: disabled ? 0.45 : 1 }}>
      <SymbolView name={{ ios, android, web: android } as never} size={22} tintColor={primary ? colors.primaryForeground : colors.primary} />
    </Pressable>
  );
}

/** Picks the time in the school's zone, shows it back in words, and sends. A refusal shows the API's own sentence. */
function ScheduleSheet({ visible, timeZone, onClose, onSubmit }: { visible: boolean; timeZone: string; onClose: () => void; onSubmit: (pick: SchedulePick) => Promise<void> }) {
  const t = useTranslations();
  const { locale, calendar } = usePreferences();
  const now = useClock(15_000);
  const [pick, setPick] = useState<SchedulePick>(() => defaultPick(Date.now(), timeZone));
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const issue = scheduleProblem(pick, timeZone, now);
  const when = formatInstant(toInstant(pick, timeZone), { locale, calendar, timeZone: usableZone(timeZone) });

  async function submit() {
    if (busy || issue) return;
    setBusy(true);
    setProblem(null);
    try {
      await onSubmit(pick);
      onClose();
    } catch (error) {
      setProblem(problemOf(error)?.detail ?? t('mobile.messaging.scheduled.failed'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Sheet visible={visible} onClose={onClose} title={t('mobile.messaging.scheduled.pickTitle')}>
      <Text tone="muted">{t('mobile.messaging.scheduled.pickBody')}</Text>
      <SchedulePicker value={pick} timeZone={timeZone} onChange={setPick} />
      <Text variant="label">{t('mobile.messaging.scheduled.willSend', { when })}</Text>
      {problem ? (
        <Text tone="destructive" accessibilityLiveRegion="polite">
          {problem}
        </Text>
      ) : null}
      <Button label={t('mobile.messaging.scheduled.confirm')} loading={busy} disabled={!!issue} onPress={() => void submit()} />
    </Sheet>
  );
}
