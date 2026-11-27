import { useQueryClient } from '@tanstack/react-query';
import { randomUUID } from 'expo-crypto';
import { Stack, router, useLocalSearchParams, usePreventRemove } from 'expo-router';
import { useMemo, useReducer, useRef, useState } from 'react';
import { ActivityIndicator, Alert, KeyboardAvoidingView, Platform, ScrollView, Switch, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { StudentTagger } from '@/components/feed/student-picker';
import { Chips } from '@/components/feed/chips';
import { usePhotoUploads } from '@/components/media/use-photo-uploads';
import { ConfirmPanel, ConsentPanel } from '@/components/portfolio/consent-panels';
import { FileRow } from '@/components/portfolio/file-row';
import { NeedsConnection } from '@/components/portfolio/needs-connection';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, EmptyState, Text, TextField } from '@/components/ui';
import { ApiRequestError } from '@/lib/api-error';
import { readyAttachments } from '@/lib/media/attachments';
import { useMyClasses, useRoster } from '@/lib/people/queries';
import {
  MAX_FILES,
  blockedReason,
  composerFromItem,
  composerReducer,
  initialComposer,
  isDirty,
  keyAfterFailure,
  keyFor,
  needsConfirmation,
  overLimitField,
  toCreateRequest,
  toQuickCapture,
  toUpdateRequest,
  type ComposerKind,
  type ComposerState,
  type SubmitKey,
} from '@/lib/portfolio/composer';
import { gateProblemOf, withoutStudents, type GateProblem } from '@/lib/portfolio/gate';
import { ITEM_TYPE_HINT, ITEM_TYPE_LABEL, VISIBILITY_HINT, VISIBILITY_LABEL } from '@/lib/portfolio/labels';
import { LIMITS, creatableTypes, defaultVisibility, needsConnection, takesPhotos, visibilityChoices } from '@/lib/portfolio/model';
import { refreshPortfolio, replaceItem, useItemQuery, usePortfolioCapabilities } from '@/lib/portfolio/queries';
import { createItem, quickCapture, submitItem, updateItem } from '@/lib/portfolio/requests';
import type { PortfolioCapabilities, PortfolioItem, PortfolioItemType } from '@/lib/portfolio/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

type Params = { schoolId: string; itemId?: string; classId?: string; studentId?: string };

/**
 * The portfolio composer, in three forms: a student's new item, a student's edit of a draft or of an item the teacher asked
 * changes for, and a teacher's quick capture for a student of a class. Nothing is saved on the phone: the words and the
 * pictures live in this screen's memory only, survive every error (including the consent gate) and are asked about before the
 * screen closes. The controls come from the API: `capabilities` for what may be created and the audience cap, the item's own
 * `capabilities` for editing and submitting.
 */
export default function ComposeScreen() {
  const { schoolId, itemId, classId, studentId } = useLocalSearchParams<Params>();
  const { colors } = useAppTheme();
  const caps = usePortfolioCapabilities(schoolId);
  const existing = useItemQuery(itemId ? schoolId : undefined, itemId);
  // A student's new item preselects the class when there is only one, so the list of classes is waited for.
  const myClasses = useMyClasses();
  const waiting = caps.isPending || (!!itemId && existing.isPending) || (!itemId && !studentId && myClasses.isPending);
  const failure = caps.error ?? (itemId ? existing.error : null);
  if (!schoolId || waiting || !caps.data) {
    return (
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.background, padding: 20 }}>
        {failure ? (
          <FailedToOpen error={failure} onRetry={() => void Promise.all([caps.refetch(), existing.refetch()])} />
        ) : (
          <ActivityIndicator color={colors.primary} />
        )}
      </View>
    );
  }
  return (
    <Composer
      key={existing.data?.id ?? `new-${studentId ?? 'me'}`}
      schoolId={schoolId}
      classId={classId}
      studentId={studentId}
      caps={caps.data}
      existing={existing.data}
    />
  );
}

