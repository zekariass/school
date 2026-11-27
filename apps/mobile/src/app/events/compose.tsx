import { useQueryClient } from '@tanstack/react-query';
import { randomUUID } from 'expo-crypto';
import { Stack, router, useLocalSearchParams, usePreventRemove } from 'expo-router';
import { useMemo, useRef, useState } from 'react';
import { ActivityIndicator, Alert, KeyboardAvoidingView, Platform, ScrollView, Switch, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { ChoiceField, type Choice } from '@/components/calendar/choice-sheet';
import { InlineError } from '@/components/calendar/inline-error';
import { RepeatBuilder } from '@/components/calendar/repeat-builder';
import { Stepper } from '@/components/calendar/stepper';
import { Chips } from '@/components/feed/chips';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, EmptyState, Sheet, Text, TextField } from '@/components/ui';
import { useMe } from '@/lib/auth/use-me';
import { ApiRequestError } from '@/lib/api-error';
import { addDays, isCivilDate } from '@/lib/calendar/civil';
import {
  canAskAnswers,
  changeWarnings,
  createRequest,
  formFromEvent,
  formFromOccurrence,
  hasWarnings,
  isEmptyRequest,
  issueFor,
  kindOptions,
  newForm,
  occurrenceRequest,
  stepTime,
  switchKind,
  typesFor,
  updateRequest,
  validateForm,
  withAllDay,
  withEnd,
  withStart,
  withZone,
  zoneChoices,
  type EventForm,
  type FormField,
  type FormKind,
} from '@/lib/calendar/form';
import { dateText } from '@/lib/calendar/labels';
import { isOccurrenceId } from '@/lib/calendar/occurrence';
import { problemKind } from '@/lib/calendar/problems';
import { replaceEvent, useAllClasses, useCalendarCapabilities, useCalendarSchools, useEvent } from '@/lib/calendar/queries';
import { createPersonalEvent, createSchoolEvent, updateEvent, updateOccurrence } from '@/lib/calendar/requests';
import type { CalendarCapabilities, CalendarEventView } from '@/lib/calendar/types';
import { useDeviceZone, useLabelContext, useLooseT } from '@/lib/calendar/use-labels';
import { zoneLabel } from '@/lib/calendar/zone';
import { keyAfterFailure, keyFor, type SubmitKey } from '@/lib/feed/composer';
import { clockText } from '@/lib/feed/schedule';
import { useClock } from '@/lib/feed/use-clock';
import { useDisplayZone } from '@/lib/country/place';
import { useMyClasses, useRoster } from '@/lib/people/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

type Params = { eventId?: string; occurrenceId?: string; date?: string; schoolId?: string };

function leave() {
  if (router.canGoBack()) router.back();
  else router.replace('/');
}

/**
 * Creates an event (a teacher's school and class events and parent meetings, anybody's personal events), changes one, or changes
 * one occurrence of a repeating event. Times are typed in the EVENT's time zone (named on the screen and changeable), never the
 * phone's. What is typed stays in this screen's memory only; a failure never empties the form.
 */
export default function ComposeScreen() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const params = useLocalSearchParams<Params>();
  const existing = useEvent(params.eventId);
  const me = useMe();
  const centered = { flex: 1, alignItems: 'center', justifyContent: 'center', padding: spacing[5], backgroundColor: colors.background } as const;

  if (params.eventId) {
    if (existing.isPending) {
      return (
        <View style={centered}>
          <ActivityIndicator color={colors.primary} />
        </View>
      );
    }
    if (existing.isError || !existing.data) {
      return (
        <View style={centered}>
          <ProblemBanner error={existing.error} fallbackTitle={t('mobile.calendar.loadFailedTitle')} fallbackBody={t('mobile.calendar.loadFailedBody')} onRetry={() => void existing.refetch()} />
        </View>
      );
    }
    if (!existing.data.capabilities?.canEdit) {
      return (
        <View style={centered}>
          <EmptyState icon={{ ios: 'lock', android: 'lock' }} title={t('mobile.calendar.form.notAllowedTitle')} body={t('mobile.calendar.form.notAllowedBody')}>
            <Button label={t('common.back')} onPress={leave} />
          </EmptyState>
        </View>
      );
    }
    const occurrenceId = isOccurrenceId(params.occurrenceId) ? params.occurrenceId : null;
    return <Editor key={`${existing.data.id}|${occurrenceId ?? ''}`} view={existing.data} occurrenceId={occurrenceId} />;
  }
  if (me.isPending) {
    return (
      <View style={centered}>
        <ActivityIndicator color={colors.primary} />
      </View>
    );
  }
  return <Creator date={isCivilDate(params.date) ? params.date : undefined} schoolParam={params.schoolId} />;
}

