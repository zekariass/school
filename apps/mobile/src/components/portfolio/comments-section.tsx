import { useQueryClient } from '@tanstack/react-query';
import { randomUUID } from 'expo-crypto';
import { useRef, useState } from 'react';
import { ActivityIndicator, Alert, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { ApiRequestError } from '@/lib/api-error';
import { useFormatInstant } from '@/lib/calendar/use-format-instant';
import { commentsKey, itemKey } from '@/lib/portfolio/keys';
import { LIMITS, authorLabel, commentProblem, isTombstone, needsConnection } from '@/lib/portfolio/model';
import { keyAfterFailure, keyFor, type SubmitKey } from '@/lib/portfolio/composer';
import { useComments } from '@/lib/portfolio/queries';
import { createComment, deleteComment, hideComment } from '@/lib/portfolio/requests';
import type { PortfolioComment, PortfolioItem } from '@/lib/portfolio/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { ProblemBanner } from '../problem-banner';
import { Button, Sheet, Text, TextField } from '../ui';

/**
 * The encouragement thread of an approved item: the student, their own guardians and the item's reviewers, nobody else (the API
 * decides who reads and who writes: this shows the thread it answers and the buttons its flags allow). Comments are plain text,
 * kept in memory only and never logged; a hidden comment stays as a note that it was hidden.
 */
export function CommentsSection({ item }: { item: PortfolioItem }) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const queryClient = useQueryClient();
  const format = useFormatInstant();
  const { query, comments } = useComments(item.schoolId, item.id, true);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<unknown>(null);
  const [hiding, setHiding] = useState<PortfolioComment | null>(null);
  const key = useRef<SubmitKey | null>(null);
  const canWrite = item.capabilities.canComment === true;
  const issue = commentProblem(text);

  async function refresh() {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: commentsKey(item.schoolId, item.id) }),
      queryClient.invalidateQueries({ queryKey: itemKey(item.schoolId, item.id) }),
    ]);
  }

  async function send() {
    if (busy || issue) return;
    setBusy(true);
    setProblem(null);
    const body = text.trim();
    try {
      key.current = keyFor(key.current, body, randomUUID);
      await createComment(item.schoolId, item.id, body, { idempotencyKey: key.current.key });
      key.current = null;
      setText('');
      await refresh();
    } catch (error) {
      setProblem(error);
      if (key.current) key.current = keyAfterFailure(key.current, error instanceof ApiRequestError ? error.status : undefined);
    } finally {
      setBusy(false);
    }
  }

  function confirmDelete(comment: PortfolioComment) {
    Alert.alert(t('mobile.portfolio.comments.deleteTitle'), t('mobile.portfolio.comments.deleteBody'), [
      { text: t('common.cancel'), style: 'cancel' },
      {
        text: t('mobile.portfolio.comments.delete'),
        style: 'destructive',
        onPress: () => {
          setProblem(null);
          deleteComment(item.schoolId, item.id, comment.id).then(refresh, setProblem);
        },
      },
    ]);
  }

  return (
    <View style={{ gap: spacing[3] }}>
      <Text variant="heading">{t('mobile.portfolio.comments.title')}</Text>
      <Text variant="caption">{t('mobile.portfolio.comments.privacy')}</Text>

      {query.isPending ? (
        <ActivityIndicator />
      ) : query.isError && comments.length === 0 ? (
        needsConnection(query.error) ? (
          <Text tone="muted">{t('mobile.portfolio.offline.short')}</Text>
        ) : (
          <ProblemBanner error={query.error} fallbackTitle={t('mobile.portfolio.comments.loadFailedTitle')} fallbackBody={t('mobile.portfolio.comments.loadFailedBody')} onRetry={() => void query.refetch()} />
        )
      ) : comments.length === 0 ? (
        <Text tone="muted">{t('mobile.portfolio.comments.none')}</Text>
      ) : (
        comments.map((comment) => (
          <CommentRow key={comment.id} comment={comment} when={format(comment.createdAt)} onDelete={() => confirmDelete(comment)} onHide={() => setHiding(comment)} />
        ))
      )}
      {query.hasNextPage ? <Button variant="ghost" fullWidth={false} label={t('mobile.portfolio.comments.more')} loading={query.isFetchingNextPage} onPress={() => void query.fetchNextPage()} /> : null}

      {canWrite ? (
        <View style={{ gap: spacing[2] }}>
          <TextField
            label={t('mobile.portfolio.comments.writeLabel')}
            value={text}
            onChangeText={setText}
            multiline
            placeholder={t('mobile.portfolio.comments.writePlaceholder')}
            hint={t('mobile.portfolio.compose.counter', { used: [...text].length, max: LIMITS.comment })}
            error={issue === 'too_long' ? t('mobile.portfolio.compose.tooLong', { max: LIMITS.comment }) : undefined}
            style={{ minHeight: 72, textAlignVertical: 'top' }}
          />
          {problem ? <ProblemBanner error={problem} fallbackTitle={t('mobile.portfolio.comments.failedTitle')} fallbackBody={t('mobile.portfolio.comments.failedBody')} /> : null}
          <Button label={t('mobile.portfolio.comments.send')} loading={busy} disabled={!!issue} onPress={() => void send()} />
        </View>
      ) : problem ? (
        <ProblemBanner error={problem} fallbackTitle={t('mobile.portfolio.comments.failedTitle')} fallbackBody={t('mobile.portfolio.comments.failedBody')} />
      ) : null}

      <HideSheet
        comment={hiding}
        onClose={() => setHiding(null)}
        onHide={async (comment, reason) => {
          await hideComment(item.schoolId, item.id, comment.id, reason);
          await refresh();
        }}
      />
    </View>
  );
}

