import { useMutation, useQueryClient } from '@tanstack/react-query';
import { randomUUID } from 'expo-crypto';
import { router, useLocalSearchParams } from 'expo-router';
import { useRef, useState } from 'react';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { ProblemBanner } from '@/components/problem-banner';
import { Button, Screen, Text, TextField } from '@/components/ui';
import { api } from '@/lib/api';
import { ApiRequestError, request, unwrap } from '@/lib/api-error';
import { invalidateRoster, useMyClass } from '@/lib/people/queries';
import { formatDobInput, validateStudentDraft, type StudentDraftErrors } from '@/lib/people/student-form';
import { useAppTheme } from '@/lib/theme/theme-provider';

/** Quick form: create a school-managed student straight into this class. */
export default function NewStudent() {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const { classId } = useLocalSearchParams<{ classId: string }>();
  const { cls } = useMyClass(classId);
  const queryClient = useQueryClient();

  const [givenName, setGivenName] = useState('');
  const [fatherName, setFatherName] = useState('');
  const [dateOfBirth, setDateOfBirth] = useState('');
  const [errors, setErrors] = useState<StudentDraftErrors>({});
  // The same key while this form is open, so a retry after a lost response cannot create a second student.
  const idempotencyKey = useRef(randomUUID());

  const create = useMutation({
    networkMode: 'always',
    retry: false,
    mutationFn: (input: { dateOfBirth: string | null }) =>
      request(async () =>
        unwrap(
          await api.POST('/api/v1/schools/{schoolId}/students', {
            headers: { 'Idempotency-Key': idempotencyKey.current },
            params: { path: { schoolId: cls!.schoolId } },
            body: {
              givenName: givenName.trim(),
              ...(fatherName.trim() ? { middleName: fatherName.trim() } : {}),
              ...(input.dateOfBirth ? { dateOfBirth: input.dateOfBirth } : {}),
              classId: classId!,
            },
          }),
        ),
      ),
    onSuccess: async (student) => {
      await invalidateRoster(queryClient, classId!);
      // Straight to the new student, where the login code can be shown.
      router.replace({ pathname: '/class/[classId]/student/[studentId]', params: { classId: classId!, studentId: student.id } });
    },
  });

  function submit() {
    if (!cls || create.isPending) return;
    const result = validateStudentDraft({ givenName, fatherName, dateOfBirth }, new Date());
    setErrors(result.errors);
    if (Object.keys(result.errors).length > 0) return;
    create.mutate({ dateOfBirth: result.dateOfBirth });
  }

  const forbidden = create.error instanceof ApiRequestError && create.error.status === 403;
  const error = (key: keyof StudentDraftErrors) => {
    const code = errors[key];
    return code ? t(`mobile.classes.newStudent.errors.${code}`) : undefined;
  };

  return (
    <Screen scroll edges={['bottom']} contentStyle={{ gap: spacing[5] }}>
      <Text tone="muted">{t('mobile.classes.newStudent.intro', { class: cls?.name ?? '' })}</Text>

      <TextField
        label={t('mobile.classes.newStudent.givenName')}
        value={givenName}
        onChangeText={setGivenName}
        error={error('givenName')}
        autoCapitalize="words"
        autoCorrect={false}
        textContentType="givenName"
        returnKeyType="next"
        maxLength={100}
        editable={!create.isPending}
      />
      <TextField
        label={t('mobile.classes.newStudent.fatherName')}
        value={fatherName}
        onChangeText={setFatherName}
        error={error('fatherName')}
        autoCapitalize="words"
        autoCorrect={false}
        returnKeyType="next"
        maxLength={100}
        editable={!create.isPending}
      />
      <TextField
        label={t('mobile.classes.newStudent.dateOfBirth')}
        hint={t('mobile.classes.newStudent.dateHint')}
        value={dateOfBirth}
        onChangeText={(value) => setDateOfBirth(formatDobInput(value))}
        error={error('dateOfBirth')}
        keyboardType="number-pad"
        placeholder="YYYY-MM-DD"
        maxLength={10}
        returnKeyType="done"
        onSubmitEditing={submit}
        editable={!create.isPending}
      />

      {create.isError ? (
        <ProblemBanner
          error={create.error}
          fallbackTitle={t(forbidden ? 'mobile.classes.forbiddenTitle' : 'status.errorTitle')}
          fallbackBody={t(forbidden ? 'mobile.classes.forbiddenBody' : 'mobile.classes.actionFailed')}
        />
      ) : null}

      <View style={{ gap: spacing[2] }}>
        <Button label={t('mobile.classes.newStudent.submit')} onPress={submit} loading={create.isPending} disabled={!cls} />
        <Text variant="caption">{t('mobile.classes.newStudent.note')}</Text>
      </View>
    </Screen>
  );
}
