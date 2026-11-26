import { useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { problemOf } from '@/lib/api-error';
import { gateProblemOf, withoutStudents, type GateProblem } from '@/lib/feed/gate';
import { refreshFeeds, removePostEverywhere } from '@/lib/feed/queries';
import { approveContribution, rejectContribution } from '@/lib/feed/requests';
import type { PostView } from '@/lib/feed/types';
import { useRoster } from '@/lib/people/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Button, Sheet, Text, TextField } from '../ui';

import { ConfirmNoUnconsented, GatePanel } from './gate-panel';
import { PostCard } from './post-card';
import { StudentTagger } from './student-picker';

/**
 * Reviewing a family contribution: the preview as it will look, the students to tag (consent is checked for each when you
 * approve), the untagged-photos statement, and the two decisions. A rejection needs a reason, which the contributor reads.
 * Consent-gate refusals are explained here and nothing is lost.
 */
export function ReviewSheet({ post, onClose }: { post: PostView | null; onClose: () => void }) {
  return (
    <Sheet visible={!!post} onClose={onClose}>
      {post ? <ReviewBody key={post.id} post={post} onClose={onClose} /> : null}
    </Sheet>
  );
}

function ReviewBody({ post, onClose }: { post: PostView; onClose: () => void }) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const queryClient = useQueryClient();
  const roster = useRoster(post.classId ? post.schoolId : undefined, post.classId ?? undefined);
  const [tagged, setTagged] = useState<string[]>(() => post.tags.map((tag) => tag.studentId));
  const [confirm, setConfirm] = useState(false);
  const [gate, setGate] = useState<GateProblem | null>(null);
  const [busy, setBusy] = useState<'approve' | 'reject' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');

  const options = useMemo(() => (roster.data ?? []).map((entry) => ({ id: entry.id, name: entry.name })), [roster.data]);
  const known = useMemo(() => new Map(options.map((option) => [option.id, option.name])), [options]);
  const initial = post.tags.map((tag) => tag.studentId);
  const changed = tagged.length !== initial.length || tagged.some((id) => !initial.includes(id));
  const needsStatement = post.media.length > 0 && tagged.length === 0;

  async function finish() {
    removePostEverywhere(queryClient, post.id);
    await refreshFeeds(queryClient);
    onClose();
  }

  async function approve() {
    setBusy('approve');
    setError(null);
    setGate(null);
    try {
      await approveContribution(post.schoolId, post.id, { confirmNoUnconsentedStudents: needsStatement && confirm, ...(changed ? { studentIds: tagged } : {}) });
      await finish();
    } catch (failure) {
      const found = gateProblemOf(failure);
      if (found) setGate(found);
      else setError(problemOf(failure)?.detail ?? t('mobile.feed.approvals.failed'));
    } finally {
      setBusy(null);
    }
  }

  async function reject() {
    const text = reason.trim();
    if (!text) return;
    setBusy('reject');
    setError(null);
    try {
      await rejectContribution(post.schoolId, post.id, text);
      await finish();
    } catch (failure) {
      setError(problemOf(failure)?.detail ?? t('mobile.feed.approvals.failed'));
    } finally {
      setBusy(null);
    }
  }

  return (
    <View style={{ gap: spacing[4] }}>
      <Text variant="heading">{t('mobile.feed.approvals.reviewTitle')}</Text>
      <PostCard post={post} />
      {post.media.length > 0 ? (
        <StudentTagger
          title={t('mobile.feed.approvals.tagTitle')}
          hint={t('mobile.feed.approvals.consentNote')}
          options={options}
          selected={tagged}
          loading={roster.isPending}
          onToggle={(id) => {
            setTagged((current) => (current.includes(id) ? current.filter((x) => x !== id) : [...current, id]));
            setConfirm(false);
          }}
        />
      ) : null}
      <GatePanel
        gate={gate?.kind === 'consent_missing' ? gate : null}
        known={known}
        onRemoveTags={() => {
          if (gate?.kind === 'consent_missing') setTagged((current) => withoutStudents(current, gate.students));
          setGate(null);
        }}
      />
      {needsStatement ? <ConfirmNoUnconsented checked={confirm} onChange={setConfirm} highlight={gate?.kind === 'confirmation_required'} /> : null}
      {error ? (
        <Text tone="destructive" accessibilityLiveRegion="polite">
          {error}
        </Text>
      ) : null}
      {rejecting ? (
        <View style={{ gap: spacing[2] }}>
          <TextField
            label={t('mobile.feed.approvals.reasonLabel')}
            hint={t('mobile.feed.approvals.reasonHint')}
            value={reason}
            onChangeText={setReason}
            multiline
            maxLength={500}
            style={{ minHeight: 88, textAlignVertical: 'top' }}
          />
          <Button variant="destructive" label={t('mobile.feed.approvals.sendRejection')} disabled={!reason.trim()} loading={busy === 'reject'} onPress={() => void reject()} />
          <Button variant="ghost" label={t('common.cancel')} onPress={() => setRejecting(false)} />
        </View>
      ) : (
        <View style={{ gap: spacing[2] }}>
          <Button label={t('mobile.feed.approvals.approve')} loading={busy === 'approve'} onPress={() => void approve()} />
          <Button variant="destructive" label={t('mobile.feed.approvals.reject')} onPress={() => setRejecting(true)} />
          <Button variant="ghost" label={t('common.cancel')} onPress={onClose} />
        </View>
      )}
    </View>
  );
}
