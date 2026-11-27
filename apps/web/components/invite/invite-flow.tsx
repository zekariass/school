"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { HeartHandshakeIcon, LinkIcon } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { useId, useRef, useState, useSyncExternalStore } from "react";
import { SUPPORTED_LOCALES } from "@school/i18n";
import { OTP_LENGTH, otpCodeSchema } from "@school/validation";
import {
  RELATIONSHIP_TYPES,
  classifyInvitationFailure,
  isDeadInvitation,
  isRelationshipType,
  parseDestinationInput,
  type ContactDestination,
  type RelationshipType,
} from "@school/validation/guardians";
import { PhoneCountryPicker } from "@/components/phone-country-picker";
import { SelectField } from "@/components/school/fields";
import { usePhoneCountry } from "@/components/use-phone-country";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { useRouter } from "@/i18n/navigation";
import { getBrowserApi } from "@/lib/api-client";
import { ApiRequestError } from "@/lib/api-error";
import { postJson, type CallFailure } from "@/lib/invite/calls";
import type { InvitationKey } from "@/lib/server/invitation-input";
import { formatCountdown } from "@/lib/problem";
import { useSecondsLeft } from "./use-countdown";

type InviteStep = "contact" | "code";

const RESEND_COOLDOWN_SECONDS = 30;
const DEFAULT_CODE_TTL_SECONDS = 300;

interface Preview {
  channel: string;
  childFirstName: string;
  relationshipType?: string;
  schoolName: string;
}

class PreviewFailure extends Error {
  constructor(readonly failure: CallFailure) {
    super("invitation preview failed");
  }
}

function usePreview(keyOf: InvitationKey, locale: string) {
  // The cache key carries no secret (the invitation key may be a printed code): one entry per mounted page.
  const mount = useId();
  return useQuery({
    queryKey: ["invitation-preview", mount],
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
    queryFn: async (): Promise<Preview> => {
      const result = await postJson<Preview>("/api/invitations/preview", keyOf, locale);
      if (!result.ok) throw new PreviewFailure(result.failure);
      return result.data;
    },
  });
}

/** Whether the visitor already has a session (a 401 means no). Only asked after a live preview. */
function useSignedInName(enabled: boolean) {
  return useQuery({
    queryKey: ["invitation-me"],
    enabled,
    retry: false,
    staleTime: 0,
    queryFn: async () => {
      const { data } = await getBrowserApi().GET("/api/v1/me");
      return data ? { name: data.displayName } : null;
    },
  });
}

function isMobileUserAgent() {
  return /Android|iPhone|iPad|iPod/i.test(navigator.userAgent);
}

/** The message for a failed call: the page's own copy for the cases it knows, else the API's localized detail. */
function useFailureText() {
  const t = useTranslations("invite");
  return (failure: CallFailure): string => {
    switch (failure.problem.kind) {
      case "contactUnavailable":
        return t("contactUnavailable");
      case "rateLimited":
        return t("rateLimited", { seconds: failure.problem.retryAfterSeconds ?? 60 });
      default:
        return failure.message ?? t("failed");
    }
  };
}

/** Accepting a guardian invitation from its link: preview, prove a phone or email, then connect and sign in. */
export function InviteFlow({ keyOf }: { keyOf: InvitationKey }) {
  const locale = useLocale();
  const preview = usePreview(keyOf, locale);
  const signedIn = useSignedInName(preview.isSuccess);
  const t = useTranslations("invite");
  const tc = useTranslations("common");
  const failureText = useFailureText();

  if (preview.isPending) {
    return (
      <div role="status" aria-label={t("loading")} className="w-full max-w-md">
        <Skeleton className="h-80 w-full rounded-2xl" />
      </div>
    );
  }
  if (preview.isError) {
    const failure = preview.error instanceof PreviewFailure ? preview.error.failure : null;
    // An unreachable server, a 5xx or a rate limit is not a dead invitation: let the visitor retry.
    if (failure && !isDeadInvitation(failure.problem)) {
      return (
        <Shell title={t("pageTitle")}>
          <div className="flex flex-col gap-4">
            <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
              {failure.problem.kind === "network" ? t("loadFailed") : failureText(failure)}
            </p>
            <Button onClick={() => void preview.refetch()}>{tc("retry")}</Button>
          </div>
        </Shell>
      );
    }
    return <DeadInvitation />;
  }
  return <Connect keyOf={keyOf} preview={preview.data} signedInAs={signedIn.data?.name ?? null} />;
}

