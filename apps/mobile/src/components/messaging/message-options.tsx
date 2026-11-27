import { useState } from 'react';
import { Alert, Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { problemOf } from '@/lib/api-error';
import type { MessageAction } from '@/lib/messaging/actions';
import { editMinutesLeft } from '@/lib/messaging/derive';
import { REACTION_SET, type MessageItem } from '@/lib/messaging/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Button, Sheet, Text, TextField } from '../ui';

export interface MessageOptionsHandlers {
  reply: () => void;
  react: (emoji: string, remove: boolean) => Promise<void>;
  edit: (body: string) => Promise<void>;
  remove: () => Promise<void>;
  /** Opens the shared report sheet for this message (the options close first: the sheet is its own screen). */
  report: () => void;
  block: () => Promise<void>;
  hide: (reason: string) => Promise<void>;
  unhide: () => Promise<void>;
}

type Mode = 'menu' | 'edit' | 'hide';

/**
 * The options of one message, as a sheet. Which actions appear comes from `messageActions` (flags the API sent); every action is
 * checked again by the server and a refusal shows the API's own localized sentence, never an invented reason.
 */
export function MessageOptions({
  message,
  actions,
  editRemainingMs,
  studentViewer,
  onClose,
  handlers,
}: {
  message: MessageItem;
  actions: MessageAction[];
  editRemainingMs: number;
  studentViewer: boolean;
  onClose: () => void;
  handlers: MessageOptionsHandlers;
}) {
  const t = useTranslations();
  const { colors, radii, minTouchTarget, spacing } = useAppTheme();
  const [mode, setMode] = useState<Mode>('menu');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [draft, setDraft] = useState(message.body ?? '');
  const [hideReason, setHideReason] = useState('');

  async function run(work: () => Promise<void>, done: 'close' | 'stay' = 'close') {
    if (busy) return;
    setBusy(true);
    setProblem(null);
    try {
      await work();
      if (done === 'close') onClose();
    } catch (error) {
      setProblem(problemOf(error)?.detail ?? t('mobile.messaging.errors.generic'));
    } finally {
      setBusy(false);
    }
  }

  const has = (action: MessageAction) => actions.includes(action);
  const left = editMinutesLeft(editRemainingMs);
  const title = mode === 'edit' ? t('mobile.messaging.edit.title') : mode === 'hide' ? t('mobile.messaging.moderation.hideTitle') : t('mobile.messaging.message.optionsTitle');

  return (
    <Sheet visible onClose={onClose} title={title}>
      {problem ? (
        <Text tone="destructive" accessibilityLiveRegion="polite">
          {problem}
        </Text>
      ) : null}

      {mode === 'menu' ? (
        <>
          {has('react') ? (
            <View accessibilityRole="toolbar" accessibilityLabel={t('mobile.messaging.message.react')} style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing[2] }}>
              {REACTION_SET.map((emoji) => {
                const mine = message.reactions.some((reaction) => reaction.emoji === emoji && reaction.mine);
                return (
                  <Pressable
                    key={emoji}
                    accessibilityRole="button"
                    accessibilityLabel={emoji}
                    accessibilityState={{ selected: mine }}
                    disabled={busy}
                    onPress={() => void run(() => handlers.react(emoji, mine))}
                    style={{ width: minTouchTarget, height: minTouchTarget, borderRadius: radii.full, alignItems: 'center', justifyContent: 'center', backgroundColor: mine ? colors.primary : colors.muted }}>
                    <Text style={{ fontSize: 22 }}>{emoji}</Text>
                  </Pressable>
                );
              })}
            </View>
          ) : null}
          {has('reply') ? <Button variant="ghost" label={t('mobile.messaging.message.reply')} onPress={() => { onClose(); handlers.reply(); }} /> : null}
          {has('edit') ? <Button variant="ghost" label={left ? t('mobile.messaging.message.editLeft', { minutes: left }) : t('common.edit')} onPress={() => setMode('edit')} /> : null}
          {has('delete') ? (
            <Button
              variant="destructive"
              label={t('common.delete')}
              onPress={() =>
                Alert.alert(t('mobile.messaging.message.deleteTitle'), t('mobile.messaging.message.deleteBody'), [
                  { text: t('common.cancel'), style: 'cancel' },
                  { text: t('common.delete'), style: 'destructive', onPress: () => void run(handlers.remove) },
                ])
              }
            />
          ) : null}
          {has('report') ? <Button variant="ghost" label={t('mobile.moderation.action')} onPress={() => { onClose(); handlers.report(); }} /> : null}
          {has('block') ? (
            <Button
              variant="destructive"
              label={t('mobile.messaging.block.action')}
              onPress={() =>
                Alert.alert(t('mobile.messaging.block.title'), studentViewer ? t('mobile.messaging.block.bodyStudent') : t('mobile.messaging.block.body'), [
                  { text: t('common.cancel'), style: 'cancel' },
                  { text: t('mobile.messaging.block.confirm'), style: 'destructive', onPress: () => void run(handlers.block) },
                ])
              }
            />
          ) : null}
          {has('hide') ? <Button variant="ghost" label={t('mobile.messaging.moderation.hide')} onPress={() => setMode('hide')} /> : null}
          {has('unhide') ? <Button variant="ghost" label={t('mobile.messaging.moderation.unhide')} loading={busy} onPress={() => void run(handlers.unhide)} /> : null}
        </>
      ) : null}

      {mode === 'edit' ? (
        <>
          <TextField label={t('mobile.messaging.edit.label')} value={draft} onChangeText={setDraft} multiline autoFocus style={{ minHeight: 96, textAlignVertical: 'top' }} />
          <Button label={t('common.save')} loading={busy} disabled={draft.trim().length === 0 || draft.trim() === (message.body ?? '').trim()} onPress={() => void run(() => handlers.edit(draft.trim()))} />
          <Button variant="ghost" label={t('common.cancel')} onPress={() => setMode('menu')} />
        </>
      ) : null}

      {mode === 'hide' ? (
        <>
          <Text tone="muted">{t('mobile.messaging.moderation.hideBody')}</Text>
          <TextField label={t('mobile.messaging.moderation.reasonLabel')} value={hideReason} onChangeText={setHideReason} multiline maxLength={300} />
          <Button variant="destructive" label={t('mobile.messaging.moderation.hide')} loading={busy} disabled={hideReason.trim().length === 0} onPress={() => void run(() => handlers.hide(hideReason.trim()))} />
          <Button variant="ghost" label={t('common.cancel')} onPress={() => setMode('menu')} />
        </>
      ) : null}
    </Sheet>
  );
}
