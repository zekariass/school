import { useQueryClient } from '@tanstack/react-query';
import { randomUUID } from 'expo-crypto';
import { Stack, router, useLocalSearchParams, usePreventRemove } from 'expo-router';
import { useMemo, useReducer, useRef, useState } from 'react';
import { ActivityIndicator, Alert, KeyboardAvoidingView, Platform, ScrollView, Switch, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { AttachmentRow } from '@/components/feed/attachment-row';
import { ConfirmNoUnconsented, GatePanel } from '@/components/feed/gate-panel';
import { SchedulePicker } from '@/components/feed/schedule-picker';
import { StudentTagger, type StudentOption } from '@/components/feed/student-picker';
import { usePhotoUploads } from '@/components/media/use-photo-uploads';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, EmptyState, SegmentedControl, Text, TextField } from '@/components/ui';
import { ApiRequestError } from '@/lib/api-error';
import {
  MAX_PHOTOS,
  blockedReason,
  composerFromPost,
  composerReducer,
  initialComposer,
  isDirty,
  keyAfterFailure,
  keyFor,
  needsConfirmation,
  readyPhotos,
  charLimit,
  toContribution,
  toCreatePost,
  toCreateStory,
  toUpdatePost,
  type ComposerKind,
  type ComposerState,
  type SubmitKey,
} from '@/lib/feed/composer';
import { gateProblemOf, withoutStudents, type GateProblem } from '@/lib/feed/gate';
import { refreshFeeds, replacePost, useFeedCapabilities, usePostQuery } from '@/lib/feed/queries';
import {
  createClassPost,
  createClassStory,
  createContribution,
  createSchoolPost,
  createSchoolStory,
  publishPost,
  schedulePost,
  unschedulePost,
  updatePost,
} from '@/lib/feed/requests';
import { defaultPick, scheduleProblem, toInstant } from '@/lib/feed/schedule';
import { useClock } from '@/lib/feed/use-clock';
import type { FeedCapabilities, PostView, PublishMode } from '@/lib/feed/types';
import { useMyChildren } from '@/lib/family/queries';
import { useRoster, useStudentSearch } from '@/lib/people/queries';
import { usePreferences } from '@/lib/preferences/preferences-provider';
import { useSession } from '@/lib/session/session-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

type Params = { kind?: string; schoolId: string; classId?: string; postId?: string };

/**
 * The composer: a post (publish now, schedule in school time, or keep as a draft), a story, or a family contribution that a
 * teacher approves first. Posting needs a connection, so there is no offline queue: the text and the chosen photos live in this
 * screen's memory only (nothing is saved to the phone), they survive every error and are asked about before the screen closes.
 */
export default function ComposeScreen() {
  const { kind: kindParam, schoolId, classId, postId } = useLocalSearchParams<Params>();
  const { colors } = useAppTheme();
  const kind: ComposerKind = kindParam === 'story' || kindParam === 'contribution' ? kindParam : 'post';
  const caps = useFeedCapabilities(schoolId, classId ?? null);
  const existing = usePostQuery(postId ? schoolId : undefined, postId);

  const waiting = caps.isPending || (!!postId && existing.isPending);
  if (waiting || !schoolId) {
    return (
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.background }}>
        <ActivityIndicator color={colors.primary} />
      </View>
    );
  }
  return (
    <Composer
      key={existing.data?.id ?? 'new'}
      kind={existing.data?.contribution ? 'contribution' : kind}
      schoolId={schoolId}
      classId={classId}
      caps={caps.data}
      existing={existing.data}
    />
  );
}

