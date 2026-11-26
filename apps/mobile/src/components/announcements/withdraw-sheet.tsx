import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslations } from 'use-intl';

import { MAX_REASON_CHARS } from '@/lib/announcements/composer';
import { refreshAnnouncements, replaceAnnouncement } from '@/lib/announcements/queries';
import { withdraw } from '@/lib/announcements/requests';
import type { AnnouncementView } from '@/lib/announcements/types';

import { ProblemBanner } from '../problem-banner';
import { Button, Sheet, Text, TextField } from '../ui';

/**
 * Withdraws a published announcement. The text stays in the school's records; recipients see it as withdrawn with the reason
 * written here (they read it, so it is a public note, not an internal one).
 */
export function WithdrawSheet({ view, visible, onClose }: { view: AnnouncementView; visible: boolean; onClose: () => void }) {
  const t = useTranslations();
  const queryClient = useQueryClient();
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<unknown>(null);

  async function submit() {
    if (busy) return;
    setBusy(true);
    setProblem(null);
    try {
      const answer = await withdraw(view.schoolId, view.id, reason.trim() || null);
      replaceAnnouncement(queryClient, answer);
      void refreshAnnouncements(queryClient);
      setReason('');
      onClose();
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Sheet visible={visible} onClose={onClose} title={t('mobile.announcements.withdraw.title')}>
      <Text>{t('mobile.announcements.withdraw.body')}</Text>
      <TextField
        label={t('mobile.announcements.withdraw.reasonLabel')}
        hint={t('mobile.announcements.withdraw.reasonHint')}
        value={reason}
        onChangeText={(value) => setReason(value.slice(0, MAX_REASON_CHARS))}
        multiline
        style={{ minHeight: 80, textAlignVertical: 'top' }}
      />
      {problem ? <ProblemBanner error={problem} fallbackTitle={t('mobile.announcements.withdraw.failedTitle')} fallbackBody={t('mobile.announcements.withdraw.failedBody')} /> : null}
      <Button variant="destructive" label={t('mobile.announcements.withdraw.confirm')} loading={busy} onPress={() => void submit()} />
      <Button variant="ghost" label={t('common.cancel')} disabled={busy} onPress={onClose} />
    </Sheet>
  );
}
