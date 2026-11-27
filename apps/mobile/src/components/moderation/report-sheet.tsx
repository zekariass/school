import { randomUUID } from 'expo-crypto';
import { useRef, useState } from 'react';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { ApiRequestError } from '@/lib/api-error';
import { RETRY_LABEL, classifyReportFailure, retryAfterParts, type ReportFailure } from '@/lib/moderation/failure';
import { useIsOnline, useSafetyText } from '@/lib/moderation/queries';
import { INTRO_LABEL, REASON_LABEL, REPORT_REASONS, type OfferedReason } from '@/lib/moderation/reasons';
import { MAX_DETAILS, attemptFor, buildReportRequest, keepAttemptAfter, validateReport, type ReportAttempt } from '@/lib/moderation/report';
import { submitReport } from '@/lib/moderation/requests';
import type { ReportTarget } from '@/lib/moderation/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Button, Card, Sheet, Text, TextField } from '../ui';

/**
 * The report sheet, one for everything that can be reported (a post, a comment, a story, a message, a person). The parent owns
 * `target` (ids only) and closes the sheet with `onClose`. What the person writes stays in this component's state until it is sent
 * and is dropped when the sheet closes: it is never stored, put in a route, logged or kept in a query. After a send the sheet shows
 * one generic thank-you (never what happens next) and, when the API has one for the country, its safety text. Reporting needs a
 * connection: offline, the sheet says so and nothing is queued. Failures show the API's own sentence, except 404 (a target that
 * cannot be seen looks exactly like one that does not exist).
 */
export function ReportSheet({ target, onClose }: { target: ReportTarget | null; onClose: () => void }) {
  // Mounted only while a target is set, and remounted for another one: whatever was typed for the last report is gone with it.
  if (!target) return null;
  return <ReportSheetBody key={`${target.type}:${target.id}`} target={target} onClose={onClose} />;
}

