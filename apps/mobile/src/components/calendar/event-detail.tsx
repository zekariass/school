import { useQueryClient } from '@tanstack/react-query';
import { router } from 'expo-router';
import { SymbolView, type SymbolViewProps } from 'expo-symbols';
import { useState } from 'react';
import { Alert, Pressable, RefreshControl, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Button, Card, Screen, Sheet, Text, TextField } from '@/components/ui';
import { eventActions, isParentMeeting, rsvpLabelKey, rsvpModel, RSVP_ANSWERS } from '@/lib/calendar/event-model';
import { itemStyle } from '@/lib/calendar/item-style';
import { dateText, eventWhenText } from '@/lib/calendar/labels';
import { occurrenceText, occurrenceTimes } from '@/lib/calendar/occurrence';
import { replaceEvent, forgetEvent } from '@/lib/calendar/queries';
import { describeRepeat, parseRule } from '@/lib/calendar/recurrence';
import { weekdayName } from '@/lib/calendar/names';
import { answerEvent, cancelEvent, cancelOccurrence, deleteEvent, restoreOccurrence } from '@/lib/calendar/requests';
import type { CalendarEventView, RsvpAnswer } from '@/lib/calendar/types';
import { useLabelContext, useLooseT } from '@/lib/calendar/use-labels';
import { zoneLabel, zonesDiffer } from '@/lib/calendar/zone';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { InlineError } from './inline-error';

type Pending = 'answer' | 'cancel' | 'delete' | 'restore' | null;

/**
 * One event (or one occurrence of a repeating one): when, where, what, who and how it repeats, the answer buttons, and what the
 * caller may do with it according to the API's `capabilities`. Everything shown comes from the API; nothing here is stored.
 */
