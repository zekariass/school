import { useQueryClient } from '@tanstack/react-query';
import { randomUUID } from 'expo-crypto';
import { Stack, router, useLocalSearchParams, usePreventRemove } from 'expo-router';
import { useMemo, useReducer, useRef, useState } from 'react';
import { ActivityIndicator, Alert, KeyboardAvoidingView, Platform, ScrollView, Switch, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { AudiencePicker } from '@/components/announcements/audience-picker';
import { CheckRow } from '@/components/announcements/check-row';
import { ConfirmSheet } from '@/components/announcements/confirm-sheet';
import { SchedulePicker } from '@/components/feed/schedule-picker';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, EmptyState, SegmentedControl, Text, TextField } from '@/components/ui';
import { allowedTypes, mayRequireAcknowledgement, optionalChannels, smsOffered } from '@/lib/announcements/audience';
import {
  MAX_BODY_CHARS,
  MAX_TITLE_CHARS,
  blockedReason,
  composerFromAnnouncement,
  composerReducer,
  confirmationFor,
  editBlockedReason,
  editFromView,
  contentLanguages,
  initialComposer,
  initialContentLanguage,
  isDirty,
  keyAfterFailure,
  keyFor,
  toDraftRequest,
  toDraftUpdate,
  toMaterialEdit,
  type BlockedReason,
  type Confirmation,
  type ComposerState,
  type PublishedEdit,
  type SubmitKey,
} from '@/lib/announcements/composer';
import { TYPE_HINT, TYPE_LABEL } from '@/lib/announcements/labels';
import { sendProblemKind } from '@/lib/announcements/problems';
import { refreshAnnouncements, replaceAnnouncement, useAnnouncementCapabilities, useAnnouncementQuery } from '@/lib/announcements/queries';
import { createAnnouncement, previewAudience, publish, schedule, unschedule, updateAnnouncement } from '@/lib/announcements/requests';
import { ANNOUNCEMENT_TYPES, isUrgentType, type AnnouncementCapabilities, type AnnouncementType, type AnnouncementView, type PublishMode } from '@/lib/announcements/types';
import { ApiRequestError } from '@/lib/api-error';
import { formatInstant } from '@/lib/calendar/format-instant';
import { usePlaceOfSchool } from '@/lib/country/place';
import { defaultPick, scheduleProblem, toInstant } from '@/lib/feed/schedule';
import { useClock } from '@/lib/feed/use-clock';
import { usePreferences } from '@/lib/preferences/preferences-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';
import { languageLabel } from '@school/i18n';

type Params = { schoolId: string; classId?: string; announcementId?: string };

/**
 * Compose an announcement (teachers: their classes; managers: school, grade levels, classes), edit a draft or scheduled one, or
 * change the text of a published one (title and text only, saying whether the change matters). Publishing needs a connection:
 * there is no queue. What is typed lives in this screen's memory only; leaving with unsent text asks first.
 */
export default function ComposeScreen() {
  const { schoolId, classId, announcementId } = useLocalSearchParams<Params>();
  const { colors } = useAppTheme();
  const t = useTranslations();
  const existing = useAnnouncementQuery(announcementId ? schoolId : undefined, announcementId);
  const published = existing.data?.status === 'PUBLISHED';
  // A published announcement is edited by its author or a manager (the API decides); the capabilities are for new text and drafts.
  const caps = useAnnouncementCapabilities(schoolId && !published ? schoolId : undefined);

  const waiting = !schoolId || (!!announcementId && existing.isPending) || (!published && caps.isPending);
  const centered = { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24, backgroundColor: colors.background } as const;
  if (waiting) {
    return (
      <View style={centered}>
        <ActivityIndicator color={colors.primary} />
      </View>
    );
  }
  if (announcementId && existing.isError) {
    return (
      <View style={centered}>
        <ProblemBanner error={existing.error} fallbackTitle={t('mobile.announcements.loadFailedTitle')} fallbackBody={t('mobile.announcements.loadFailedBody')} onRetry={() => void existing.refetch()} />
      </View>
    );
  }
  if (existing.data && published) return <PublishedEditor view={existing.data} />;
  if (caps.isError || !caps.data || !caps.data.canCompose) {
    return (
      <View style={centered}>
        {caps.isError && !(caps.error instanceof ApiRequestError && (caps.error.status === 403 || caps.error.status === 404)) ? (
          <ProblemBanner error={caps.error} fallbackTitle={t('mobile.announcements.loadFailedTitle')} fallbackBody={t('mobile.announcements.loadFailedBody')} onRetry={() => void caps.refetch()} />
        ) : (
          <EmptyState icon={{ ios: 'lock', android: 'lock' }} title={t('mobile.announcements.compose.notAllowedTitle')} body={t('mobile.announcements.compose.notAllowedBody')}>
            <Button label={t('common.back')} onPress={() => (router.canGoBack() ? router.back() : router.replace('/'))} />
          </EmptyState>
        )}
      </View>
    );
  }
  return <Composer key={existing.data?.id ?? 'new'} schoolId={schoolId} classId={classId} caps={caps.data} existing={existing.data} />;
}