function Shell({ title, description, children }: { title: string; description?: string; children?: React.ReactNode }) {
  return (
    <Card className="w-full max-w-md shadow-md">
      <CardHeader>
        <CardTitle role="heading" aria-level={1} className="text-2xl">
          {title}
        </CardTitle>
        {description ? <CardDescription>{description}</CardDescription> : null}
      </CardHeader>
      {children ? <CardContent>{children}</CardContent> : null}
    </Card>
  );
}

/** The one answer for every invitation that cannot be used: unknown, expired, cancelled or used up. */
export function DeadInvitation() {
  const t = useTranslations("invite");
  return (
    <Shell title={t("notFoundTitle")} description={t("notFoundBody")}>
      <div className="flex items-center gap-3 rounded-xl bg-muted/60 p-4 text-muted-foreground">
        <LinkIcon className="size-5 shrink-0" aria-hidden />
        <span className="text-sm">{t("pageTitle")}</span>
      </div>
    </Shell>
  );
}

interface Timing {
  expiresAt: number;
  resendAt: number;
}

function Connect({ keyOf, preview, signedInAs }: { keyOf: InvitationKey; preview: Preview; signedInAs: string | null }) {
  const t = useTranslations("invite");
  const tRel = useTranslations("family.relationship");
  const locale = useLocale();
  const [step, setStep] = useState<InviteStep>("contact");
  const [destination, setDestination] = useState<ContactDestination | null>(null);
  const [timing, setTiming] = useState<Timing>({ expiresAt: 0, resendAt: 0 });
  const [useAnother, setUseAnother] = useState(false);
  const [dead, setDead] = useState(false);
  const mobile = useSyncExternalStore(
    () => () => {},
    isMobileUserAgent,
    () => false,
  );

  if (dead) return <DeadInvitation />;

  const relationship = isRelationshipType(preview.relationshipType) ? preview.relationshipType : null;
  const title = t("connectTitle", { child: preview.childFirstName });
  const description = t("connectBody", { school: preview.schoolName, child: preview.childFirstName });

  if (signedInAs && !useAnother) {
    return (
      <Shell title={title} description={description}>
        <SignedInConnect
          keyOf={keyOf}
          name={signedInAs}
          relationship={relationship}
          onDead={() => setDead(true)}
          onUseAnother={() => setUseAnother(true)}
        />
      </Shell>
    );
  }

  return (
    <Shell title={title} description={description}>
      <div className="flex flex-col gap-5">
        <p className="flex items-start gap-3 rounded-xl bg-primary/10 p-3 text-sm">
          <HeartHandshakeIcon className="mt-0.5 size-5 shrink-0 text-primary" aria-hidden />
          <span>{t("howItWorks")}</span>
        </p>
        {relationship ? (
          <p className="text-sm text-muted-foreground">{t("relationshipLine", { relationship: tRel(relationship) })}</p>
        ) : null}
        {step === "contact" || !destination ? (
          <ContactStep
            keyOf={keyOf}
            locale={locale}
            onSent={(next, expiresIn) => {
              const now = Date.now();
              setDestination(next);
              setTiming({ expiresAt: now + expiresIn * 1000, resendAt: now + RESEND_COOLDOWN_SECONDS * 1000 });
              setStep("code");
            }}
            onDead={() => setDead(true)}
          />
        ) : (
          <CodeStep
            keyOf={keyOf}
            locale={locale}
            destination={destination}
            timing={timing}
            onTiming={setTiming}
            onChangeContact={() => setStep("contact")}
            onDead={() => setDead(true)}
          />
        )}
        {mobile && keyOf.token ? (
          <a
            href={`school://invite/${keyOf.token}`}
            className="inline-flex min-h-11 items-center justify-center rounded-xl text-sm font-semibold text-primary hover:underline"
          >
            {t("openApp")}
          </a>
        ) : null}
      </div>
    </Shell>
  );
}