// --- creating ----------------------------------------------------------------------------------------------------------

function Creator({ date, schoolParam }: { date: string | undefined; schoolParam: string | undefined }) {
  const me = useMe();
  const schools = useCalendarSchools();
  // Only a teacher (or other staff) member can create events in a school; everybody can create personal ones.
  const creatorSchools = useMemo(() => {
    const ids = new Set((me.data?.memberships ?? []).filter((m) => m.type === 'TEACHER' || m.type === 'STAFF').map((m) => m.schoolId));
    return schools.filter((school) => ids.has(school.id));
  }, [me.data, schools]);
  const [schoolId, setSchoolId] = useState<string | null>(() => (creatorSchools.some((s) => s.id === schoolParam) ? (schoolParam ?? null) : (creatorSchools[0]?.id ?? null)));
  const caps = useCalendarCapabilities(schoolId ?? undefined);
  // The account's own zone, else the zone of their country (D42).
  const userZone = useDisplayZone();

  if (schoolId && caps.isPending) return <Waiting />;
  // A school whose calendar is off, or that does not let this person create, still allows personal events.
  const capabilities = caps.isSuccess ? caps.data : null;
  return (
    <Form
      key={schoolId ?? 'personal'}
      mode={{ type: 'create', date, schoolId, schools: creatorSchools, onSchool: setSchoolId, capabilities, capsError: caps.isError ? caps.error : null, userZone }}
    />
  );
}

function Waiting() {
  const { colors, spacing } = useAppTheme();
  return (
    <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', padding: spacing[5], backgroundColor: colors.background }}>
      <ActivityIndicator color={colors.primary} />
    </View>
  );
}

// --- editing -----------------------------------------------------------------------------------------------------------

function Editor({ view, occurrenceId }: { view: CalendarEventView; occurrenceId: string | null }) {
  return <Form mode={{ type: 'edit', view, occurrenceId }} />;
}

type CreateMode = {
  type: 'create';
  date: string | undefined;
  schoolId: string | null;
  schools: { id: string; name: string }[];
  onSchool: (schoolId: string) => void;
  capabilities: CalendarCapabilities | null;
  capsError: unknown;
  userZone: string;
};
type EditMode = { type: 'edit'; view: CalendarEventView; occurrenceId: string | null };

const KIND_LABEL: Record<FormKind, string> = {
  SCHOOL: 'mobile.calendar.form.kind.school',
  CAMPUS: 'mobile.calendar.form.kind.campus',
  GRADE_LEVEL: 'mobile.calendar.form.kind.grade',
  CLASS: 'mobile.calendar.form.kind.class',
  PARENT_MEETING: 'mobile.calendar.form.kind.meeting',
  PERSONAL: 'mobile.calendar.form.kind.personal',
};
const TYPE_LABEL = {
  SCHOOL_EVENT: 'mobile.calendar.kind.schoolEvent',
  CLASS_EVENT: 'mobile.calendar.kind.classEvent',
  EXAM: 'mobile.calendar.kind.exam',
  PARENT_MEETING: 'mobile.calendar.kind.parentMeeting',
  OTHER: 'mobile.calendar.kind.other',
} as const;