function FailedToOpen({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const t = useTranslations();
  if (needsConnection(error)) return <NeedsConnection onRetry={onRetry} />;
  return <ProblemBanner error={error} fallbackTitle={t('mobile.portfolio.loadFailedTitle')} fallbackBody={t('mobile.portfolio.loadFailedBody')} onRetry={onRetry} />;
}

function Composer({
  schoolId,
  classId: classParam,
  studentId,
  caps,
  existing,
}: {
  schoolId: string;
  classId: string | undefined;
  studentId: string | undefined;
  caps: PortfolioCapabilities;
  existing: PortfolioItem | undefined;
}) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const queryClient = useQueryClient();
  const kind: ComposerKind = studentId && classParam ? 'capture' : 'student';
  const editing = !!existing;
  const types = creatableTypes(caps.itemTypes);

  const myClasses = useMyClasses();
  const classes = useMemo(
    () => (myClasses.data ?? []).filter((cls) => cls.role === 'STUDENT' && cls.status === 'ACTIVE' && cls.schoolId === schoolId),
    [myClasses.data, schoolId],
  );
  const allowed = kind === 'capture' ? caps.captureClassIds.includes(classParam!) : editing ? existing.capabilities.canEdit === true : caps.studentMayCreate === true;

  const [state, dispatch] = useReducer(composerReducer, undefined, () =>
    existing
      ? composerFromItem(existing)
      : initialComposer({
          kind,
          itemType: types[0] ?? 'TEXT',
          visibility: defaultVisibility(caps.maxVisibility),
          // A student in exactly one class: that class (the API would pick it anyway). Several: the student chooses.
          classId: kind === 'capture' ? classParam! : classes.length === 1 ? classes[0]!.id : null,
          subjectId: kind === 'capture' ? null : classes.length === 1 ? (classes[0]!.subjectId ?? null) : null,
        }),
  );
  const [opened] = useState(state);
  const [busy, setBusy] = useState<'draft' | 'submit' | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [gate, setGate] = useState<GateProblem | null>(null);
  const [pickerMessage, setPickerMessage] = useState<string | null>(null);
  const submitKey = useRef<SubmitKey | null>(null);

  const target = useMemo(() => ({ purpose: 'PORTFOLIO' as const, context: { type: 'SCHOOL' as const, id: schoolId } }), [schoolId]);
  const photos = usePhotoUploads({ dispatch, target });

  // --- who can be tagged (a teacher's capture) -------------------------------------------------------
  const roster = useRoster(kind === 'capture' ? schoolId : undefined, kind === 'capture' ? classParam : undefined);
  const options = useMemo(() => (roster.data ?? []).filter((entry) => entry.id !== studentId).map((entry) => ({ id: entry.id, name: entry.name })), [roster.data, studentId]);
  const known = useMemo(() => new Map((roster.data ?? []).map((entry) => [entry.id, entry.name])), [roster.data]);
  const subjectName = kind === 'capture' ? known.get(studentId ?? '') : undefined;

  // --- leaving -----------------------------------------------------------------------------------
  const dirty = isDirty(opened, state);
  const [guard, setGuard] = useState(true);
  const disableGuard = usePreventRemove(dirty && guard, ({ repeat }) => {
    Alert.alert(t('mobile.portfolio.compose.discardTitle'), t('mobile.portfolio.compose.discardBody'), [
      { text: t('mobile.portfolio.compose.keepEditing'), style: 'cancel' },
      {
        text: t('mobile.portfolio.compose.discard'),
        style: 'destructive',
        onPress: () => {
          setGuard(false);
          repeat();
        },
      },
    ]);
  });

  const photoType = takesPhotos(state.itemType);
  const confirmShown = needsConfirmation(state);
  const confirmAsked = gate?.kind === 'confirmation_required';
  const consentGate = gate?.kind === 'consent_missing' ? gate : null;
  const offline = needsConnection(problem);
  const over = overLimitField(state);
  const draftBlock = blockedReason(state, 'draft');
  const submitBlock = blockedReason(state, 'submit');
  const canSubmit = editing ? existing.capabilities.canSubmit === true : kind === 'student';

  async function send(mode: 'draft' | 'submit') {
    if (busy) return;
    setBusy(mode);
    setProblem(null);
    setGate(null);
    const sent: ComposerState = state;
    try {
      let result: PortfolioItem;
      if (existing) {
        result = await updateItem(schoolId, existing.id, toUpdateRequest(sent, opened, existing.mediaConfirmed ?? false));
        if (mode === 'submit') result = await submitItem(schoolId, existing.id);
      } else if (kind === 'capture') {
        const body = toQuickCapture(sent, studentId!);
        submitKey.current = keyFor(submitKey.current, body, randomUUID);
        result = await quickCapture(schoolId, classParam!, body, { idempotencyKey: submitKey.current.key });
      } else {
        const body = toCreateRequest(sent, mode === 'submit' ? 'SUBMIT' : 'DRAFT');
        submitKey.current = keyFor(submitKey.current, body, randomUUID);
        result = await createItem(schoolId, body, { idempotencyKey: submitKey.current.key });
      }
      photos.adopt(readyAttachments(sent.attachments).map((file) => file.mediaId!));
      replaceItem(queryClient, result);
      await refreshPortfolio(queryClient);
      submitKey.current = null;
      disableGuard();
      router.back();
    } catch (error) {
      const found = gateProblemOf(error);
      if (found) setGate(found);
      else setProblem(error);
      if (submitKey.current) submitKey.current = keyAfterFailure(submitKey.current, error instanceof ApiRequestError ? error.status : undefined);
    } finally {
      setBusy(null);
    }
  }

  const title = editing ? t('mobile.portfolio.compose.editTitle') : kind === 'capture' ? t('mobile.portfolio.compose.captureTitle') : t('mobile.portfolio.compose.title');

  if (!allowed) {
    return (
      <SafeAreaView style={{ flex: 1, backgroundColor: colors.background, padding: spacing[5], justifyContent: 'center' }}>
        <Stack.Screen options={{ title }} />
        <EmptyState
          icon={{ ios: 'lock', android: 'lock' }}
          title={t('mobile.portfolio.compose.notAllowedTitle')}
          body={kind === 'capture' ? t('mobile.portfolio.compose.notAllowedCapture') : editing ? t('mobile.portfolio.compose.notAllowedEdit') : t('mobile.portfolio.compose.notAllowedCreate')}>
          <Button label={t('common.back')} onPress={() => router.back()} />
        </EmptyState>
      </SafeAreaView>
    );
  }

  const blockedText =
    draftBlock === 'uploading'
      ? t('mobile.portfolio.compose.waitUploads')
      : draftBlock === 'failed_upload'
        ? t('mobile.portfolio.compose.fixUploads')
        : draftBlock === 'title_missing'
          ? t('mobile.portfolio.compose.needTitle')
          : draftBlock === 'too_long'
            ? t('mobile.portfolio.compose.tooLong', { max: over === 'title' ? LIMITS.title : over === 'text' ? LIMITS.text : LIMITS.description })
            : null;
  const submitText = submitBlock === 'text_missing' ? t('mobile.portfolio.compose.needText') : submitBlock === 'photo_missing' ? t('mobile.portfolio.compose.needPhoto') : null;

  const showClassChips = kind === 'student' && !editing && classes.length > 1;

  return (
    <SafeAreaView edges={['bottom']} style={{ flex: 1, backgroundColor: colors.background }}>
      <Stack.Screen options={{ title, headerLeft: () => <CancelButton /> }} />
      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={{ flex: 1 }}>
        <ScrollView contentContainerStyle={{ padding: spacing[5], gap: spacing[4] }} keyboardShouldPersistTaps="handled" keyboardDismissMode="on-drag">
          {kind === 'capture' ? (
            <View style={{ padding: spacing[4], borderRadius: 16, backgroundColor: colors.accent }}>
              <Text style={{ color: colors.accentForeground }}>{subjectName ? t('mobile.portfolio.compose.captureFor', { name: subjectName }) : t('mobile.portfolio.compose.captureForStudent')}</Text>
              <Text variant="caption" style={{ color: colors.accentForeground }}>
                {t('mobile.portfolio.compose.captureNote')}
              </Text>
            </View>
          ) : null}

          {!editing && types.length > 1 ? (
            <View style={{ gap: spacing[2] }}>
              <Text variant="label">{t('mobile.portfolio.compose.kind')}</Text>
              <Chips<PortfolioItemType>
                accessibilityLabel={t('mobile.portfolio.compose.kind')}
                value={state.itemType}
                onChange={(value) => dispatch({ type: 'itemType', value })}
                chips={types.map((type) => ({ value: type, label: t(ITEM_TYPE_LABEL[type]) }))}
              />
              <Text variant="caption">{t(ITEM_TYPE_HINT[state.itemType])}</Text>
            </View>
          ) : null}

          <TextField
            label={t('mobile.portfolio.compose.titleLabel')}
            value={state.title}
            onChangeText={(value) => dispatch({ type: 'title', value })}
            placeholder={t('mobile.portfolio.compose.titlePlaceholder')}
            hint={t('mobile.portfolio.compose.counter', { used: [...state.title].length, max: LIMITS.title })}
            error={over === 'title' ? t('mobile.portfolio.compose.tooLong', { max: LIMITS.title }) : undefined}
          />

          {state.itemType === 'TEXT' ? (
            <TextField
              label={t('mobile.portfolio.compose.textLabel')}
              value={state.textContent}
              onChangeText={(value) => dispatch({ type: 'text', value })}
              multiline
              placeholder={t('mobile.portfolio.compose.textPlaceholder')}
              hint={t('mobile.portfolio.compose.counter', { used: [...state.textContent].length, max: LIMITS.text })}
              error={over === 'text' ? t('mobile.portfolio.compose.tooLong', { max: LIMITS.text }) : undefined}
              style={{ minHeight: 160, textAlignVertical: 'top' }}
            />
          ) : null}

          {photoType ? (
            <View style={{ gap: spacing[3] }}>
              <Text variant="heading">{t('mobile.portfolio.compose.photos')}</Text>
              <Text variant="caption">{state.itemType === 'SCAN' ? t('mobile.portfolio.compose.scanTip') : t('mobile.portfolio.compose.photosHint', { max: MAX_FILES })}</Text>
              {state.attachments.map((attachment, index) => (
                <FileRow
                  key={attachment.key}
                  attachment={attachment}
                  index={index}
                  total={state.attachments.length}
                  onRetry={() => photos.retry(attachment.key)}
                  onRemove={() => photos.remove(attachment)}
                  onMove={(direction) => dispatch({ type: 'move', key: attachment.key, direction })}
                />
              ))}
              {state.attachments.length < MAX_FILES ? (
                <View style={{ flexDirection: 'row', gap: spacing[2] }}>
                  <View style={{ flex: 1 }}>
                    <Button variant="ghost" label={state.itemType === 'SCAN' ? t('mobile.portfolio.compose.scanPage') : t('media.picker.takePhoto')} onPress={() => void photos.add('camera').then(setPickerMessage)} />
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
          ) : null}

          <TextField
            label={t('mobile.portfolio.compose.descriptionLabel')}
            value={state.description}
            onChangeText={(value) => dispatch({ type: 'description', value })}
            multiline
            placeholder={t('mobile.portfolio.compose.descriptionPlaceholder')}
            hint={t('mobile.portfolio.compose.counter', { used: [...state.description].length, max: LIMITS.description })}
            error={over === 'description' ? t('mobile.portfolio.compose.tooLong', { max: LIMITS.description }) : undefined}
            style={{ minHeight: 88, textAlignVertical: 'top' }}
          />

          <TextField
            label={t('mobile.portfolio.compose.reflectionLabel')}
            value={state.reflection}
            onChangeText={(value) => dispatch({ type: 'reflection', value })}
            multiline
            placeholder={t('mobile.portfolio.compose.reflectionPlaceholder')}
            hint={t('mobile.portfolio.compose.counter', { used: [...state.reflection].length, max: LIMITS.reflection })}
            error={over === 'reflection' ? t('mobile.portfolio.compose.tooLong', { max: LIMITS.reflection }) : undefined}
            style={{ minHeight: 88, textAlignVertical: 'top' }}
          />

          {showClassChips ? (
            <View style={{ gap: spacing[2] }}>
              <Text variant="label">{t('mobile.portfolio.compose.classLabel')}</Text>
              <Text variant="caption">{t('mobile.portfolio.compose.classHint')}</Text>
              <Chips
                accessibilityLabel={t('mobile.portfolio.compose.classLabel')}
                value={state.classId ?? ''}
                onChange={(value) => {
                  const cls = classes.find((candidate) => candidate.id === value);
                  dispatch({ type: 'class', classId: cls?.id ?? null, subjectId: cls?.subjectId ?? null });
                }}
                chips={[...classes.map((cls) => ({ value: cls.id, label: cls.name })), { value: '', label: t('mobile.portfolio.compose.noClass') }]}
              />
            </View>
          ) : null}

          <View style={{ gap: spacing[2] }}>
            <Text variant="label">{t('mobile.portfolio.compose.audienceLabel')}</Text>
            <Chips
              accessibilityLabel={t('mobile.portfolio.compose.audienceLabel')}
              value={state.visibility}
              onChange={(value) => dispatch({ type: 'visibility', value })}
              chips={visibilityChoices(caps.maxVisibility).map((visibility) => ({ value: visibility, label: t(VISIBILITY_LABEL[visibility]) }))}
            />
            <Text variant="caption">{t(VISIBILITY_HINT[state.visibility])}</Text>
          </View>

          {photoType ? (
            <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing[3] }}>
              <View style={{ flex: 1 }}>
                <Text variant="label">{t('mobile.portfolio.compose.others')}</Text>
                <Text variant="caption">{kind === 'capture' ? t('mobile.portfolio.compose.othersHintCapture') : t('mobile.portfolio.compose.othersHint')}</Text>
              </View>
              <Switch
                accessibilityLabel={t('mobile.portfolio.compose.others')}
                value={state.showsOtherChildren}
                onValueChange={(value) => dispatch({ type: 'showsOthers', value })}
                trackColor={{ true: colors.primary, false: colors.border }}
              />
            </View>
          ) : null}

          {kind === 'capture' && photoType ? (
            <StudentTagger
              title={t('mobile.portfolio.compose.tagsTitle')}
              hint={t('mobile.portfolio.compose.tagsHint')}
              options={options}
              selected={state.studentIds}
              onToggle={(id) => dispatch({ type: 'toggleStudent', studentId: id })}
              loading={roster.isPending}
            />
          ) : null}

          <ConsentPanel
            gate={consentGate}
            known={known}
            mode="own"
            onRemoveTags={() => {
              if (consentGate) dispatch({ type: 'setStudents', studentIds: withoutStudents(state.studentIds, consentGate.students) });
              setGate(null);
            }}
          />

          {confirmShown ? <ConfirmPanel checked={state.confirm} onChange={(value) => dispatch({ type: 'confirm', value })} highlight={confirmAsked} /> : null}

          {offline ? (
            <View accessibilityRole="alert" style={{ padding: spacing[4], borderRadius: 16, borderWidth: 1, borderColor: colors.warning, gap: spacing[1] }}>
              <Text variant="label">{t('mobile.portfolio.compose.offlineTitle')}</Text>
              <Text>{t('mobile.portfolio.compose.offlineBody')}</Text>
            </View>
          ) : problem ? (
            <ProblemBanner error={problem} fallbackTitle={t('mobile.portfolio.compose.failedTitle')} fallbackBody={t('mobile.portfolio.compose.failedBody')} />
          ) : null}

          {blockedText ? (
            <Text variant="caption" accessibilityLiveRegion="polite">
              {blockedText}
            </Text>
          ) : submitText && canSubmit ? (
            <Text variant="caption" accessibilityLiveRegion="polite">
              {submitText}
            </Text>
          ) : null}

          {kind === 'capture' ? (
            <Button label={offline ? t('common.retry') : t('mobile.portfolio.compose.addToPortfolio')} loading={busy === 'submit'} disabled={!!submitBlock || busy !== null} onPress={() => void send('submit')} />
          ) : (
            <View style={{ gap: spacing[2] }}>
              {canSubmit ? (
                <Button
                  label={offline ? t('common.retry') : existing?.status === 'CHANGES_REQUESTED' ? t('mobile.portfolio.compose.sendAgain') : t('mobile.portfolio.compose.submit')}
                  loading={busy === 'submit'}
                  disabled={!!submitBlock || busy !== null}
                  onPress={() => void send('submit')}
                />
              ) : null}
              <Button
                variant={canSubmit ? 'secondary' : 'primary'}
                label={editing ? t('mobile.portfolio.compose.saveChanges') : t('mobile.portfolio.compose.saveDraft')}
                loading={busy === 'draft'}
                disabled={!!draftBlock || busy !== null}
                onPress={() => void send('draft')}
              />
              {!editing ? <Text variant="caption">{t('mobile.portfolio.compose.draftHint')}</Text> : null}
            </View>
          )}
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

function CancelButton() {
  const t = useTranslations();
  return <Button variant="ghost" fullWidth={false} label={t('common.cancel')} onPress={() => (router.canGoBack() ? router.back() : router.replace('/'))} />;
}
