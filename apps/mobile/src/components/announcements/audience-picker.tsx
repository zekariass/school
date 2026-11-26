import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { ActivityIndicator, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { allowedKinds, allowedRoles, audienceProblem, toggle, type AudienceChoice, type AudienceKind } from '@/lib/announcements/audience';
import { ANNOUNCEMENTS_GC_MS, audienceChoicesKey } from '@/lib/announcements/keys';
import { KIND_LABEL, ROLE_HINT, ROLE_LABEL } from '@/lib/announcements/labels';
import { fetchClasses, fetchGradeLevels } from '@/lib/announcements/requests';
import type { AnnouncementCapabilities } from '@/lib/announcements/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { ProblemBanner } from '../problem-banner';
import { SegmentedControl, Text, TextField } from '../ui';

import { CheckRow } from './check-row';

/**
 * Who gets the announcement, built only from what `capabilities` allows: a teacher picks among their own classes and the
 * guardians (and students when the school allows); a manager may also address the whole school or grade levels and the staff.
 * The server checks it all again when publishing.
 */
export function AudiencePicker({
  caps,
  schoolId,
  value,
  onChange,
}: {
  caps: AnnouncementCapabilities;
  schoolId: string;
  value: AudienceChoice;
  onChange: (value: AudienceChoice) => void;
}) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const kinds = allowedKinds(caps);
  const roles = allowedRoles(caps);
  const problem = audienceProblem(value, caps);

  return (
    <View style={{ gap: spacing[3] }}>
      <Text variant="heading">{t('mobile.announcements.audience.title')}</Text>
      {kinds.length > 1 ? (
        <SegmentedControl<AudienceKind>
          accessibilityLabel={t('mobile.announcements.audience.kindLabel')}
          value={value.kind}
          onChange={(kind) => onChange({ ...value, kind })}
          options={kinds.map((kind) => ({ value: kind, label: t(KIND_LABEL[kind]) }))}
        />
      ) : null}

      {value.kind === 'SCHOOL' ? <Text tone="muted">{t('mobile.announcements.audience.wholeSchool')}</Text> : null}
      {value.kind === 'GRADE_LEVEL' ? <GradeLevels schoolId={schoolId} selected={value.gradeLevelIds} onToggle={(id) => onChange({ ...value, gradeLevelIds: toggle(value.gradeLevelIds, id) })} /> : null}
      {value.kind === 'CLASS' ? (
        caps.schoolWide ? (
          <SchoolClasses schoolId={schoolId} selected={value.classIds} onToggle={(id) => onChange({ ...value, classIds: toggle(value.classIds, id) })} />
        ) : (
          <View>
            {caps.classes.map((cls) => (
              <CheckRow key={cls.id} label={cls.name} checked={value.classIds.includes(cls.id)} onToggle={() => onChange({ ...value, classIds: toggle(value.classIds, cls.id) })} />
            ))}
          </View>
        )
      ) : null}

      <Text variant="label">{t('mobile.announcements.audience.rolesLabel')}</Text>
      <View accessibilityRole="none">
        {roles.map((role) => (
          <CheckRow
            key={role}
            label={t(ROLE_LABEL[role])}
            hint={t(ROLE_HINT[role])}
            checked={value.roles.includes(role)}
            onToggle={() => onChange({ ...value, roles: toggle(value.roles, role) })}
          />
        ))}
      </View>
      {problem === 'no_roles' ? (
        <Text variant="caption" tone="destructive" accessibilityLiveRegion="polite">
          {t('mobile.announcements.audience.noRoles')}
        </Text>
      ) : null}
      {problem === 'no_targets' ? (
        <Text variant="caption" tone="destructive" accessibilityLiveRegion="polite">
          {value.kind === 'GRADE_LEVEL' ? t('mobile.announcements.audience.noGrades') : t('mobile.announcements.audience.noClasses')}
        </Text>
      ) : null}
    </View>
  );
}

function GradeLevels({ schoolId, selected, onToggle }: { schoolId: string; selected: string[]; onToggle: (id: string) => void }) {
  const t = useTranslations();
  const { colors } = useAppTheme();
  const grades = useQuery({ queryKey: audienceChoicesKey(schoolId, 'grades'), queryFn: () => fetchGradeLevels(schoolId), staleTime: 5 * 60_000, gcTime: ANNOUNCEMENTS_GC_MS });
  if (grades.isPending) return <ActivityIndicator color={colors.primary} />;
  if (grades.isError) return <ProblemBanner error={grades.error} fallbackTitle={t('mobile.announcements.audience.loadFailedTitle')} fallbackBody={t('mobile.announcements.audience.loadFailedBody')} onRetry={() => void grades.refetch()} />;
  const active = grades.data.filter((grade) => grade.active);
  return (
    <View>
      {active.length === 0 ? <Text tone="muted">{t('mobile.announcements.audience.noGradesExist')}</Text> : null}
      {active.map((grade) => (
        <CheckRow key={grade.id} label={grade.name} checked={selected.includes(grade.id)} onToggle={() => onToggle(grade.id)} />
      ))}
    </View>
  );
}

/** A manager picks among all the classes of the school: a searched list, with the chosen ones kept on top by name. */
function SchoolClasses({ schoolId, selected, onToggle }: { schoolId: string; selected: string[]; onToggle: (id: string) => void }) {
  const t = useTranslations();
  const { colors } = useAppTheme();
  const [search, setSearch] = useState('');
  // Names of the classes chosen, so a chosen class keeps its name when a later search no longer lists it.
  const [names, setNames] = useState<ReadonlyMap<string, string>>(new Map());
  const classes = useQuery({
    queryKey: audienceChoicesKey(schoolId, 'classes', search.trim()),
    queryFn: () => fetchClasses(schoolId, search.trim()),
    staleTime: 60_000,
    gcTime: ANNOUNCEMENTS_GC_MS,
  });
  const found = classes.data?.items ?? [];
  const chosenElsewhere = selected.filter((id) => !found.some((cls) => cls.id === id));
  return (
    <View style={{ gap: 4 }}>
      <TextField label={t('mobile.announcements.audience.searchClasses')} value={search} onChangeText={setSearch} autoCapitalize="none" autoCorrect={false} />
      {classes.isFetching ? <ActivityIndicator color={colors.primary} /> : null}
      {classes.isError ? <ProblemBanner error={classes.error} fallbackTitle={t('mobile.announcements.audience.loadFailedTitle')} fallbackBody={t('mobile.announcements.audience.loadFailedBody')} onRetry={() => void classes.refetch()} /> : null}
      {chosenElsewhere.map((id) => (
        <CheckRow key={id} label={names.get(id) ?? t('mobile.announcements.audience.chosenClass')} checked onToggle={() => onToggle(id)} />
      ))}
      {found.map((cls) => (
        <CheckRow
          key={cls.id}
          label={cls.name}
          hint={cls.gradeLevelName}
          checked={selected.includes(cls.id)}
          onToggle={() => {
            setNames((current) => new Map(current).set(cls.id, cls.name));
            onToggle(cls.id);
          }}
        />
      ))}
      {!classes.isFetching && found.length === 0 && chosenElsewhere.length === 0 ? <Text tone="muted">{t('mobile.announcements.audience.noMatch')}</Text> : null}
    </View>
  );
}