function CancelButton() {
  const t = useTranslations();
  return <Button variant="ghost" fullWidth={false} label={t('common.cancel')} onPress={() => (router.canGoBack() ? router.back() : router.replace('/'))} />;
}

const BLOCKED_KEYS = {
  title_empty: 'mobile.announcements.compose.blocked.title',
  title_too_long: 'mobile.announcements.compose.blocked.titleLong',
  body_empty: 'mobile.announcements.compose.blocked.body',
  body_too_long: 'mobile.announcements.compose.blocked.bodyLong',
  type_not_allowed: 'mobile.announcements.compose.blocked.type',
  schedule_missing: 'mobile.announcements.compose.blocked.schedule',
  audience_no_roles: 'mobile.announcements.audience.noRoles',
  audience_no_targets: 'mobile.announcements.audience.noTargets',
  audience_role_not_allowed: 'mobile.announcements.compose.blocked.audience',
  audience_kind_not_allowed: 'mobile.announcements.compose.blocked.audience',
  audience_class_not_allowed: 'mobile.announcements.compose.blocked.audience',
  audience_too_many: 'mobile.announcements.compose.blocked.audience',
} as const satisfies Record<BlockedReason, string>;

function Composer({ schoolId, classId, caps, existing }: { schoolId: string; classId: string | undefined; caps: AnnouncementCapabilities; existing: AnnouncementView | undefined }) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const { locale, calendar } = usePreferences();
  const queryClient = useQueryClient();
  const clock = useClock(15_000);
  const timeZone = caps.timeZone;

  const start = useMemo<ComposerState | null>(() => (existing ? composerFromAnnouncement(existing, caps) : initialComposer(caps, classId)), [existing, caps, classId]);
  const [state, dispatch] = useReducer(composerReducer, start ?? initialComposer(caps, classId));
  const [opened] = useState(state);
  // The languages of the school's country (D42): what the text can be written in; never a list in code.
  const place = usePlaceOfSchool(schoolId);
  const countryLanguages = place?.languageCodes ?? [];
  const [language, setLanguage] = useState(() =>
    initialContentLanguage({
      saved: existing?.bodyLanguageCode,
      uiLocale: locale,
      countryLanguages,
      defaultLanguage: place?.defaultLanguageCode ?? locale,
    }),
  );
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<unknown>(null);
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [nobody, setNobody] = useState(false);
  const draftId = useRef<string | null>(existing?.id ?? null);
  const [hasDraft, setHasDraft] = useState(!!existing);
  const submitKey = useRef<SubmitKey | null>(null);
  const [guard, setGuard] = useState(true);
  const dirty = isDirty(opened, state);
  const disableGuard = usePreventRemove(dirty && guard, ({ repeat }) => {
    Alert.alert(t('mobile.announcements.compose.discardTitle'), hasDraft ? t('mobile.announcements.compose.discardBodySaved') : t('mobile.announcements.compose.discardBody'), [
      { text: t('mobile.announcements.compose.keepEditing'), style: 'cancel' },
      {
        text: t('mobile.announcements.compose.discard'),
        style: 'destructive',
        onPress: () => {
          setGuard(false);
          repeat();
        },
      },
    ]);
  });

  if (!start) {
    // A draft addressed to chosen people or a campus is edited on the web: the phone cannot show those rows.
    return (
      <SafeAreaView style={{ flex: 1, backgroundColor: colors.background, padding: spacing[5], justifyContent: 'center' }}>
        <EmptyState icon={{ ios: 'desktopcomputer', android: 'computer' }} title={t('mobile.announcements.compose.webOnlyTitle')} body={t('mobile.announcements.compose.webOnlyBody')}>
          <Button label={t('common.back')} onPress={() => (router.canGoBack() ? router.back() : router.replace('/'))} />
        </EmptyState>
      </SafeAreaView>
    );
  }

  const reason = blockedReason(state, caps);
  const scheduleIssue = state.mode === 'SCHEDULE' && state.schedule ? scheduleProblem(state.schedule, timeZone, clock) : null;
  const types = allowedTypes(caps);
  const optional = optionalChannels(caps, state.type);
  const urgent = isUrgentType(state.type);
  const scheduledInstant = state.mode === 'SCHEDULE' && state.schedule ? toInstant(state.schedule, timeZone) : null;
  const whenText = scheduledInstant ? `${formatInstant(scheduledInstant, { locale, calendar, timeZone })} (${timeZone.replace(/_/g, ' ')})` : null;
  const editingScheduled = existing?.status === 'SCHEDULED';

  function setMode(mode: PublishMode) {
    dispatch({ type: 'mode', value: mode });
    if (mode === 'SCHEDULE' && !state.schedule) dispatch({ type: 'schedule', value: defaultPick(Date.now(), timeZone) });
  }

  /** Creates the draft on the server, or updates it. The create call carries an `Idempotency-Key` (reused only for a retry of the same content). */
  async function save(): Promise<AnnouncementView> {
    if (draftId.current) return updateAnnouncement(schoolId, draftId.current, toDraftUpdate(state, caps, language));
    const body = toDraftRequest(state, caps, language);
    submitKey.current = keyFor(submitKey.current, body, randomUUID);
    const created = await createAnnouncement(schoolId, body, submitKey.current.key);
    draftId.current = created.id;
    setHasDraft(true);
    return created;
  }

  function fail(error: unknown) {
    setProblem(error);
    if (submitKey.current) submitKey.current = keyAfterFailure(submitKey.current, error instanceof ApiRequestError ? error.status : undefined);
  }

  async function done(view: AnnouncementView) {
    replaceAnnouncement(queryClient, view);
    await refreshAnnouncements(queryClient);
    submitKey.current = null;
    disableGuard();
    router.replace({ pathname: '/announcements/[announcementId]', params: { announcementId: view.id, schoolId: view.schoolId } });
  }

  /** Step one: keep the text on the server as a draft; a draft ends here, a publish or schedule goes on to the confirmation. */
  async function submit() {
    if (busy || reason || scheduleIssue) return;
    setBusy(true);
    setProblem(null);
    setNobody(false);
    try {
      const saved = await save();
      if (state.mode === 'DRAFT') {
        await done(saved.status === 'SCHEDULED' ? await unschedule(schoolId, saved.id) : saved);
        return;
      }
      const preview = await previewAudience(schoolId, saved.id);
      if (preview.recipients === 0) {
        setNobody(true);
        return;
      }
      setConfirmation(confirmationFor(preview, state));
    } catch (error) {
      fail(error);
    } finally {
      setBusy(false);
    }
  }

  /** Step two, after the person saw how many people it reaches. */
  async function send() {
    const id = draftId.current;
    if (!id || confirming) return;
    setConfirming(true);
    setProblem(null);
    try {
      const scheduledFor = scheduledInstant?.toISOString();
      const view = state.mode === 'SCHEDULE' && scheduledFor ? await schedule(schoolId, id, scheduledFor) : await publish(schoolId, id);
      setConfirmation(null);
      await done(view);
    } catch (error) {
      setProblem(error);
      // 422 `announcement.no_recipients` and 429 `announcement.rate_limited` stay on the screen with the API's own words.
      const kind = sendProblemKind(error);
      if (kind === 'no_recipients' || kind === 'withdrawn' || kind === 'forbidden') setConfirmation(null);
    } finally {
      setConfirming(false);
    }
  }

  const submitLabel = state.mode === 'DRAFT' ? t('mobile.announcements.compose.saveDraft') : state.mode === 'SCHEDULE' ? t('mobile.announcements.compose.review.schedule') : t('mobile.announcements.compose.review.publish');
  const offline = problem instanceof ApiRequestError && problem.status === undefined;
  const kind = problem ? sendProblemKind(problem) : null;

  return (
    <SafeAreaView edges={['bottom']} style={{ flex: 1, backgroundColor: colors.background }}>
      <Stack.Screen options={{ title: existing ? t('mobile.announcements.compose.editDraftTitle') : t('mobile.announcements.compose.title'), headerLeft: () => <CancelButton /> }} />
      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={{ flex: 1 }}>
        <ScrollView contentContainerStyle={{ padding: spacing[5], gap: spacing[5] }} keyboardShouldPersistTaps="handled" keyboardDismissMode="on-drag">
          <View style={{ gap: spacing[2] }}>
            <Text variant="heading">{t('mobile.announcements.compose.typeLabel')}</Text>
            {types.map((type) => (
              <CheckRow
                key={type}
                radio
                label={t(TYPE_LABEL[type])}
                hint={t(TYPE_HINT[type])}
                checked={state.type === type}
                onToggle={() => dispatch({ type: 'kind', value: type as AnnouncementType })}
              />
            ))}
            {types.length < ANNOUNCEMENT_TYPES.length ? <Text variant="caption">{t('mobile.announcements.compose.urgentNeedsRight')}</Text> : null}
          </View>

          <View style={{ gap: spacing[3] }}>
            <TextField
              label={t('mobile.announcements.compose.titleLabel')}
              value={state.title}
              onChangeText={(value) => dispatch({ type: 'title', value })}
              placeholder={t('mobile.announcements.compose.titlePlaceholder')}
              hint={t('mobile.announcements.compose.counter', { used: state.title.length, max: MAX_TITLE_CHARS })}
              error={state.title.length > MAX_TITLE_CHARS ? t('mobile.announcements.compose.blocked.titleLong') : undefined}
              autoCapitalize="sentences"
            />
            <TextField
              label={t('mobile.announcements.compose.bodyLabel')}
              value={state.body}
              onChangeText={(value) => dispatch({ type: 'body', value })}
              multiline
              placeholder={t('mobile.announcements.compose.bodyPlaceholder')}
              hint={t('mobile.announcements.compose.counter', { used: state.body.length, max: MAX_BODY_CHARS })}
              error={state.body.length > MAX_BODY_CHARS ? t('mobile.announcements.compose.blocked.bodyLong') : undefined}
              style={{ minHeight: 160, textAlignVertical: 'top' }}
            />
            <Text variant="caption">{t('mobile.announcements.compose.plainTextNote')}</Text>
            {contentLanguages(countryLanguages, language).length > 1 ? (
              <SegmentedControl
                accessibilityLabel={t('mobile.announcements.compose.languageLabel')}
                value={language}
                onChange={setLanguage}
                options={contentLanguages(countryLanguages, language).map((code) => ({ value: code, label: languageLabel(code, locale) }))}
              />
            ) : null}
          </View>

          <AudiencePicker caps={caps} schoolId={schoolId} value={state.audience} onChange={(value) => dispatch({ type: 'audience', value })} />

          <View style={{ gap: spacing[3] }}>
            <Text variant="heading">{t('mobile.announcements.compose.optionsTitle')}</Text>
            <SwitchRow
              label={t('mobile.announcements.compose.ackLabel')}
              hint={mayRequireAcknowledgement(state.type) ? t('mobile.announcements.compose.ackHint') : t('mobile.announcements.compose.ackNeedsType')}
              value={state.requiresAcknowledgement}
              disabled={!mayRequireAcknowledgement(state.type)}
              onChange={(value) => dispatch({ type: 'acknowledgement', value })}
            />
            <Text variant="caption">{t('mobile.announcements.compose.inAppAlways')}</Text>
            {optional.includes('PUSH') ? (
              <SwitchRow label={t('mobile.announcements.compose.pushLabel')} hint={t('mobile.announcements.compose.pushHint')} value={state.channels.includes('PUSH')} onChange={(on) => dispatch({ type: 'channel', channel: 'PUSH', on })} />
            ) : null}
            {optional.includes('EMAIL') ? (
              <SwitchRow label={t('mobile.announcements.compose.emailLabel')} hint={t('mobile.announcements.compose.emailHint')} value={state.channels.includes('EMAIL')} onChange={(on) => dispatch({ type: 'channel', channel: 'EMAIL', on })} />
            ) : null}
            {smsOffered(caps, state.type) ? (
              <SwitchRow label={t('mobile.announcements.compose.smsLabel')} hint={t('mobile.announcements.compose.smsHint')} value={state.channels.includes('SMS')} onChange={(on) => dispatch({ type: 'channel', channel: 'SMS', on })} />
            ) : null}
            {urgent ? <Text variant="caption">{t('mobile.announcements.compose.urgentChannels')}</Text> : null}
          </View>

          <View style={{ gap: spacing[3] }}>
            <Text variant="heading">{t('mobile.announcements.compose.whenLabel')}</Text>
            <SegmentedControl
              accessibilityLabel={t('mobile.announcements.compose.whenLabel')}
              value={state.mode}
              onChange={setMode}
              options={[
                { value: 'PUBLISH', label: t('mobile.announcements.compose.modeNow') },
                { value: 'SCHEDULE', label: t('mobile.announcements.compose.modeSchedule') },
                { value: 'DRAFT', label: t('mobile.announcements.compose.modeDraft') },
              ]}
            />
            {state.mode === 'SCHEDULE' && state.schedule ? <SchedulePicker value={state.schedule} timeZone={timeZone} onChange={(value) => dispatch({ type: 'schedule', value })} /> : null}
            {state.mode === 'DRAFT' ? <Text variant="caption">{editingScheduled ? t('mobile.announcements.compose.draftUnschedules') : t('mobile.announcements.compose.draftHint')}</Text> : null}
          </View>

          {offline ? (
            <View accessibilityRole="alert" style={{ padding: spacing[4], borderRadius: 16, borderWidth: 1, borderColor: colors.warning, gap: spacing[1] }}>
              <Text variant="label">{t('mobile.announcements.compose.offlineTitle')}</Text>
              <Text>{t('mobile.announcements.compose.offlineBody')}</Text>
            </View>
          ) : problem && !confirmation ? (
            <ProblemBanner error={problem} fallbackTitle={t('mobile.announcements.compose.failedTitle')} fallbackBody={t('mobile.announcements.compose.failedBody')} />
          ) : null}
          {nobody || kind === 'no_recipients' ? (
            <Text tone="destructive" accessibilityLiveRegion="polite">
              {t('mobile.announcements.compose.nobody')}
            </Text>
          ) : null}
          {reason ? (
            <Text variant="caption" accessibilityLiveRegion="polite">
              {t(BLOCKED_KEYS[reason])}
            </Text>
          ) : null}
          {scheduleIssue ? (
            <Text variant="caption" tone="destructive">
              {scheduleIssue === 'past' ? t('mobile.feed.schedule.past') : t('mobile.feed.schedule.tooFar')}
            </Text>
          ) : null}
          <Button label={offline ? t('common.retry') : submitLabel} loading={busy} disabled={!!reason || !!scheduleIssue} onPress={() => void submit()} />
          {hasDraft ? <Text variant="caption">{t('mobile.announcements.compose.draftSavedNote')}</Text> : null}
        </ScrollView>
      </KeyboardAvoidingView>
      <ConfirmSheet visible={!!confirmation} confirmation={confirmation} when={whenText} busy={confirming} problem={confirmation ? problem : null} onConfirm={() => void send()} onClose={() => setConfirmation(null)} />
    </SafeAreaView>
  );
}