function Composer({ kind, schoolId, classId, caps, existing }: { kind: ComposerKind; schoolId: string; classId: string | undefined; caps: FeedCapabilities | undefined; existing: PostView | undefined }) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const { locale } = usePreferences();
  const { activeRole } = useSession();
  const queryClient = useQueryClient();
  const timeZone = caps?.timeZone ?? 'UTC';
  const clock = useClock(15_000);

  const [state, dispatch] = useReducer(composerReducer, undefined, () => (existing ? composerFromPost(existing) : initialComposer(kind, caps?.commentsDefaultEnabled ?? true)));
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<unknown>(null);
  const [gate, setGate] = useState<GateProblem | null>(null);
  const [pickerMessage, setPickerMessage] = useState<string | null>(null);
  const [done, setDone] = useState<'contribution' | null>(null);
  const submitKey = useRef<SubmitKey | null>(null);

  const photoTarget = useMemo(
    () => ({ purpose: kind === 'story' ? ('STORY' as const) : ('POST' as const), context: classId ? { type: 'CLASS' as const, id: classId } : { type: 'SCHOOL' as const, id: schoolId } }),
    [kind, classId, schoolId],
  );
  const photos = usePhotoUploads({ dispatch, target: photoTarget });

  // --- who can be tagged -------------------------------------------------------------------------
  const roster = useRoster(kind === 'contribution' ? undefined : schoolId, classId);
  const children = useMyChildren(kind === 'contribution' && activeRole === 'guardian');
  const [search, setSearch] = useState('');
  const schoolStudents = useStudentSearch(!classId && kind !== 'contribution' ? schoolId : undefined, search);
  const options: StudentOption[] = useMemo(() => {
    if (kind === 'contribution') {
      // A parent tags only their own children of this class (the API tells which); a student tags nobody.
      if (activeRole !== 'guardian') return [];
      const allowed = new Set(caps?.contributionStudentIds ?? []);
      return (children.data?.children ?? []).filter((child) => allowed.has(child.studentId)).map((child) => ({ id: child.studentId, name: child.displayName }));
    }
    if (classId) return (roster.data ?? []).map((entry) => ({ id: entry.id, name: entry.name }));
    const found = new Map<string, StudentOption>();
    for (const page of schoolStudents.data?.pages ?? []) for (const student of page.items) found.set(student.id, { id: student.id, name: student.displayName });
    // Students already tagged stay listed even when a search no longer finds them.
    for (const tag of existing?.tags ?? []) if (!found.has(tag.studentId)) found.set(tag.studentId, { id: tag.studentId, name: tag.displayName });
    return [...found.values()];
  }, [activeRole, caps?.contributionStudentIds, children.data, classId, existing?.tags, kind, roster.data, schoolStudents.data]);
  const known = useMemo(() => new Map(options.map((option) => [option.id, option.name])), [options]);

  // --- leaving -----------------------------------------------------------------------------------
  // Nothing is saved on the phone, so leaving with something typed or picked asks first.
  const [opened] = useState(state);
  const dirty = isDirty(opened, state);
  const [guard, setGuard] = useState(true);
  const disableGuard = usePreventRemove(dirty && guard, ({ repeat }) => {
    Alert.alert(t('mobile.feed.compose.discardTitle'), t('mobile.feed.compose.discardBody'), [
      { text: t('mobile.feed.compose.keepEditing'), style: 'cancel' },
      {
        text: t('mobile.feed.compose.discard'),
        style: 'destructive',
        onPress: () => {
          setGuard(false);
          repeat();
        },
      },
    ]);
  });

  const limit = charLimit(kind);
  const editingLive = !!existing && existing.status === 'PUBLISHED';
  const showModes = kind === 'post' && !editingLive && !(existing && existing.status === 'ARCHIVED');
  const confirmShown = needsConfirmation(state);
  const confirmAsked = gate?.kind === 'confirmation_required';
  const consentGate = gate?.kind === 'consent_missing' ? gate : null;
  const reason = blockedReason(state);
  const offline = problem instanceof ApiRequestError && problem.status === undefined;
  const scheduleIssue = state.mode === 'SCHEDULE' && state.schedule ? scheduleProblem(state.schedule, timeZone, clock) : null;

  function setMode(mode: PublishMode) {
    dispatch({ type: 'mode', value: mode });
    if (mode === 'SCHEDULE' && !state.schedule) dispatch({ type: 'schedule', value: defaultPick(Date.now(), timeZone) });
  }

  async function submit() {
    if (busy || reason) return;
    setBusy(true);
    setProblem(null);
    setGate(null);
    const start: ComposerState = state;
    try {
      const result = await send(start);
      photos.adopt(readyPhotos(start).map((photo) => photo.mediaId!));
      if (result) replacePost(queryClient, result);
      await refreshFeeds(queryClient);
      submitKey.current = null;
      disableGuard();
      if (kind === 'contribution') setDone('contribution');
      else router.back();
    } catch (error) {
      const found = gateProblemOf(error);
      if (found) setGate(found);
      else setProblem(error);
      if (submitKey.current) submitKey.current = keyAfterFailure(submitKey.current, error instanceof ApiRequestError ? error.status : undefined);
    } finally {
      setBusy(false);
    }
  }

  /** The API calls of one send. A story or a post returns what the lists should now hold. */
  async function send(current: ComposerState): Promise<PostView | null> {
    if (kind === 'story') {
      const body = toCreateStory(current, locale);
      submitKey.current = keyFor(submitKey.current, body, randomUUID);
      const key = { idempotencyKey: submitKey.current.key };
      if (classId) await createClassStory(schoolId, classId, body, key);
      else await createSchoolStory(schoolId, body, key);
      return null;
    }
    const scheduledFor = current.mode === 'SCHEDULE' && current.schedule ? toInstant(current.schedule, timeZone).toISOString() : undefined;
    if (current.mode === 'SCHEDULE' && !scheduledFor) throw new ApiRequestError(400);
    if (existing) return saveExisting(existing, current, scheduledFor);
    if (kind === 'contribution') {
      const body = toContribution(current, locale);
      submitKey.current = keyFor(submitKey.current, body, randomUUID);
      return createContribution(schoolId, classId!, body, { idempotencyKey: submitKey.current.key });
    }
    const body = toCreatePost(current, locale, scheduledFor);
    submitKey.current = keyFor(submitKey.current, body, randomUUID);
    const key = { idempotencyKey: submitKey.current.key };
    return classId ? createClassPost(schoolId, classId, body, key) : createSchoolPost(schoolId, body, key);
  }

  /** Edit: save the changes, then move the post to the state chosen (publish, schedule, draft). */
  async function saveExisting(post: PostView, current: ComposerState, scheduledFor: string | undefined): Promise<PostView> {
    const confirm = needsConfirmation(current) && current.confirmNoUnconsented;
    let saved = await updatePost(schoolId, post.id, toUpdatePost(current, locale));
    if (!post.contribution && (post.status === 'DRAFT' || post.status === 'SCHEDULED' || post.status === 'ARCHIVED')) {
      if (current.mode === 'PUBLISH') saved = await publishPost(schoolId, post.id, confirm);
      else if (current.mode === 'SCHEDULE' && scheduledFor) saved = await schedulePost(schoolId, post.id, scheduledFor, confirm);
      else if (current.mode === 'DRAFT' && post.status === 'SCHEDULED') saved = await unschedulePost(schoolId, post.id);
    }
    return saved;
  }

  const submitLabel =
    kind === 'story'
      ? t('mobile.feed.compose.shareStory')
      : kind === 'contribution'
        ? caps?.contributionNeedsApproval === false ? t('mobile.feed.compose.share') : t('mobile.feed.compose.sendForApproval')
        : editingLive
          ? t('mobile.feed.compose.saveChanges')
          : state.mode === 'SCHEDULE'
            ? t('mobile.feed.compose.schedule')
            : state.mode === 'DRAFT'
              ? t('mobile.feed.compose.saveDraft')
              : t('mobile.feed.compose.post');

  const blockedText =
    reason === 'uploading'
      ? t('mobile.feed.compose.waitUploads')
      : reason === 'failed_upload'
        ? t('mobile.feed.compose.fixUploads')
        : reason === 'too_long'
          ? t('mobile.feed.compose.tooLong', { max: limit })
          : reason === 'schedule_missing'
            ? t('mobile.feed.schedule.invalid')
            : null;

  if (done) {
    return (
      <SafeAreaView style={{ flex: 1, backgroundColor: colors.background, padding: spacing[5], justifyContent: 'center' }}>
        <EmptyState
          icon={{ ios: 'checkmark.circle.fill', android: 'check_circle' }}
          title={caps?.contributionNeedsApproval === false ? t('mobile.feed.contribute.sharedTitle') : t('mobile.feed.contribute.sentTitle')}
          body={caps?.contributionNeedsApproval === false ? t('mobile.feed.contribute.sharedBody') : t('mobile.feed.contribute.sentBody')}>
          <Button label={t('common.done')} onPress={() => router.back()} />
        </EmptyState>
      </SafeAreaView>
    );
  }

  const title = existing ? t('mobile.feed.compose.editTitle') : kind === 'story' ? t('mobile.feed.compose.storyTitle') : kind === 'contribution' ? t('mobile.feed.compose.contributionTitle') : t('mobile.feed.compose.title');

  return (
    <SafeAreaView edges={['bottom']} style={{ flex: 1, backgroundColor: colors.background }}>
      <Stack.Screen options={{ title, headerLeft: () => <CancelButton /> }} />
      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={{ flex: 1 }}>
        <ScrollView contentContainerStyle={{ padding: spacing[5], gap: spacing[4] }} keyboardShouldPersistTaps="handled" keyboardDismissMode="on-drag">
          {kind === 'contribution' ? (
            <View style={{ padding: spacing[4], borderRadius: 16, backgroundColor: colors.accent }}>
              <Text style={{ color: colors.accentForeground }}>{caps?.contributionNeedsApproval === false ? t('mobile.feed.contribute.noApproval') : t('mobile.feed.contribute.needsApproval')}</Text>
            </View>
          ) : null}

          <TextField
            label={kind === 'story' ? t('mobile.feed.compose.captionLabel') : t('mobile.feed.compose.bodyLabel')}
            value={state.body}
            onChangeText={(value) => dispatch({ type: 'body', value })}
            multiline
            placeholder={kind === 'story' ? t('mobile.feed.compose.captionPlaceholder') : t('mobile.feed.compose.bodyPlaceholder')}
            hint={t('mobile.feed.compose.counter', { used: state.body.length, max: limit })}
            error={state.body.length > limit ? t('mobile.feed.compose.tooLong', { max: limit }) : undefined}
            style={{ minHeight: 120, textAlignVertical: 'top' }}
          />

          <View style={{ gap: spacing[3] }}>
            <Text variant="heading">{t('mobile.feed.compose.photos')}</Text>
            <Text variant="caption">{kind === 'story' ? t('mobile.feed.compose.storyPhotoHint') : t('mobile.feed.compose.photosHint', { max: MAX_PHOTOS })}</Text>
            {state.attachments.map((attachment, index) => (
              <AttachmentRow
                key={attachment.key}
                attachment={attachment}
                index={index}
                total={state.attachments.length}
                onCaption={(value) => dispatch({ type: 'caption', key: attachment.key, value })}
                onRetry={() => photos.retry(attachment.key)}
                onRemove={() => photos.remove(attachment)}
                onMove={(direction) => dispatch({ type: 'move', key: attachment.key, direction })}
              />
            ))}
            {state.attachments.length < (kind === 'story' ? 1 : MAX_PHOTOS) ? (
              <View style={{ flexDirection: 'row', gap: spacing[2] }}>
                <View style={{ flex: 1 }}>
                  <Button variant="ghost" label={t('media.picker.takePhoto')} onPress={() => void photos.add('camera').then(setPickerMessage)} />
                </View>
                <View style={{ flex: 1 }}>
                  <Button variant="ghost" label={t('media.picker.chooseFromLibrary')} onPress={() => void photos.add('library').then(setPickerMessage)} />
                </View>
              </View>
            ) : null}
            {pickerMessage ? (
              <Text variant="caption" tone="destructive" accessibilityLiveRegion="polite">
                {pickerMessage}
              </Text>
            ) : null}
          </View>

          {options.length > 0 || kind !== 'contribution' ? (
            <StudentTagger
              title={t('mobile.feed.tags.title')}
              hint={kind === 'contribution' ? t('mobile.feed.tags.hintContribution') : t('mobile.feed.tags.hint')}
              options={options}
              selected={state.studentIds}
              onToggle={(studentId) => dispatch({ type: 'toggleStudent', studentId })}
              loading={classId ? roster.isPending : schoolStudents.isFetching}
              {...(!classId && kind !== 'contribution' ? { onSearch: setSearch } : {})}
            />
          ) : null}

          <GatePanel
            gate={consentGate}
            known={known}
            onRemoveTags={() => {
              if (consentGate) dispatch({ type: 'setStudents', studentIds: withoutStudents(state.studentIds, consentGate.students) });
              setGate(null);
            }}
          />

          {confirmShown ? <ConfirmNoUnconsented checked={state.confirmNoUnconsented} onChange={(value) => dispatch({ type: 'confirm', value })} highlight={confirmAsked} /> : null}

          {kind === 'post' ? (
            <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing[3] }}>
              <View style={{ flex: 1 }}>
                <Text variant="label">{t('mobile.feed.compose.comments')}</Text>
                <Text variant="caption">{t('mobile.feed.compose.commentsHint')}</Text>
              </View>
              <Switch
                accessibilityLabel={t('mobile.feed.compose.comments')}
                value={state.commentsEnabled}
                onValueChange={(value) => dispatch({ type: 'comments', value })}
                trackColor={{ true: colors.primary, false: colors.border }}
              />
            </View>
          ) : null}

          {showModes ? (
            <View style={{ gap: spacing[3] }}>
              <SegmentedControl
                accessibilityLabel={t('mobile.feed.compose.when')}
                value={state.mode}
                onChange={setMode}
                options={[
                  { value: 'PUBLISH', label: t('mobile.feed.compose.modeNow') },
                  { value: 'SCHEDULE', label: t('mobile.feed.compose.modeSchedule') },
                  { value: 'DRAFT', label: t('mobile.feed.compose.modeDraft') },
                ]}
              />
              {state.mode === 'SCHEDULE' && state.schedule ? <SchedulePicker value={state.schedule} timeZone={timeZone} onChange={(value) => dispatch({ type: 'schedule', value })} /> : null}
              {state.mode === 'DRAFT' ? <Text variant="caption">{t('mobile.feed.compose.draftHint')}</Text> : null}
            </View>
          ) : null}

          {offline ? (
            <View accessibilityRole="alert" style={{ padding: spacing[4], borderRadius: 16, borderWidth: 1, borderColor: colors.warning, gap: spacing[1] }}>
              <Text variant="label">{t('mobile.feed.compose.offlineTitle')}</Text>
              <Text>{t('mobile.feed.compose.offlineBody')}</Text>
            </View>
          ) : problem ? (
            <ProblemBanner error={problem} fallbackTitle={t('mobile.feed.compose.failedTitle')} fallbackBody={t('mobile.feed.compose.failedBody')} />
          ) : null}

          {blockedText ? (
            <Text variant="caption" accessibilityLiveRegion="polite">
              {blockedText}
            </Text>
          ) : null}
          {state.mode === 'SCHEDULE' && scheduleIssue ? <Text variant="caption" tone="destructive">{scheduleIssue === 'past' ? t('mobile.feed.schedule.past') : t('mobile.feed.schedule.tooFar')}</Text> : null}
          <Button label={offline ? t('common.retry') : submitLabel} loading={busy} disabled={!!reason || !!scheduleIssue} onPress={() => void submit()} />
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

function CancelButton() {
  const t = useTranslations();
  return <Button variant="ghost" fullWidth={false} label={t('common.cancel')} onPress={() => (router.canGoBack() ? router.back() : router.replace('/'))} />;
}