function Form({ mode }: { mode: CreateMode | EditMode }) {
  const t = useTranslations();
  const tx = useLooseT();
  const { colors, spacing } = useAppTheme();
  const context = useLabelContext();
  const queryClient = useQueryClient();
  const deviceZone = useDeviceZone();
  const clock = useClock(30_000);
  const editing = mode.type === 'edit';
  const occurrenceId = mode.type === 'edit' ? mode.occurrenceId : null;
  const view = mode.type === 'edit' ? mode.view : null;

  const kinds = useMemo(() => (mode.type === 'create' ? kindOptions(mode.capabilities) : []), [mode]);
  const schoolZone = mode.type === 'create' ? (mode.capabilities?.timeZone ?? null) : null;

  const original = useMemo<EventForm | null>(() => (view ? (occurrenceId ? formFromOccurrence(view, occurrenceId) : formFromEvent(view)) : null), [view, occurrenceId]);
  const [form, setForm] = useState<EventForm>(() => {
    if (original) return original;
    const create = mode as CreateMode;
    const first = kinds[0]!.kind;
    return newForm({ kind: first, schoolId: create.schoolId, timeZone: first === 'PERSONAL' ? create.userZone : (schoolZone ?? create.userZone), nowMs: Date.now(), date: create.date });
  });
  const [initial] = useState(form);
  const [attempted, setAttempted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<unknown>(null);
  const [confirm, setConfirm] = useState(false);
  const [guard, setGuard] = useState(true);
  const submitKey = useRef<SubmitKey | null>(null);

  const set = (patch: Partial<EventForm>) => setForm((current) => ({ ...current, ...patch }));
  const issues = validateForm(form, clock, original);
  const issue = (field: FormField) => {
    const found = attempted ? issueFor(issues, field) : undefined;
    return found ? tx(found.key, found.values ?? {}) : undefined;
  };
  const dirty = JSON.stringify(form) !== JSON.stringify(initial);
  const disableGuard = usePreventRemove(dirty && guard, ({ repeat }) => {
    Alert.alert(t('mobile.calendar.form.discardTitle'), t('mobile.calendar.form.discardBody'), [
      { text: t('mobile.calendar.form.keepEditing'), style: 'cancel' },
      {
        text: t('mobile.calendar.form.discard'),
        style: 'destructive',
        onPress: () => {
          setGuard(false);
          repeat();
        },
      },
    ]);
  });

  const warnings = view && original ? changeWarnings(view, original, form) : null;
  const meeting = form.kind === 'PARENT_MEETING';
  const occurrence = form.mode === 'occurrence';

  async function save(): Promise<CalendarEventView> {
    if (view && original) {
      if (occurrenceId) return updateOccurrence(view.id, occurrenceId, occurrenceRequest(original, form));
      return updateEvent(view.id, updateRequest(original, form));
    }
    const body = createRequest(form);
    submitKey.current = keyFor(submitKey.current, { body, school: form.schoolId }, randomUUID);
    return form.kind === 'PERSONAL' || !form.schoolId ? createPersonalEvent(body, submitKey.current.key) : createSchoolEvent(form.schoolId, body, submitKey.current.key);
  }

  async function submit(confirmed = false) {
    if (busy) return;
    setAttempted(true);
    if (issues.length > 0) return;
    if (view && original) {
      const request = occurrence ? occurrenceRequest(original, form) : updateRequest(original, form);
      if (isEmptyRequest(request)) {
        leave();
        return;
      }
      if (!confirmed && warnings && hasWarnings(warnings)) {
        setConfirm(true);
        return;
      }
    }
    setConfirm(false);
    setBusy(true);
    setProblem(null);
    try {
      const saved = await save();
      replaceEvent(queryClient, saved);
      submitKey.current = null;
      disableGuard();
      setGuard(false);
      if (editing) leave();
      else router.replace({ pathname: '/events/[eventId]', params: { eventId: saved.id, ...(saved.schoolId ? { schoolId: saved.schoolId } : {}) } });
    } catch (error) {
      setProblem(error);
      // Only a failure without an answer (offline, timeout, 5xx) keeps the key: the same submit is then a retry.
      if (submitKey.current) submitKey.current = keyAfterFailure(submitKey.current, error instanceof ApiRequestError ? error.status : undefined);
    } finally {
      setBusy(false);
    }
  }

  const title = editing ? (occurrence ? t('mobile.calendar.form.titleOccurrence') : t('mobile.calendar.form.titleEdit')) : t('mobile.calendar.form.titleNew');
  const submitLabel = editing ? t('common.save') : t('mobile.calendar.form.create');

  return (
    <SafeAreaView edges={['bottom']} style={{ flex: 1, backgroundColor: colors.background }}>
      <Stack.Screen options={{ title, headerLeft: () => <Button variant="ghost" fullWidth={false} label={t('common.cancel')} onPress={leave} /> }} />
      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={{ flex: 1 }}>
        <ScrollView contentContainerStyle={{ padding: spacing[5], gap: spacing[5] }} keyboardShouldPersistTaps="handled" keyboardDismissMode="on-drag">
          {mode.type === 'create' ? (
            <AudienceSection mode={mode} form={form} setForm={setForm} kinds={kinds} issue={issue} schoolZone={schoolZone} />
          ) : (
            <Text variant="caption">{tx(KIND_LABEL[form.kind])}</Text>
          )}

          <TextField label={t('mobile.calendar.form.title')} value={form.title} onChangeText={(title) => set({ title })} maxLength={400} error={issue('title')} returnKeyType="next" />
          <TextField label={t('mobile.calendar.form.description')} value={form.description} onChangeText={(description) => set({ description })} multiline style={{ minHeight: 96, textAlignVertical: 'top' }} error={issue('description')} />
          <TextField label={t('mobile.calendar.form.location')} value={form.location} onChangeText={(location) => set({ location })} error={issue('location')} />

          <WhenSection form={form} setForm={setForm} issue={issue} showAllDay={!occurrence && !meeting} showZone={!occurrence} schoolZone={schoolZone} userZone={mode.type === 'create' ? mode.userZone : null} deviceZone={deviceZone} />

          {!occurrence && !meeting ? (
            <RepeatBuilder
              value={form.repeat}
              onChange={(repeat) => setForm((current) => ({ ...current, repeat, rsvpEnabled: repeat.freq !== 'NONE' ? false : current.rsvpEnabled }))}
              startDate={form.startDate}
              context={context}
            />
          ) : null}

          {!occurrence && form.kind !== 'PERSONAL' && !meeting ? (
            <View style={{ gap: spacing[1] }}>
              <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing[3] }}>
                <Text variant="label" style={{ flex: 1 }}>
                  {t('mobile.calendar.form.rsvp')}
                </Text>
                <Switch
                  accessibilityLabel={t('mobile.calendar.form.rsvp')}
                  value={form.rsvpEnabled}
                  disabled={!canAskAnswers(form)}
                  onValueChange={(rsvpEnabled) => set({ rsvpEnabled })}
                  trackColor={{ true: colors.primary, false: colors.border }}
                />
              </View>
              <Text variant="caption">{canAskAnswers(form) ? t('mobile.calendar.form.rsvpHint') : t('mobile.calendar.form.rsvpNotWithRepeat')}</Text>
              {issue('rsvp') ? <Text variant="caption" tone="destructive">{issue('rsvp')}</Text> : null}
            </View>
          ) : null}

          {meeting && !editing ? <Text variant="caption">{t('mobile.calendar.form.meetingPrivacy')}</Text> : null}
          {occurrence ? <Text variant="caption">{t('mobile.calendar.form.occurrenceNote')}</Text> : null}

          <InlineError error={problem} />
          {problem && problemKind(problem) === 'offline' ? <Text variant="caption">{t('mobile.calendar.form.keptOnError')}</Text> : null}
          <Button label={submitLabel} loading={busy} onPress={() => void submit()} />
        </ScrollView>
      </KeyboardAvoidingView>

      <Sheet visible={confirm} onClose={() => setConfirm(false)} title={t('mobile.calendar.confirm.title')}>
        {warnings?.clearsExceptions ? <Text>{t('mobile.calendar.confirm.clearsExceptions')}</Text> : null}
        {warnings?.resetsAnswers ? <Text>{t('mobile.calendar.confirm.resetsAnswers')}</Text> : null}
        {warnings?.notifies ? <Text>{meeting ? t('mobile.calendar.confirm.notifiesMeeting') : t('mobile.calendar.confirm.notifies')}</Text> : null}
        <Button label={t('mobile.calendar.confirm.save')} loading={busy} onPress={() => void submit(true)} />
        <Button variant="ghost" label={t('mobile.calendar.form.keepEditing')} onPress={() => setConfirm(false)} />
      </Sheet>
    </SafeAreaView>
  );
}