export function EventDetail({ view, occurrenceId, refresh }: { view: CalendarEventView; occurrenceId: string | null; refresh: () => Promise<unknown> }) {
  const t = useTranslations();
  const tx = useLooseT();
  const { colors, radii, spacing } = useAppTheme();
  const context = useLabelContext();
  const queryClient = useQueryClient();
  const [refreshing, setRefreshing] = useState(false);
  const [busy, setBusy] = useState<Pending>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [scopeSheet, setScopeSheet] = useState<'edit' | 'cancel' | null>(null);
  const [cancelSheet, setCancelSheet] = useState<'event' | 'occurrence' | null>(null);
  const [reason, setReason] = useState('');
  const [openedAt] = useState(() => Date.now());

  const actions = eventActions(view, occurrenceId);
  const times = occurrenceTimes(view, occurrenceId);
  const text = occurrenceText(view, times.override);
  const rsvp = rsvpModel(view);
  const style = itemStyle({ source: view.scope === 'PERSONAL' ? 'PERSONAL_EVENT' : 'EVENT', kind: view.eventType });
  const meeting = isParentMeeting(view);
  // Somebody who was asked (or may answer) gets the buttons; a manager who only looks at the totals does not.
  const showAnswer = rsvp.canAnswer || rsvp.mine !== null;
  const cancelled = actions.cancelled || actions.occurrenceCancelled;
  const repeat = view.recurring ? parseRule(view.recurrenceRule, view.startDate ?? times.startDate ?? '', view.timeZone) : null;
  const repeatText = repeat
    ? describeRepeat(repeat, { t: context.t, weekdayName: (weekday) => weekdayName(weekday, context.locale), formatDate: (date) => dateText(date, context.calendar, context.locale) })
    : '';
  const anchorMs = times.startMs ?? openedAt;
  const zoneDiffers = zonesDiffer(view.timeZone, context.deviceZone, anchorMs);

  async function run(kind: Exclude<Pending, null>, action: () => Promise<CalendarEventView | void>) {
    if (busy) return;
    setBusy(kind);
    setProblem(null);
    try {
      const result = await action();
      if (result) replaceEvent(queryClient, result);
    } catch (error) {
      setProblem(error);
      // Something changed meanwhile (409, 404): show the event as it now is.
      void refresh();
    } finally {
      setBusy(null);
    }
  }

  async function onRefresh() {
    setRefreshing(true);
    try {
      await refresh();
    } finally {
      setRefreshing(false);
    }
  }

  const openEdit = (occurrence: boolean) =>
    router.push({ pathname: '/events/compose', params: { eventId: view.id, ...(occurrence && occurrenceId ? { occurrenceId } : {}) } });

  function confirmDelete() {
    Alert.alert(t('mobile.calendar.detail.deleteTitle'), t('mobile.calendar.detail.deleteBody'), [
      { text: t('common.cancel'), style: 'cancel' },
      {
        text: t('common.delete'),
        style: 'destructive',
        onPress: () =>
          void run('delete', async () => {
            await deleteEvent(view.id);
            forgetEvent(queryClient, view.id);
            if (router.canGoBack()) router.back();
            else router.replace('/');
          }),
      },
    ]);
  }

  function submitCancel() {
    const which = cancelSheet;
    setCancelSheet(null);
    const why = reason.trim() || null;
    setReason('');
    if (which === 'occurrence' && occurrenceId) void run('cancel', () => cancelOccurrence(view.id, occurrenceId, why));
    else void run('cancel', () => cancelEvent(view.id, why));
  }

  return (
    <Screen scroll edges={['bottom']} refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void onRefresh()} tintColor={colors.primary} />} contentStyle={{ gap: spacing[4] }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[2] }} accessible accessibilityLabel={tx(style.labelKey)}>
        <SymbolView name={{ ios: style.icon.ios, android: style.icon.android, web: style.icon.android } as SymbolViewProps['name']} size={22} tintColor={colors[style.tone]} />
        <Text variant="label">{tx(style.labelKey)}</Text>
      </View>

      <Text variant="title" style={cancelled ? { textDecorationLine: 'line-through', color: colors.mutedForeground } : undefined}>
        {text.title}
      </Text>

      {cancelled ? (
        <View accessibilityRole="alert" style={{ padding: spacing[3], borderRadius: radii.lg, borderWidth: 1, borderColor: colors.destructive, gap: spacing[1] }}>
          <Text variant="label" tone="destructive">
            {actions.cancelled ? t('mobile.calendar.detail.cancelledEvent') : t('mobile.calendar.detail.cancelledOccurrence')}
          </Text>
          {(actions.cancelled ? view.cancellationReason : times.cancellationReason) ? <Text>{actions.cancelled ? view.cancellationReason : times.cancellationReason}</Text> : null}
        </View>
      ) : null}

      <Card>
        <InfoRow icon={{ ios: 'clock', android: 'schedule' }} label={t('mobile.calendar.detail.when')} value={eventWhenText(times, view.timeZone, context)} />
        {!view.allDay ? (
          <InfoRow
            icon={{ ios: 'globe', android: 'public' }}
            label={t('mobile.calendar.detail.zone')}
            value={t('mobile.calendar.detail.zoneValue', { zone: zoneLabel(view.timeZone) })}
            note={zoneDiffers && context.deviceZone ? t('mobile.calendar.detail.zoneDiffers', { phone: zoneLabel(context.deviceZone) }) : undefined}
          />
        ) : null}
        {repeatText ? <InfoRow icon={{ ios: 'repeat', android: 'repeat' }} label={t('mobile.calendar.detail.repeats')} value={repeatText} /> : null}
        {text.location ? <InfoRow icon={{ ios: 'mappin.and.ellipse', android: 'location_on' }} label={t('mobile.calendar.detail.where')} value={text.location} /> : null}
        <InfoRow icon={{ ios: 'person.2', android: 'group' }} label={t('mobile.calendar.detail.audience')} value={tx(`mobile.calendar.audience.${view.scope}`)} />
        {view.createdByName ? <InfoRow icon={{ ios: 'person.crop.circle', android: 'person' }} label={t('mobile.calendar.detail.organiser')} value={view.createdByName} /> : null}
      </Card>

      {text.description ? (
        <View style={{ gap: spacing[1] }}>
          <Text variant="label">{t('mobile.calendar.detail.about')}</Text>
          {/* Plain text, as the API cleaned it: never rendered as markup or links. */}
          <Text selectable>{text.description}</Text>
        </View>
      ) : null}

      {meeting && view.capabilities?.canEdit ? <Text variant="caption">{t('mobile.calendar.detail.meetingPrivacy')}</Text> : null}

      {rsvp.enabled ? (
        <Card>
          <Text variant="heading">{t('mobile.calendar.rsvp.title')}</Text>
          {showAnswer ? <Text accessibilityLiveRegion="polite">{rsvp.mine ? t('mobile.calendar.rsvp.mine', { answer: tx(rsvpLabelKey(rsvp.mine)) }) : t('mobile.calendar.rsvp.notAsked')}</Text> : null}
          {showAnswer ? (
            <View accessibilityRole="radiogroup" accessibilityLabel={t('mobile.calendar.rsvp.title')} style={{ flexDirection: 'row', gap: spacing[2] }}>
              {RSVP_ANSWERS.map((answer) => (
                <AnswerButton
                  key={answer}
                  answer={answer}
                  selected={rsvp.mine === answer}
                  disabled={!rsvp.canAnswer || busy !== null}
                  onPress={() => void run('answer', () => answerEvent(view.id, answer))}
                />
              ))}
            </View>
          ) : null}
          {showAnswer && !rsvp.canAnswer ? <Text variant="caption">{t('mobile.calendar.rsvp.closed')}</Text> : null}
          {rsvp.counts ? (
            <View style={{ gap: 2 }} accessible accessibilityLabel={t('mobile.calendar.rsvp.countsLabel', { yes: rsvp.counts.yes, maybe: rsvp.counts.maybe, no: rsvp.counts.no, pending: rsvp.counts.pending })}>
              <Text variant="label">{t('mobile.calendar.rsvp.counts')}</Text>
              <Text>{t('mobile.calendar.rsvp.countsLine', { yes: rsvp.counts.yes, maybe: rsvp.counts.maybe, no: rsvp.counts.no, pending: rsvp.counts.pending })}</Text>
            </View>
          ) : null}
          {rsvp.participants ? (
            <View style={{ gap: spacing[2] }}>
              <Text variant="label">{t('mobile.calendar.rsvp.participants')}</Text>
              {rsvp.participants.length === 0 ? <Text variant="caption">{t('mobile.calendar.rsvp.noParticipants')}</Text> : null}
              {rsvp.participants.map((participant) => (
                <View key={participant.userId} accessible accessibilityLabel={`${participant.name}. ${tx(rsvpLabelKey(participant.status))}`} style={{ flexDirection: 'row', justifyContent: 'space-between', gap: spacing[2] }}>
                  <Text style={{ flex: 1 }}>{participant.name}</Text>
                  <Text variant="label">{tx(rsvpLabelKey(participant.status))}</Text>
                </View>
              ))}
            </View>
          ) : null}
        </Card>
      ) : rsvp.recurringNote ? (
        <Text variant="caption">{t('mobile.calendar.rsvp.recurringNote')}</Text>
      ) : null}

      <InlineError error={problem} />

      <View style={{ gap: spacing[2] }}>
        {actions.edit ? <Button label={t('mobile.calendar.detail.edit')} onPress={() => (actions.recurring && occurrenceId ? setScopeSheet('edit') : openEdit(false))} /> : null}
        {actions.restoreOccurrence ? (
          <Button variant="ghost" label={t('mobile.calendar.detail.restoreOccurrence')} loading={busy === 'restore'} onPress={() => occurrenceId && void run('restore', () => restoreOccurrence(view.id, occurrenceId))} />
        ) : null}
        {actions.cancel ? (
          <Button variant="destructive" label={t('mobile.calendar.detail.cancel')} loading={busy === 'cancel'} onPress={() => (actions.cancelOccurrence ? setScopeSheet('cancel') : setCancelSheet('event'))} />
        ) : null}
        {actions.delete ? <Button variant="destructive" label={t('mobile.calendar.detail.delete')} loading={busy === 'delete'} onPress={confirmDelete} /> : null}
      </View>

      <Sheet visible={scopeSheet !== null} onClose={() => setScopeSheet(null)} title={scopeSheet === 'edit' ? t('mobile.calendar.scope.editTitle') : t('mobile.calendar.scope.cancelTitle')}>
        <Button
          label={t('mobile.calendar.scope.thisOne')}
          onPress={() => {
            const kind = scopeSheet;
            setScopeSheet(null);
            if (kind === 'edit') openEdit(true);
            else setCancelSheet('occurrence');
          }}
        />
        <Button
          variant="ghost"
          label={t('mobile.calendar.scope.all')}
          onPress={() => {
            const kind = scopeSheet;
            setScopeSheet(null);
            if (kind === 'edit') openEdit(false);
            else setCancelSheet('event');
          }}
        />
        {scopeSheet === 'edit' ? <Text variant="caption">{t('mobile.calendar.scope.editWarning')}</Text> : null}
      </Sheet>

      <Sheet visible={cancelSheet !== null} onClose={() => setCancelSheet(null)} title={cancelSheet === 'occurrence' ? t('mobile.calendar.cancelSheet.titleOccurrence') : t('mobile.calendar.cancelSheet.titleEvent')}>
        <Text>{meeting ? t('mobile.calendar.cancelSheet.bodyMeeting') : t('mobile.calendar.cancelSheet.body')}</Text>
        <TextField label={t('mobile.calendar.cancelSheet.reason')} value={reason} onChangeText={setReason} maxLength={500} multiline />
        <Button variant="destructive" label={t('mobile.calendar.cancelSheet.confirm')} onPress={submitCancel} />
        <Button variant="ghost" label={t('mobile.calendar.cancelSheet.keep')} onPress={() => setCancelSheet(null)} />
      </Sheet>
    </Screen>
  );
}

