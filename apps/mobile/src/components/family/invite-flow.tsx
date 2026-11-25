import { useMutation, useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useId, useRef, useState, type ComponentRef } from 'react';
import { TextInput, View } from 'react-native';
import { useLocale, useTranslations } from 'use-intl';

import { RelationshipPicker } from '@/components/family/relationship-picker';
import { Button, EmptyState, Screen, SegmentedControl, Text, TextField } from '@/components/ui';
import { formatCountdown, nowMs, RESEND_COOLDOWN_SECONDS, sanitizeCode, secondsLeft } from '@/lib/auth/otp-timer';
import { useNow } from '@/lib/auth/use-now';
import { FAMILY_ROOT } from '@/lib/family/keys';
import {
  acceptInvitation,
  previewInvitation,
  requestInvitationOtp,
  type InvitationChallenge,
  type InvitationKey,
} from '@/lib/family/invitation-requests';
import { useRedeemInvitation } from '@/lib/family/queries';
import { invitationMessageFor, invitationProblemOf, retryAfterOfInvitation } from '@/lib/family/problems';
import { useSession } from '@/lib/session/session-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';
import { SUPPORTED_LOCALES, isSupportedLocale } from '@school/i18n';
import { OTP_LENGTH, otpCodeSchema } from '@school/validation';
import {
  isDeadInvitation,
  isRelationshipType,
  parseDestinationInput,
  type ContactDestination,
  type RelationshipType,
} from '@school/validation/guardians';

/**
 * Accepting an invitation, from its link token (`school://invite/{token}`, or the https link when the
 * app is installed) or from a printed parent code: preview, prove a phone or email with a one-time code,
 * connect and sign in. An adult who is already signed in connects the invitation to their own account.
 */
export function InviteFlow({ keyOf }: { keyOf: InvitationKey }) {
  const t = useTranslations();
  const { spacing } = useAppTheme();

  // The preview (school and the child's first name) lives only in memory, never in the persisted cache,
  // and its cache key carries no secret (the key may be a printed code).
  const mount = useId();
  const preview = useQuery({
    queryKey: [FAMILY_ROOT, 'invite-preview', mount],
    retry: false,
    gcTime: 0,
    queryFn: () => previewInvitation(keyOf),
  });

  if (preview.isError && isDeadInvitation(invitationProblemOf(preview.error))) return <Dead />;
  if (preview.isPending) return <Screen><Text tone="muted">{t('invite.loading')}</Text></Screen>;
  if (preview.isError) {
    const { message } = invitationMessageFor(preview.error);
    return (
      <Screen scroll edges={['bottom']} contentStyle={{ gap: spacing[4] }}>
        <Text tone="destructive" accessibilityRole="alert">
          {t(message.key, 'values' in message ? message.values : undefined)}
        </Text>
        <Button label={t('common.retry')} onPress={() => void preview.refetch()} />
      </Screen>
    );
  }
  return <Connect keyOf={keyOf} preview={preview.data} />;
}

/** The one answer for every invitation that cannot be used. */
export function Dead() {
  const t = useTranslations();
  return (
    <Screen edges={['bottom']}>
      <EmptyState icon={{ ios: 'link.badge.plus', android: 'link_off' }} title={t('invite.notFoundTitle')} body={t('invite.notFoundBody')}>
        <Button label={t('common.done')} onPress={() => router.replace('/')} />
      </EmptyState>
    </Screen>
  );
}

function Connect({ keyOf, preview }: { keyOf: InvitationKey; preview: { schoolName: string; childFirstName: string; relationshipType?: string | undefined } }) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const { status } = useSession();
  const [dead, setDead] = useState(false);
  const relationship = isRelationshipType(preview.relationshipType) ? preview.relationshipType : null;
  const [useAnother, setUseAnother] = useState(false);
  const { signOut } = useSession();

  if (dead) return <Dead />;

  return (
    <Screen scroll edges={['bottom']} contentStyle={{ gap: spacing[5] }}>
      <View style={{ gap: spacing[2] }}>
        <Text variant="title">{t('invite.connectTitle', { child: preview.childFirstName })}</Text>
        <Text tone="muted">{t('invite.connectBody', { school: preview.schoolName, child: preview.childFirstName })}</Text>
        {relationship ? <Text tone="muted">{t('invite.relationshipLine', { relationship: t(`family.relationship.${relationship}`) })}</Text> : null}
      </View>
      {status === 'signedIn' && !useAnother ? (
        <SignedInConnect
          keyOf={keyOf}
          relationship={relationship}
          onDead={() => setDead(true)}
          onUseAnother={async () => {
            // Another number means another account: end this session first, then the signed-out flow shows.
            await signOut();
            setUseAnother(true);
          }}
        />
      ) : (
        <SignedOutConnect keyOf={keyOf} onDead={() => setDead(true)} />
      )}
    </Screen>
  );
}

