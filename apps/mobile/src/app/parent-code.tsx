import { useState } from 'react';
import { useTranslations } from 'use-intl';

import { InviteFlow } from '@/components/family/invite-flow';
import { Button, Screen, Text, TextField } from '@/components/ui';
import { useAppTheme } from '@/lib/theme/theme-provider';
import { formatParentCode, isCompleteParentCode, normalizeParentCode } from '@school/validation/guardians';

/**
 * "Have a parent code?": the printed code from the child's school, for a parent who has no account yet
 * (an unknown phone number cannot sign in) or who is signed in already. The code lives only in this
 * field and in the requests: never in a route parameter, storage or a query key.
 */
export default function ParentCodeScreen() {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const [value, setValue] = useState('');
  const [code, setCode] = useState<string | null>(null);
  const [incomplete, setIncomplete] = useState(false);

  // Remounted with the code as key, so another code starts a fresh preview.
  if (code) return <InviteFlow key={code} keyOf={{ code }} />;

  function submit() {
    const complete = isCompleteParentCode(value);
    setIncomplete(!complete);
    if (complete) setCode(normalizeParentCode(value));
  }

  return (
    <Screen scroll edges={['bottom']} contentStyle={{ gap: spacing[5] }}>
      <Text tone="muted">{t('family.connect.body')}</Text>
      <TextField
        label={t('family.connect.codeLabel')}
        hint={t('family.connect.codeHint')}
        error={incomplete ? t('family.connect.incomplete') : undefined}
        value={value}
        onChangeText={(next) => {
          setValue(formatParentCode(next));
          if (incomplete) setIncomplete(false);
        }}
        autoCapitalize="characters"
        autoCorrect={false}
        autoComplete="off"
        spellCheck={false}
        autoFocus
        maxLength={12}
        returnKeyType="go"
        onSubmitEditing={submit}
        style={{ fontSize: 24, letterSpacing: 4, textAlign: 'center' }}
      />
      <Button label={t('family.connect.continue')} onPress={submit} />
    </Screen>
  );
}