function ReportSheetBody({ target, onClose }: { target: ReportTarget; onClose: () => void }) {
  const t = useTranslations();
  const { colors, radii, minTouchTarget, spacing } = useAppTheme();
  const online = useIsOnline();
  const [reason, setReason] = useState<OfferedReason | null>(null);
  const [details, setDetails] = useState('');
  const [phase, setPhase] = useState<'form' | 'sending' | 'done'>('form');
  const [failure, setFailure] = useState<ReportFailure | null>(null);
  const [invalid, setInvalid] = useState<'reasonRequired' | 'detailsTooLong' | null>(null);
  const attempt = useRef<ReportAttempt | null>(null);
  const safety = useSafetyText(target.schoolId, true);

  function close() {
    attempt.current = null;
    onClose();
  }

  async function send() {
    if (phase === 'sending') return;
    const draft = { reason, details };
    const problem = validateReport(draft);
    const body = buildReportRequest(target, draft);
    setInvalid(problem);
    if (problem || !body) return;
    setFailure(null);
    setPhase('sending');
    attempt.current = attemptFor(attempt.current, body, randomUUID);
    try {
      await submitReport(body, { idempotencyKey: attempt.current.key });
      attempt.current = null;
      // The words are not kept a moment longer than needed.
      setDetails('');
      setReason(null);
      setPhase('done');
    } catch (error) {
      const status = error instanceof ApiRequestError ? error.status : undefined;
      if (!keepAttemptAfter(status)) attempt.current = null;
      setFailure(classifyReportFailure(error));
      setPhase('form');
    }
  }

  function failureMessage(problem: ReportFailure): string {
    switch (problem.kind) {
      case 'notFound':
        return t('mobile.moderation.error.notFound');
      case 'offline':
        return t('mobile.moderation.error.offline');
      case 'rateLimited': {
        const base = problem.detail ?? t('mobile.moderation.error.rateLimited');
        if (!problem.retryAfterSeconds) return base;
        const { unit, value } = retryAfterParts(problem.retryAfterSeconds);
        return `${base} ${t(RETRY_LABEL[unit], { value })}`;
      }
      case 'refused':
      case 'failed':
        return problem.detail ?? t('mobile.moderation.error.generic');
    }
  }

  const failureText = failure ? failureMessage(failure) : null;
  const sending = phase === 'sending';

  return (
    <Sheet visible onClose={close} title={phase === 'done' ? t('mobile.moderation.thanksTitle') : t('mobile.moderation.title')}>
      {phase === 'done' ? (
        <View style={{ gap: spacing[4] }} accessibilityLiveRegion="polite">
          <Text>{t('mobile.moderation.thanksBody')}</Text>
          {safety ? <SafetyNote text={safety} /> : null}
          <Button label={t('common.done')} onPress={close} />
        </View>
      ) : (
        <View style={{ gap: spacing[4] }}>
          <Text tone="muted">{t(INTRO_LABEL[target.type])}</Text>

          {!online ? (
            <Card accessibilityRole="alert" accessibilityLiveRegion="polite">
              <Text variant="label">{t('mobile.moderation.offlineTitle')}</Text>
              <Text tone="muted">{t('mobile.moderation.offlineBody')}</Text>
            </Card>
          ) : null}

          <Text variant="label">{t('mobile.moderation.reasonLabel')}</Text>
          <View accessibilityRole="radiogroup" style={{ gap: spacing[2] }}>
            {REPORT_REASONS.map((value) => (
              <Pressable
                key={value}
                accessibilityRole="radio"
                accessibilityState={{ selected: reason === value, disabled: sending }}
                accessibilityLabel={t(REASON_LABEL[value])}
                disabled={sending}
                onPress={() => {
                  setReason(value);
                  setInvalid(null);
                }}
                style={{
                  minHeight: minTouchTarget,
                  justifyContent: 'center',
                  paddingHorizontal: 12,
                  borderRadius: radii.lg,
                  borderWidth: 1,
                  borderColor: reason === value ? colors.primary : colors.border,
                  backgroundColor: reason === value ? colors.accent : colors.card,
                }}>
                <Text variant="label">{t(REASON_LABEL[value])}</Text>
              </Pressable>
            ))}
          </View>
          {invalid === 'reasonRequired' ? (
            <Text variant="caption" tone="destructive" accessibilityLiveRegion="polite">
              {t('mobile.moderation.error.reasonRequired')}
            </Text>
          ) : null}

          {reason === 'SAFETY_CONCERN' && safety ? <SafetyNote text={safety} /> : null}

          <TextField
            label={t('mobile.moderation.detailsLabel')}
            hint={t('mobile.moderation.detailsHint')}
            value={details}
            onChangeText={(text) => {
              setDetails(text);
              setInvalid(null);
            }}
            editable={!sending}
            multiline
            autoCorrect
            maxLength={MAX_DETAILS}
            error={invalid === 'detailsTooLong' ? t('mobile.moderation.error.detailsTooLong', { max: MAX_DETAILS }) : undefined}
            style={{ minHeight: 96, textAlignVertical: 'top' }}
          />
          <Text variant="caption" style={{ textAlign: 'right' }}>
            {t('mobile.moderation.detailsCount', { count: details.length, max: MAX_DETAILS })}
          </Text>

          {failureText ? (
            <Text tone="destructive" accessibilityLiveRegion="polite">
              {failureText}
            </Text>
          ) : null}

          <Button label={t('mobile.moderation.send')} loading={sending} disabled={!online || !reason || failure?.kind === 'notFound'} onPress={() => void send()} />
          <Button variant="ghost" label={t('common.cancel')} onPress={close} />
        </View>
      )}
    </Sheet>
  );
}

/** The API's safety text for the country, shown as it was sent. */
function SafetyNote({ text }: { text: string }) {
  const t = useTranslations();
  return (
    <Card accessibilityRole="summary">
      <Text variant="label">{t('mobile.moderation.safetyTitle')}</Text>
      <Text selectable>{text}</Text>
    </Card>
  );
}