function SignedInConnect({
  keyOf,
  relationship,
  onDead,
  onUseAnother,
}: {
  keyOf: InvitationKey;
  relationship: RelationshipType | null;
  onDead: () => void;
  onUseAnother: () => void | Promise<void>;
}) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const [chosen, setChosen] = useState<RelationshipType>(relationship ?? 'MOTHER');
  const redeem = useRedeemInvitation(keyOf);
  const message = redeem.isError ? invitationMessageFor(redeem.error) : null;

  return (
    <View style={{ gap: spacing[4] }}>
      <Text variant="label">{t('invite.alreadySignedInTitle')}</Text>
      <Text tone="muted">{t('invite.alreadySignedInBody')}</Text>
      {relationship === null ? <RelationshipPicker label={t('family.connect.relationshipLabel')} value={chosen} onChange={setChosen} /> : null}
      {message ? (
        <Text tone="destructive" accessibilityRole="alert">
          {message.detail ?? t(message.message.key, 'values' in message.message ? message.message.values : undefined)}
        </Text>
      ) : null}
      <Button
        label={t('invite.connectSignedIn')}
        loading={redeem.isPending}
        onPress={() =>
          redeem.mutate(
            relationship ?? chosen,
            {
              onSuccess: () => router.replace('/'),
              onError: (error) => {
                if (isDeadInvitation(invitationProblemOf(error))) onDead();
              },
            },
          )
        }
      />
      <Button variant="ghost" label={t('invite.changeContact')} onPress={() => void onUseAnother()} disabled={redeem.isPending} />
    </View>
  );
}

interface Timing {
  expiresAt: number;
  resendAt: number;
}

function SignedOutConnect({ keyOf, onDead }: { keyOf: InvitationKey; onDead: () => void }) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const [destination, setDestination] = useState<ContactDestination | null>(null);
  const [timing, setTiming] = useState<Timing>({ expiresAt: 0, resendAt: 0 });
  const [codeLength, setCodeLength] = useState(OTP_LENGTH);

  function onSent(next: ContactDestination, challenge: InvitationChallenge) {
    const sent = nowMs();
    setDestination(next);
    setCodeLength(challenge.codeLength);
    setTiming({ expiresAt: sent + challenge.expiresIn * 1000, resendAt: sent + RESEND_COOLDOWN_SECONDS * 1000 });
  }

  return (
    <View style={{ gap: spacing[4] }}>
      <Text tone="muted">{t('invite.howItWorks')}</Text>
      {destination === null ? (
        <ContactStep keyOf={keyOf} onSent={onSent} onDead={onDead} />
      ) : (
        <CodeStep
          keyOf={keyOf}
          destination={destination}
          codeLength={codeLength}
          timing={timing}
          onSent={onSent}
          onChangeContact={() => setDestination(null)}
          onDead={onDead}
        />
      )}
    </View>
  );
}

/** Seconds of a rate-limit block as a deadline, shared by both steps. */
function useBlock() {
  const [until, setUntil] = useState<number | null>(null);
  const now = useNow();
  return { left: until ? secondsLeft(until, now) : 0, start: (seconds: number) => setUntil(nowMs() + seconds * 1000) };
}

function ContactStep({
  keyOf,
  onSent,
  onDead,
}: {
  keyOf: InvitationKey;
  onSent: (destination: ContactDestination, challenge: InvitationChallenge) => void;
  onDead: () => void;
}) {
  const t = useTranslations();
  const [value, setValue] = useState('');
  const [fieldError, setFieldError] = useState<string>();
  const block = useBlock();

  const send = useMutation({
    networkMode: 'always',
    retry: false,
    mutationFn: async (destination: ContactDestination) => ({ destination, challenge: await requestInvitationOtp(keyOf, destination) }),
    onSuccess: ({ destination, challenge }) => onSent(destination, challenge),
    onError: (error) => {
      if (isDeadInvitation(invitationProblemOf(error))) return onDead();
      const wait = retryAfterOfInvitation(error);
      if (wait) block.start(wait);
    },
  });

  function submit() {
    const parsed = parseDestinationInput(value);
    if (!parsed.ok) {
      setFieldError(t(parsed.key));
      return;
    }
    setFieldError(undefined);
    send.mutate(parsed.destination);
  }

  const failure = send.isError ? invitationMessageFor(send.error) : null;
  return (
    <View style={{ gap: 16 }}>
      <TextField
        label={t('invite.contactLabel')}
        hint={t('invite.contactHint')}
        error={fieldError}
        value={value}
        onChangeText={(next) => {
          setValue(next);
          if (fieldError) setFieldError(undefined);
        }}
        keyboardType="email-address"
        autoCapitalize="none"
        autoCorrect={false}
        autoComplete="off"
        returnKeyType="send"
        onSubmitEditing={submit}
        editable={!send.isPending}
      />
      {failure ? (
        <Text tone="destructive" accessibilityRole="alert">
          {failure.detail ?? t(failure.message.key, 'values' in failure.message ? failure.message.values : undefined)}
        </Text>
      ) : null}
      <Button
        label={block.left > 0 ? t('invite.tryAgainIn', { seconds: block.left }) : t('invite.sendCode')}
        onPress={submit}
        loading={send.isPending}
        disabled={block.left > 0}
      />
    </View>
  );
}