function destinationLabel(destination: ContactDestination): string {
  return destination.phone ?? destination.email ?? "";
}

function codeTtl(body: unknown): number {
  const expiresIn =
    typeof body === "object" && body !== null ? (body as { expiresIn?: unknown }).expiresIn : undefined;
  return typeof expiresIn === "number" ? expiresIn : DEFAULT_CODE_TTL_SECONDS;
}

function ContactStep({
  keyOf,
  locale,
  onSent,
  onDead,
}: {
  keyOf: InvitationKey;
  locale: string;
  onSent: (destination: ContactDestination, expiresIn: number) => void;
  onDead: () => void;
}) {
  const t = useTranslations("invite");
  const tv = useTranslations("validation");
  const failureText = useFailureText();
  const id = useId();
  const [value, setValue] = useState("");
  const [fieldError, setFieldError] = useState<"required" | "phone" | "email" | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [blockedUntil, setBlockedUntil] = useState<number | null>(null);
  const [pending, setPending] = useState(false);
  const blockedFor = useSecondsLeft(blockedUntil);
  const phoneCountry = usePhoneCountry();

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFailure(null);
    const parsed = parseDestinationInput(value, phoneCountry.regionCode);
    if (!parsed.ok) {
      setFieldError(parsed.key.replace("validation.", "") as "required" | "phone" | "email");
      return;
    }
    setFieldError(null);
    setPending(true);
    const result = await postJson<{ expiresIn?: number }>("/api/invitations/otp", { ...keyOf, ...parsed.destination, country: phoneCountry.country?.code }, locale);
    setPending(false);
    if (result.ok) {
      onSent(parsed.destination, codeTtl(result.data));
      return;
    }
    if (isDeadInvitation(result.failure.problem)) return onDead();
    setFailure(failureText(result.failure));
    if (result.failure.problem.kind === "rateLimited") {
      setBlockedUntil(Date.now() + (result.failure.problem.retryAfterSeconds ?? 60) * 1000);
    }
  }

  const inputId = `${id}-contact`;
  const errorId = `${id}-contact-error`;
  const hintId = `${id}-contact-hint`;
  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-5">
      {phoneCountry.showPicker ? (
        <PhoneCountryPicker
          countries={phoneCountry.countries}
          value={phoneCountry.country?.code}
          onChange={phoneCountry.select}
          disabled={pending}
        />
      ) : null}
      <div className="flex flex-col gap-2">
        <Label htmlFor={inputId}>{t("contactLabel")}</Label>
        <Input
          id={inputId}
          name="contact"
          type="text"
          inputMode="email"
          autoComplete="tel email"
          autoCapitalize="none"
          autoFocus
          value={value}
          onChange={(e) => setValue(e.target.value)}
          aria-invalid={fieldError ? true : undefined}
          aria-describedby={fieldError ? `${hintId} ${errorId}` : hintId}
          disabled={pending}
        />
        <p id={hintId} className="text-sm text-muted-foreground">
          {t("contactHint")}
        </p>
        {fieldError ? (
          <p id={errorId} role="alert" className="text-sm font-medium text-destructive">
            {tv(fieldError)}
          </p>
        ) : null}
      </div>
      <Button type="submit" size="lg" disabled={pending || phoneCountry.loading || blockedFor > 0}>
        {pending ? t("sending") : blockedFor > 0 ? t("tryAgainIn", { seconds: blockedFor }) : t("sendCode")}
      </Button>
      <div aria-live="polite" className="empty:hidden">
        {failure ? (
          <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
            {failure}
          </p>
        ) : null}
      </div>
    </form>
  );
}