function SwitchRow({ label, hint, value, onChange, disabled = false }: { label: string; hint?: string; value: boolean; onChange: (value: boolean) => void; disabled?: boolean }) {
  const { colors, spacing } = useAppTheme();
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[3], minHeight: 44, opacity: disabled ? 0.5 : 1 }}>
      <View style={{ flex: 1 }}>
        <Text variant="label">{label}</Text>
        {hint ? <Text variant="caption">{hint}</Text> : null}
      </View>
      <Switch accessibilityLabel={label} value={value && !disabled} disabled={disabled} onValueChange={onChange} trackColor={{ true: colors.primary, false: colors.border }} />
    </View>
  );
}

/** Changing the text of a published announcement: the editor says whether it matters (people are told and asked again) or is a small correction. */
function PublishedEditor({ view }: { view: AnnouncementView }) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const queryClient = useQueryClient();
  const initial = useMemo<PublishedEdit>(() => editFromView(view), [view]);
  const [edit, setEdit] = useState<PublishedEdit>(initial);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<unknown>(null);
  const [guard, setGuard] = useState(true);
  const dirty = edit.title.trim() !== initial.title.trim() || edit.body.trim() !== initial.body.trim();
  const disableGuard = usePreventRemove(dirty && guard, ({ repeat }) => {
    Alert.alert(t('mobile.announcements.compose.discardTitle'), t('mobile.announcements.edit.discardBody'), [
      { text: t('mobile.announcements.compose.keepEditing'), style: 'cancel' },
      {
        text: t('mobile.announcements.compose.discard'),
        style: 'destructive',
        onPress: () => {
          setGuard(false);
          repeat();
        },
      },
    ]);
  });
  const blocked = editBlockedReason(initial, edit);
  const payload = toMaterialEdit(edit);

  async function submit() {
    if (busy || blocked || !payload) return;
    setBusy(true);
    setProblem(null);
    try {
      const answer = await updateAnnouncement(view.schoolId, view.id, payload);
      replaceAnnouncement(queryClient, answer);
      await refreshAnnouncements(queryClient);
      disableGuard();
      router.back();
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <SafeAreaView edges={['bottom']} style={{ flex: 1, backgroundColor: colors.background }}>
      <Stack.Screen options={{ title: t('mobile.announcements.edit.title'), headerLeft: () => <CancelButton /> }} />
      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={{ flex: 1 }}>
        <ScrollView contentContainerStyle={{ padding: spacing[5], gap: spacing[4] }} keyboardShouldPersistTaps="handled" keyboardDismissMode="on-drag">
          <Text tone="muted">{t('mobile.announcements.edit.intro')}</Text>
          <TextField
            label={t('mobile.announcements.compose.titleLabel')}
            value={edit.title}
            onChangeText={(value) => setEdit({ ...edit, title: value })}
            hint={t('mobile.announcements.compose.counter', { used: edit.title.length, max: MAX_TITLE_CHARS })}
            error={edit.title.length > MAX_TITLE_CHARS ? t('mobile.announcements.compose.blocked.titleLong') : undefined}
          />
          <TextField
            label={t('mobile.announcements.compose.bodyLabel')}
            value={edit.body}
            onChangeText={(value) => setEdit({ ...edit, body: value })}
            multiline
            hint={t('mobile.announcements.compose.counter', { used: edit.body.length, max: MAX_BODY_CHARS })}
            error={edit.body.length > MAX_BODY_CHARS ? t('mobile.announcements.compose.blocked.bodyLong') : undefined}
            style={{ minHeight: 160, textAlignVertical: 'top' }}
          />
          <View style={{ gap: spacing[2] }} accessibilityRole="radiogroup">
            <Text variant="heading">{t('mobile.announcements.edit.materialTitle')}</Text>
            <CheckRow radio label={t('mobile.announcements.edit.materialYes')} hint={t(view.requiresAcknowledgement ? 'mobile.announcements.edit.materialYesAckHint' : 'mobile.announcements.edit.materialYesHint')} checked={edit.material === true} onToggle={() => setEdit({ ...edit, material: true })} />
            <CheckRow radio label={t('mobile.announcements.edit.materialNo')} hint={t('mobile.announcements.edit.materialNoHint')} checked={edit.material === false} onToggle={() => setEdit({ ...edit, material: false })} />
          </View>
          {problem ? <ProblemBanner error={problem} fallbackTitle={t('mobile.announcements.compose.failedTitle')} fallbackBody={t('mobile.announcements.compose.failedBody')} /> : null}
          {blocked && blocked !== 'unchanged' ? (
            <Text variant="caption" accessibilityLiveRegion="polite">
              {blocked === 'material_missing' ? t('mobile.announcements.edit.chooseMaterial') : blocked.startsWith('title') ? t('mobile.announcements.compose.blocked.title') : t('mobile.announcements.compose.blocked.body')}
            </Text>
          ) : null}
          <Button label={t('mobile.announcements.edit.save')} loading={busy} disabled={!!blocked} onPress={() => void submit()} />
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}