// --- who the event is for -----------------------------------------------------------------------------------------------

function AudienceSection({
  mode,
  form,
  setForm,
  kinds,
  issue,
  schoolZone,
}: {
  mode: CreateMode;
  form: EventForm;
  setForm: (update: (current: EventForm) => EventForm) => void;
  kinds: ReturnType<typeof kindOptions>;
  issue: (field: FormField) => string | undefined;
  schoolZone: string | null;
}) {
  const t = useTranslations();
  const tx = useLooseT();
  const { spacing } = useAppTheme();
  const caps = mode.capabilities;
  const myClasses = useMyClasses();
  const allClasses = useAllClasses(form.schoolId ?? undefined, form.kind === 'CLASS' && !!caps && !!caps.canCreateAnyClassEvent && !caps.classes);
  const roster = useRoster(form.kind === 'PARENT_MEETING' ? (form.schoolId ?? undefined) : undefined, form.kind === 'PARENT_MEETING' ? (form.classId ?? undefined) : undefined);

  const classChoices: Choice[] =
    form.kind === 'PARENT_MEETING'
      ? (myClasses.data ?? [])
          .filter((cls) => cls.role === 'TEACHER' && cls.schoolId === form.schoolId && (cls.teacherRole === 'LEAD' || cls.teacherRole === 'CO_TEACHER' || cls.teacherRole === 'SUBJECT_TEACHER'))
          .map((cls) => ({ id: cls.id, label: cls.name }))
      : (caps?.classes ?? allClasses.data ?? []).map((cls) => ({ id: cls.id ?? '', label: cls.name ?? '' })).filter((choice) => choice.id);
  const students: Choice[] = (roster.data ?? []).map((student) => ({ id: student.id, label: student.name }));

  return (
    <View style={{ gap: spacing[4] }}>
      {mode.schools.length > 1 && form.kind !== 'PERSONAL' ? (
        <Chips
          accessibilityLabel={t('mobile.calendar.form.school')}
          value={form.schoolId ?? mode.schools[0]!.id}
          onChange={(id) => mode.onSchool(id)}
          chips={mode.schools.map((school) => ({ value: school.id, label: school.name }))}
        />
      ) : null}
      {kinds.length > 1 ? (
        <Chips<FormKind>
          accessibilityLabel={t('mobile.calendar.form.kindLabel')}
          value={form.kind}
          onChange={(kind) => setForm((current) => switchKind(current, kind, { schoolZone, userZone: mode.userZone }))}
          chips={kinds.map((option) => ({ value: option.kind, label: tx(KIND_LABEL[option.kind]) }))}
        />
      ) : null}
      {mode.capsError && !mode.capabilities ? (
        <Text variant="caption">{problemKind(mode.capsError) === 'featureDisabled' ? t('mobile.calendar.form.calendarOff') : t('mobile.calendar.form.personalOnly')}</Text>
      ) : null}
      {typesFor(form.kind).length > 1 ? (
        <Chips
          accessibilityLabel={t('mobile.calendar.form.typeLabel')}
          value={form.eventType}
          onChange={(eventType) => setForm((current) => ({ ...current, eventType }))}
          chips={typesFor(form.kind).map((type) => ({ value: type, label: tx(TYPE_LABEL[type]) }))}
        />
      ) : null}
      {form.kind === 'CLASS' || form.kind === 'PARENT_MEETING' ? (
        <ChoiceField
          label={t('mobile.calendar.form.class')}
          choices={classChoices}
          value={form.classId}
          onChange={(classId) => setForm((current) => ({ ...current, classId, studentId: null }))}
          emptyText={t('mobile.calendar.form.noClasses')}
          loading={myClasses.isPending}
          error={issue('class')}
        />
      ) : null}
      {form.kind === 'PARENT_MEETING' ? (
        <ChoiceField
          label={t('mobile.calendar.form.student')}
          choices={students}
          value={form.studentId}
          onChange={(studentId) => setForm((current) => ({ ...current, studentId }))}
          emptyText={form.classId ? t('mobile.calendar.form.noStudents') : t('mobile.calendar.form.pickClassFirst')}
          loading={roster.isPending && !!form.classId}
          disabled={!form.classId}
          error={issue('student')}
        />
      ) : null}
      {issue('audience') ? <Text variant="caption" tone="destructive">{issue('audience')}</Text> : null}
    </View>
  );
}