function CodeStep({
  keyOf,
  locale,
  destination,
  timing,
  onTiming,
  onChangeContact,
  onDead,
}: {
  keyOf: InvitationKey;
  locale: string;
  destination: ContactDestination;
  timing: Timing;
  onTiming: (timing: Timing) => void;
  onChangeContact: () => void;
  onDead: () => void;
}) {
  const t = useTranslations("invite");
  const tv = useTranslations("validation");
  const failureText = useFailureText();
  const id = useId();
  const inputRef = useRef<HTMLInputElement>(null);
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [language, setLanguage] = useState(locale);
  const [fieldError, setFieldError] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [blockedUntil, setBlockedUntil] = useState<number | null>(null);
  const [pending, setPending] = useState(false);
  const [resending, setResending] = useState(false);
  const expiresIn = useSecondsLeft(timing.expiresAt);
  const resendIn = useSecondsLeft(timing.resendAt);
  const blockedFor = useSecondsLeft(blockedUntil);

  async function accept() {
    const parsed = otpCodeSchema.safeParse(code);
    if (!parsed.success) {
      setFieldError(true);
      return;
    }
    setFieldError(false);
    setFailure(null);
    setNotice(null);
    setPending(true);
    const result = await postJson("/api/invitations/accept", {
      ...keyOf,
      ...destination,
      otpCode: parsed.data,
      displayName: name.trim() || undefined,
      preferredLanguage: language,
    }, locale);
    if (result.ok) {
      // Signed in: the cookies are set. A full navigation, so the server renders with them.
      const destination = `/${locale}/family`;
      // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- a full load, so the server reads the new cookies
      window.location.assign(destination);
      return;
    }
    setPending(false);
    if (isDeadInvitation(result.failure.problem)) return onDead();
    setFailure(failureText(result.failure));
    if (result.failure.problem.kind === "rateLimited") {
      setBlockedUntil(Date.now() + (result.failure.problem.retryAfterSeconds ?? 60) * 1000);
    }
    setCode("");
    inputRef.current?.focus();
  }

  async function resend() {
    setResending(true);
    setFailure(null);
    setNotice(null);
    const result = await postJson<{ expiresIn?: number }>("/api/invitations/otp", { ...keyOf, ...destination }, locale);
    setResending(false);
    if (result.ok) {
      const now = Date.now();
      onTiming({ expiresAt: now + codeTtl(result.data) * 1000, resendAt: now + RESEND_COOLDOWN_SECONDS * 1000 });
      setCode("");
      setNotice(t("resent"));
      inputRef.current?.focus();
      return;
    }
    if (isDeadInvitation(result.failure.problem)) return onDead();
    setFailure(failureText(result.failure));
    if (result.failure.problem.kind === "rateLimited") {
      setBlockedUntil(Date.now() + (result.failure.problem.retryAfterSeconds ?? 60) * 1000);
    }
  }

  const codeId = `${id}-code`;
  const errorId = `${id}-code-error`;
  const hintId = `${id}-code-hint`;
  const expired = expiresIn === 0;
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        void accept();
      }}
      noValidate
      className="flex flex-col gap-5"
    >
      <p className="text-sm text-muted-foreground">
        {t("codeSentTo", { length: OTP_LENGTH, destination: destinationLabel(destination) })}
      </p>
      <div className="flex flex-col gap-2">
        <Label htmlFor={codeId}>{t("codeLabel")}</Label>
        <Input
          ref={inputRef}
          id={codeId}
          name="code"
          type="text"
          inputMode="numeric"
          pattern="[0-9]*"
          autoComplete="one-time-code"
          maxLength={OTP_LENGTH}
          autoFocus
          value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, OTP_LENGTH))}
          className="h-14 text-center font-mono text-2xl tracking-[0.5em]"
          aria-invalid={fieldError ? true : undefined}
          aria-describedby={fieldError ? `${hintId} ${errorId}` : hintId}
          disabled={pending}
        />
        <p id={hintId} className="text-sm text-muted-foreground">
          {expired ? t("expired") : t("expiresIn", { time: formatCountdown(expiresIn) })}
        </p>
        {fieldError ? (
          <p id={errorId} role="alert" className="text-sm font-medium text-destructive">
            {tv("otp", { length: OTP_LENGTH })}
          </p>
        ) : null}
      </div>

      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-name`}>{t("nameLabel")}</Label>
        <Input
          id={`${id}-name`}
          name="name"
          autoComplete="name"
          maxLength={100}
          value={name}
          onChange={(e) => setName(e.target.value)}
          disabled={pending}
        />
        <p className="text-sm text-muted-foreground">{t("nameHint")}</p>
      </div>

      <SelectField
        label={t("languageLabel")}
        value={language}
        onChange={setLanguage}
        options={SUPPORTED_LOCALES.map((l) => ({ value: l.code, label: l.nativeName }))}
      />

      <Button type="submit" size="lg" disabled={pending || blockedFor > 0 || code.length < OTP_LENGTH}>
        {pending ? t("accepting") : blockedFor > 0 ? t("tryAgainIn", { seconds: blockedFor }) : t("accept")}
      </Button>

      <div aria-live="polite" className="flex flex-col gap-2 empty:hidden">
        {failure ? (
          <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
            {failure}
          </p>
        ) : null}
        {notice ? <p className="rounded-xl bg-success/10 p-3 text-sm font-medium text-success">{notice}</p> : null}
      </div>

      <div className="flex flex-wrap items-center justify-between gap-2">
        <Button type="button" variant="ghost" disabled={resendIn > 0 || resending || pending} onClick={() => void resend()}>
          {resendIn > 0 ? t("resendIn", { seconds: resendIn }) : t("resend")}
        </Button>
        <Button type="button" variant="ghost" disabled={pending} onClick={onChangeContact}>
          {t("changeContact")}
        </Button>
      </div>
    </form>
  );
}

/** A visitor who is already signed in connects the invitation to their own account. */
function SignedInConnect({
  keyOf,
  name,
  relationship,
  onDead,
  onUseAnother,
}: {
  keyOf: InvitationKey;
  name: string;
  relationship: RelationshipType | null;
  onDead: () => void;
  onUseAnother: () => void;
}) {
  const t = useTranslations("invite");
  const tRel = useTranslations("family.relationship");
  const tConnect = useTranslations("family.connect");
  const failureText = useFailureText();
  const router = useRouter();
  const [chosen, setChosen] = useState<RelationshipType>(relationship ?? "MOTHER");

  const connect = useMutation({
    mutationFn: async () => {
      const { data, error, response } = await getBrowserApi().POST("/api/v1/me/guardian-links", {
        body: { ...keyOf, relationshipType: chosen },
      });
      if (data) return data;
      throw new ApiRequestError(response.status, error && typeof error === "object" ? (error as never) : undefined);
    },
    onSuccess: () => router.replace("/family"),
    onError: (error) => {
      const problem =
        error instanceof ApiRequestError
          ? classifyInvitationFailure(error.status, error.problem?.code)
          : classifyInvitationFailure(undefined, undefined);
      if (isDeadInvitation(problem)) onDead();
    },
  });
  const failure =
    connect.error instanceof ApiRequestError
      ? failureText({
          problem: classifyInvitationFailure(connect.error.status, connect.error.problem?.code),
          message: connect.error.problem?.detail ?? null,
        })
      : connect.isError
        ? failureText({ problem: { kind: "network" }, message: null })
        : null;

  return (
    <div className="flex flex-col gap-5">
      <div>
        <p className="font-semibold">{t("alreadySignedInTitle")}</p>
        <p className="text-sm text-muted-foreground">{name}</p>
        <p className="text-sm text-muted-foreground">{t("alreadySignedInBody")}</p>
      </div>
      {relationship === null ? (
        <SelectField
          label={tConnect("relationshipLabel")}
          value={chosen}
          onChange={(v) => setChosen(v as RelationshipType)}
          options={RELATIONSHIP_TYPES.map((value) => ({ value, label: tRel(value) }))}
        />
      ) : null}
      <Button size="lg" onClick={() => connect.mutate()} disabled={connect.isPending}>
        {connect.isPending ? t("accepting") : t("connectSignedIn")}
      </Button>
      <Button variant="ghost" onClick={onUseAnother} disabled={connect.isPending}>
        {t("changeContact")}
      </Button>
      <div aria-live="polite" className="empty:hidden">
        {failure ? (
          <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
            {failure}
          </p>
        ) : null}
      </div>
    </div>
  );
}