function CodeStep({
  keyOf,
  destination,
  codeLength,
  timing,
  onSent,
  onChangeContact,
  onDead,
}: {
  keyOf: InvitationKey;
  destination: ContactDestination;
  codeLength: number;
  timing: Timing;
  onSent: (destination: ContactDestination, challenge: InvitationChallenge) => void;
  onChangeContact: () => void;
  onDead: () => void;
}) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const { completeSignIn } = useSession();
  const locale = useLocale();
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [language, setLanguage] = useState(locale);
  const [fieldError, setFieldError] = useState<string>();
  const [resent, setResent] = useState(false);
  const input = useRef<ComponentRef<typeof TextInput>>(null);
  const now = useNow();
  const block = useBlock();
  const expired = secondsLeft(timing.expiresAt, now) === 0;
  const resendIn = secondsLeft(timing.resendAt, now);

  const accept = useMutation({
    networkMode: 'always',
    retry: false,
    mutationFn: async () => {
      const tokens = await acceptInvitation({
        key: keyOf,
        destination,
        otpCode: code,
        ...(name.trim() ? { displayName: name.trim() } : {}),
        preferredLanguage: language,
      });
      await completeSignIn(tokens);
    },
    onSuccess: () => router.replace('/'),
    onError: (error) => {
      if (isDeadInvitation(invitationProblemOf(error))) return onDead();
      const wait = retryAfterOfInvitation(error);
      if (wait) block.start(wait);
      setCode('');
      input.current?.focus();
    },
  });

  const resend = useMutation({
    networkMode: 'always',
    retry: false,
    mutationFn: () => requestInvitationOtp(keyOf, destination),
    onSuccess: (challenge) => {
      onSent(destination, challenge);
      setCode('');
      setResent(true);
    },
    onError: (error) => {
      if (isDeadInvitation(invitationProblemOf(error))) return onDead();
      const wait = retryAfterOfInvitation(error);
      if (wait) block.start(wait);
    },
  });

  function submit() {
    const parsed = otpCodeSchema.safeParse(code);
    if (!parsed.success) {
      setFieldError(t('validation.otp', { length: OTP_LENGTH }));
      return;
    }
    setFieldError(undefined);
    setResent(false);
    accept.mutate();
  }

  const failure = accept.isError ? invitationMessageFor(accept.error) : resend.isError ? invitationMessageFor(resend.error) : null;
  const busy = accept.isPending || resend.isPending;
  const destinationText = destination.phone ?? destination.email ?? '';

  return (
    <View style={{ gap: spacing[4] }}>
      <Text tone="muted">{t('invite.codeSentTo', { length: codeLength, destination: destinationText })}</Text>
      <TextField
        ref={input}
        label={t('invite.codeLabel')}
        error={fieldError}
        value={code}
        onChangeText={(next) => {
          setCode(sanitizeCode(next, codeLength));
          if (fieldError) setFieldError(undefined);
        }}
        keyboardType="number-pad"
        textContentType="oneTimeCode"
        autoComplete="sms-otp"
        autoCorrect={false}
        autoFocus
        maxLength={codeLength + 4}
        editable={!busy}
        style={{ textAlign: 'center', fontSize: 28, letterSpacing: 8 }}
      />
      <View accessibilityLiveRegion="polite" style={{ gap: spacing[1] }}>
        {resent ? <Text tone="muted">{t('invite.resent')}</Text> : null}
        <Text variant="caption">
          {expired ? t('invite.expired') : t('invite.expiresIn', { time: formatCountdown(secondsLeft(timing.expiresAt, now)) })}
        </Text>
      </View>

      <TextField
        label={t('invite.nameLabel')}
        hint={t('invite.nameHint')}
        value={name}
        onChangeText={setName}
        autoComplete="name"
        textContentType="name"
        maxLength={100}
        editable={!busy}
      />
      <View style={{ gap: spacing[2] }}>
        <Text variant="label">{t('invite.languageLabel')}</Text>
        <SegmentedControl
          accessibilityLabel={t('invite.languageLabel')}
          value={language}
          onChange={(value) => isSupportedLocale(value) && setLanguage(value)}
          options={SUPPORTED_LOCALES.map((l) => ({ value: l.code, label: l.nativeName }))}
        />
      </View>

      {failure ? (
        <Text tone="destructive" accessibilityRole="alert">
          {failure.detail ?? t(failure.message.key, 'values' in failure.message ? failure.message.values : undefined)}
        </Text>
      ) : null}

      <Button
        label={block.left > 0 ? t('invite.tryAgainIn', { seconds: block.left }) : t('invite.accept')}
        onPress={submit}
        loading={accept.isPending}
        disabled={code.length < codeLength || block.left > 0 || resend.isPending}
      />
      <Button
        variant="ghost"
        label={resendIn > 0 ? t('invite.resendIn', { seconds: resendIn }) : t('invite.resend')}
        onPress={() => resend.mutate()}
        loading={resend.isPending}
        disabled={resendIn > 0 || accept.isPending || block.left > 0}
      />
      <Button variant="ghost" label={t('invite.changeContact')} onPress={onChangeContact} disabled={busy} />
    </View>
  );
}