// --- when ----------------------------------------------------------------------------------------------------------------

function WhenSection({
  form,
  setForm,
  issue,
  showAllDay,
  showZone,
  schoolZone,
  userZone,
  deviceZone,
}: {
  form: EventForm;
  setForm: (update: (current: EventForm) => EventForm) => void;
  issue: (field: FormField) => string | undefined;
  showAllDay: boolean;
  showZone: boolean;
  schoolZone: string | null;
  userZone: string | null;
  deviceZone: string | null;
}) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const { locale, calendar } = useLabelContext();
  // The zones of the countries the account has schools in come from /me (D42).
  const me = useMe();
  const countryZones = me.data?.countries.map((c) => c.defaultTimezone) ?? [];
  const zones = zoneChoices([form.timeZone, schoolZone, userZone, deviceZone, ...countryZones]);
  const dayLabels = { smallBack: t('mobile.calendar.stepper.dayBefore'), smallForward: t('mobile.calendar.stepper.dayAfter'), largeBack: t('mobile.calendar.stepper.weekBefore'), largeForward: t('mobile.calendar.stepper.weekAfter') };
  const timeLabels = { smallBack: t('mobile.calendar.stepper.quarterBefore'), smallForward: t('mobile.calendar.stepper.quarterAfter'), largeBack: t('mobile.calendar.stepper.hourBefore'), largeForward: t('mobile.calendar.stepper.hourAfter') };

  return (
    <View style={{ gap: spacing[4] }}>
      {showAllDay ? (
        <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing[3] }}>
          <Text variant="label" style={{ flex: 1 }}>
            {t('mobile.calendar.form.allDay')}
          </Text>
          <Switch accessibilityLabel={t('mobile.calendar.form.allDay')} value={form.allDay} onValueChange={(allDay) => setForm((current) => withAllDay(current, allDay))} />
        </View>
      ) : null}

      <Stepper
        label={t('mobile.calendar.form.startDate')}
        value={dateText(form.startDate, calendar, locale)}
        labels={dayLabels}
        error={issue('start')}
        onStep={(direction, size) => setForm((current) => withStart(current, addDays(current.startDate, direction * (size === 2 ? 7 : 1)), current.startMinutes))}
      />
      {!form.allDay ? (
        <Stepper
          label={t('mobile.calendar.form.startTime')}
          value={clockText(form.startMinutes)}
          labels={timeLabels}
          onStep={(direction, size) =>
            setForm((current) => {
              const next = stepTime(current.startDate, current.startMinutes, direction, size === 2 ? 60 : 15);
              return withStart(current, next.date, next.minutes);
            })
          }
        />
      ) : null}
      <Stepper
        label={t('mobile.calendar.form.endDate')}
        value={dateText(form.endDate, calendar, locale)}
        labels={dayLabels}
        error={issue('end')}
        onStep={(direction, size) => setForm((current) => withEnd(current, addDays(current.endDate, direction * (size === 2 ? 7 : 1)), current.endMinutes))}
      />
      {!form.allDay ? (
        <Stepper
          label={t('mobile.calendar.form.endTime')}
          value={clockText(form.endMinutes)}
          labels={timeLabels}
          onStep={(direction, size) =>
            setForm((current) => {
              const next = stepTime(current.endDate, current.endMinutes, direction, size === 2 ? 60 : 15);
              return withEnd(current, next.date, next.minutes);
            })
          }
        />
      ) : null}

      {showZone ? (
        <View style={{ gap: spacing[1] }}>
          <ChoiceField
            label={t('mobile.calendar.form.timeZone')}
            choices={zones.map((zone) => ({ id: zone, label: zoneLabel(zone) }))}
            value={form.timeZone}
            onChange={(zone) => setForm((current) => withZone(current, zone))}
            emptyText={t('mobile.calendar.form.noMatch')}
            error={issue('timeZone')}
          />
          <Text variant="caption">{form.allDay ? t('mobile.calendar.form.zoneHintAllDay', { zone: zoneLabel(form.timeZone) }) : t('mobile.calendar.form.zoneHint', { zone: zoneLabel(form.timeZone) })}</Text>
        </View>
      ) : (
        <Text variant="caption">{t('mobile.calendar.form.zoneFixed', { zone: zoneLabel(form.timeZone) })}</Text>
      )}
    </View>
  );
}
