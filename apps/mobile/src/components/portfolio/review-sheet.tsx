import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { problemOf } from '@/lib/api-error';
import { gateProblemOf, type GateProblem } from '@/lib/portfolio/gate';
import { VISIBILITY_HINT, VISIBILITY_LABEL } from '@/lib/portfolio/labels';
import { LIMITS, lowerVisibilities, needsConnection, noteRequired } from '@/lib/portfolio/model';
import { refreshPortfolio, removeFromQueues, replaceItem } from '@/lib/portfolio/queries';
import { reviewItem } from '@/lib/portfolio/requests';
import type { PortfolioItem, PortfolioVisibility, ReviewDecision } from '@/lib/portfolio/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Chips } from '../feed/chips';
import { Button, Sheet, Text, TextField } from '../ui';

import { ConsentPanel } from './consent-panels';

const DECISIONS: readonly ReviewDecision[] = ['APPROVE', 'REQUEST_CHANGES', 'REJECT'];

const DECISION_LABEL = {
  APPROVE: 'mobile.portfolio.review.approve',
  REQUEST_CHANGES: 'mobile.portfolio.review.requestChanges',
  REJECT: 'mobile.portfolio.review.reject',
} as const satisfies Record<ReviewDecision, string>;

const DECISION_HINT = {
  APPROVE: 'mobile.portfolio.review.approveHint',
  REQUEST_CHANGES: 'mobile.portfolio.review.requestChangesHint',
  REJECT: 'mobile.portfolio.review.rejectHint',
} as const satisfies Record<ReviewDecision, string>;

const SEND_LABEL = {
  APPROVE: 'mobile.portfolio.review.sendApprove',
  REQUEST_CHANGES: 'mobile.portfolio.review.sendRequestChanges',
  REJECT: 'mobile.portfolio.review.sendReject',
} as const satisfies Record<ReviewDecision, string>;

/**
 * A reviewing teacher decides on a SUBMITTED item: approve (optionally for a narrower audience, never a wider one), ask for
 * changes, or reject. Asking for changes and rejecting need a note, which only the student and the reviewers read. When the
 * consent gate refuses an approval, the item is left as it was and the sheet explains what to do: the typed note and the choice
 * stay where they are.
 */
export function ReviewSheet({ item, onClose, onDone }: { item: PortfolioItem | null; onClose: () => void; onDone?: (item: PortfolioItem) => void }) {
  return (
    <Sheet visible={!!item} onClose={onClose}>
      {item ? <ReviewBody key={item.id} item={item} onClose={onClose} onDone={onDone} /> : null}
    </Sheet>
  );
}

function ReviewBody({ item, onClose, onDone }: { item: PortfolioItem; onClose: () => void; onDone: ((item: PortfolioItem) => void) | undefined }) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const queryClient = useQueryClient();
  const [decision, setDecision] = useState<ReviewDecision>('APPROVE');
  const [note, setNote] = useState('');
  const [visibility, setVisibility] = useState<PortfolioVisibility>(item.visibility);
  const [gate, setGate] = useState<GateProblem | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const needsNote = noteRequired(decision);
  const trimmed = note.trim();
  const choices = lowerVisibilities(item.visibility);
  const lowered = visibility !== item.visibility;

  async function send() {
    if (busy || (needsNote && !trimmed)) return;
    setBusy(true);
    setError(null);
    setGate(null);
    try {
      const result = await reviewItem(item.schoolId, item.id, {
        decision,
        ...(trimmed ? { note: trimmed } : {}),
        ...(decision === 'APPROVE' && lowered ? { visibility } : {}),
      });
      replaceItem(queryClient, result);
      removeFromQueues(queryClient, item.id);
      await refreshPortfolio(queryClient);
      onDone?.(result);
      onClose();
    } catch (failure) {
      const found = gateProblemOf(failure);
      if (found) setGate(found);
      else setError(needsConnection(failure) ? t('mobile.portfolio.review.offline') : (problemOf(failure)?.detail ?? t('mobile.portfolio.review.failed')));
    } finally {
      setBusy(false);
    }
  }

  return (
    <View style={{ gap: spacing[4] }}>
      <Text variant="heading">{t('mobile.portfolio.review.title')}</Text>
      <Chips<ReviewDecision>
        accessibilityLabel={t('mobile.portfolio.review.title')}
        value={decision}
        onChange={(value) => {
          setDecision(value);
          setGate(null);
          setError(null);
        }}
        chips={DECISIONS.map((value) => ({ value, label: t(DECISION_LABEL[value]) }))}
      />
      <Text variant="caption">{t(DECISION_HINT[decision])}</Text>

      {decision === 'APPROVE' && choices.length > 1 ? (
        <View style={{ gap: spacing[2] }}>
          <Text variant="label">{t('mobile.portfolio.review.audience')}</Text>
          <Chips
            accessibilityLabel={t('mobile.portfolio.review.audience')}
            value={visibility}
            onChange={setVisibility}
            chips={choices.map((value) => ({ value, label: t(VISIBILITY_LABEL[value]) }))}
          />
          <Text variant="caption">{lowered ? t(VISIBILITY_HINT[visibility]) : t('mobile.portfolio.review.audienceHint')}</Text>
        </View>
      ) : null}

      <TextField
        label={needsNote ? t('mobile.portfolio.review.noteLabelRequired') : t('mobile.portfolio.review.noteLabel')}
        hint={needsNote ? t('mobile.portfolio.review.noteHintRequired') : t('mobile.portfolio.review.noteHint')}
        value={note}
        onChangeText={setNote}
        multiline
        maxLength={LIMITS.note}
        style={{ minHeight: 88, textAlignVertical: 'top' }}
      />

      <ConsentPanel gate={gate?.kind === 'consent_missing' ? gate : null} known={new Map()} mode="review" />
      {gate?.kind === 'confirmation_required' ? (
        <View accessibilityRole="alert" accessibilityLiveRegion="assertive" style={{ gap: spacing[1] }}>
          <Text variant="heading">{t('mobile.portfolio.gate.title')}</Text>
          <Text>{t('mobile.portfolio.gate.confirmationReview')}</Text>
        </View>
      ) : null}
      {error ? (
        <Text tone="destructive" accessibilityLiveRegion="polite">
          {error}
        </Text>
      ) : null}

      <View style={{ gap: spacing[2] }}>
        <Button
          variant={decision === 'REJECT' ? 'destructive' : 'primary'}
          label={t(SEND_LABEL[decision])}
          loading={busy}
          disabled={needsNote && !trimmed}
          onPress={() => void send()}
        />
        <Button variant="ghost" label={t('common.cancel')} onPress={onClose} />
      </View>
    </View>
  );
}