function InfoRow({ icon, label, value, note }: { icon: { ios: string; android: string }; label: string; value: string; note?: string | undefined }) {
  const { colors, spacing } = useAppTheme();
  return (
    <View accessible accessibilityLabel={`${label}: ${value}${note ? `. ${note}` : ''}`} style={{ flexDirection: 'row', gap: spacing[3] }}>
      <View accessibilityElementsHidden importantForAccessibility="no-hide-descendants" style={{ width: 24, paddingTop: 2 }}>
        <SymbolView name={{ ios: icon.ios, android: icon.android, web: icon.android } as SymbolViewProps['name']} size={20} tintColor={colors.mutedForeground} />
      </View>
      <View style={{ flex: 1 }}>
        <Text variant="caption">{label}</Text>
        <Text>{value}</Text>
        {note ? (
          <Text variant="caption" style={{ color: colors.warning }}>
            {note}
          </Text>
        ) : null}
      </View>
    </View>
  );
}

function AnswerButton({ answer, selected, disabled, onPress }: { answer: RsvpAnswer; selected: boolean; disabled: boolean; onPress: () => void }) {
  const tx = useLooseT();
  const { colors, radii, minTouchTarget } = useAppTheme();
  const icon = answer === 'YES' ? { ios: 'checkmark', android: 'check' } : answer === 'NO' ? { ios: 'xmark', android: 'close' } : { ios: 'questionmark', android: 'question_mark' };
  const foreground = selected ? colors.primaryForeground : colors.foreground;
  return (
    <Pressable
      accessibilityRole="radio"
      accessibilityState={{ selected, disabled }}
      accessibilityLabel={tx(rsvpLabelKey(answer))}
      disabled={disabled}
      onPress={onPress}
      style={{
        flex: 1,
        minHeight: minTouchTarget + 4,
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 6,
        borderRadius: radii.full,
        borderWidth: 1,
        borderColor: selected ? colors.primary : colors.border,
        backgroundColor: selected ? colors.primary : colors.card,
        opacity: disabled && !selected ? 0.5 : 1,
      }}>
      <SymbolView name={{ ios: icon.ios, android: icon.android, web: icon.android } as SymbolViewProps['name']} size={16} tintColor={foreground} />
      <Text variant="label" style={{ color: foreground }}>
        {tx(rsvpLabelKey(answer))}
      </Text>
    </Pressable>
  );
}