function CommentRow({ comment, when, onDelete, onHide }: { comment: PortfolioComment; when: string; onDelete: () => void; onHide: () => void }) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const label = authorLabel(comment);
  const author = label.kind === 'name' ? label.name : t(label.labelKey);
  const hidden = isTombstone(comment);
  return (
    <View
      accessible
      accessibilityLabel={[author, when, hidden ? t('mobile.portfolio.comments.hidden') : comment.body].filter(Boolean).join(', ')}
      style={{ gap: 4, padding: spacing[3], borderRadius: radii.xl, backgroundColor: comment.mine ? colors.accent : colors.card, borderWidth: 1, borderColor: colors.border }}>
      <Text variant="label" style={comment.mine ? { color: colors.accentForeground } : undefined}>
        {comment.mine ? t('mobile.portfolio.comments.you', { author }) : author}
      </Text>
      {hidden ? <Text tone="muted">{t('mobile.portfolio.comments.hidden')}</Text> : null}
      {comment.body ? <Text style={comment.mine ? { color: colors.accentForeground } : undefined}>{comment.body}</Text> : null}
      <Text variant="caption" style={comment.mine ? { color: colors.accentForeground } : undefined}>
        {when}
      </Text>
      {comment.canDelete || comment.canHide ? (
        <View style={{ flexDirection: 'row', gap: spacing[2], flexWrap: 'wrap' }}>
          {comment.canDelete ? <Button variant="destructive" fullWidth={false} label={t('mobile.portfolio.comments.delete')} onPress={onDelete} /> : null}
          {comment.canHide ? <Button variant="ghost" fullWidth={false} label={t('mobile.portfolio.comments.hide')} onPress={onHide} /> : null}
        </View>
      ) : null}
    </View>
  );
}

/** A reviewer hides a comment with a reason (audited; the comment stays as a note that it was hidden). */
function HideSheet({ comment, onClose, onHide }: { comment: PortfolioComment | null; onClose: () => void; onHide: (comment: PortfolioComment, reason: string) => Promise<void> }) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<unknown>(null);
  return (
    <Sheet visible={!!comment} onClose={onClose} title={t('mobile.portfolio.comments.hideTitle')}>
      <View style={{ gap: spacing[3] }}>
        <Text>{t('mobile.portfolio.comments.hideBody')}</Text>
        <TextField
          label={t('mobile.portfolio.comments.reasonLabel')}
          value={reason}
          onChangeText={setReason}
          multiline
          maxLength={LIMITS.reason}
          hint={t('mobile.portfolio.compose.counter', { used: [...reason].length, max: LIMITS.reason })}
          style={{ minHeight: 72, textAlignVertical: 'top' }}
        />
        {problem ? <ProblemBanner error={problem} fallbackTitle={t('mobile.portfolio.comments.failedTitle')} fallbackBody={t('mobile.portfolio.comments.failedBody')} /> : null}
        <Button
          variant="destructive"
          label={t('mobile.portfolio.comments.hide')}
          loading={busy}
          disabled={!reason.trim()}
          onPress={() => {
            if (!comment) return;
            setBusy(true);
            setProblem(null);
            onHide(comment, reason.trim())
              .then(() => {
                setReason('');
                onClose();
              })
              .catch(setProblem)
              .finally(() => setBusy(false));
          }}
        />
        <Button variant="ghost" label={t('common.cancel')} onPress={onClose} />
      </View>
    </Sheet>
  );
}
