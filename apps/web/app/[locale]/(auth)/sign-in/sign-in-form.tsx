"use client";

import { useId, useRef, useState, useSyncExternalStore } from "react";
import { useLocale, useTranslations } from "next-intl";
import { useSearchParams } from "next/navigation";
import {
  OTP_LENGTH,
  otpCodeSchema,
  phoneSchema,
  toFieldErrors,
  type FieldError,
} from "@school/validation";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { getBrowserApi } from "@/lib/api-client";
import { resolvePostSignInPath } from "@/lib/post-sign-in";
import { formatCountdown, readProblem, retryAfterSeconds, secondsUntil } from "@/lib/problem";

type Translate = (key: string, values?: Record<string, string | number>) => string;

/** How long before the code can be requested again. */
const RESEND_COOLDOWN_SECONDS = 30;
const DEFAULT_CODE_TTL_SECONDS = 300;

type Step =
  | { name: "phone" }
  | {
      name: "code";
      /** E.164 */
      phone: string;
      expiresAt: number;
      resendAt: number;
    };

function subscribeToClock(onTick: () => void) {
  const id = window.setInterval(onTick, 1000);
  return () => window.clearInterval(id);
}

/** The current time, truncated to the second so the snapshot is stable between ticks. */
function readClock() {
  return Math.floor(Date.now() / 1000) * 1000;
}

/** Whole seconds until `deadline` (epoch ms); re-renders every second. */
function useSecondsLeft(deadline: number | null): number {
  const now = useSyncExternalStore(subscribeToClock, readClock, () => 0);
  return deadline === null ? 0 : secondsUntil(deadline, now);
}

export function SignInForm() {
  const [step, setStep] = useState<Step>({ name: "phone" });
  return step.name === "phone" ? (
    <PhoneStep
      onSent={(phone, expiresIn) => {
        const now = Date.now();
        setStep({
          name: "code",
          phone,
          expiresAt: now + expiresIn * 1000,
          resendAt: now + RESEND_COOLDOWN_SECONDS * 1000,
        });
      }}
    />
  ) : (
    <CodeStep
      step={step}
      onResent={(expiresIn) => {
        const now = Date.now();
        setStep({
          ...step,
          expiresAt: now + expiresIn * 1000,
          resendAt: now + RESEND_COOLDOWN_SECONDS * 1000,
        });
      }}
      onChangeNumber={() => setStep({ name: "phone" })}
    />
  );
}

type RequestOutcome =
  | { ok: true; expiresIn: number }
  | { ok: false; message: string; retryAfter: number | null };

/** Asks the BFF to text a code; maps every failure to a message for the user. */
async function requestCode(phone: string, locale: string, fallback: string): Promise<RequestOutcome> {
  let response: Response;
  try {
    response = await fetch("/api/auth/otp/request", {
      method: "POST",
      headers: { "content-type": "application/json", "accept-language": locale },
      body: JSON.stringify({ phone }),
    });
  } catch {
    return { ok: false, message: fallback, retryAfter: null };
  }
  if (response.ok) {
    const body: unknown = await response.json().catch(() => null);
    const expiresIn =
      typeof body === "object" && body !== null && typeof (body as { expiresIn?: unknown }).expiresIn === "number"
        ? (body as { expiresIn: number }).expiresIn
        : DEFAULT_CODE_TTL_SECONDS;
    return { ok: true, expiresIn };
  }
  const problem = await readProblem(response);
  return {
    ok: false,
    message: problem?.detail ?? problem?.title ?? fallback,
    retryAfter: response.status === 429 ? retryAfterSeconds(response.headers.get("retry-after")) : null,
  };
}

function PhoneStep({ onSent }: { onSent: (phone: string, expiresIn: number) => void }) {
  const t = useTranslations();
  const translate = t as unknown as Translate;
  const locale = useLocale();
  const id = useId();
  const [phone, setPhone] = useState("");
  const [fieldError, setFieldError] = useState<FieldError | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [blockedUntil, setBlockedUntil] = useState<number | null>(null);
  const [pending, setPending] = useState(false);
  const blockedFor = useSecondsLeft(blockedUntil);

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFailure(null);
    const parsed = phoneSchema("ET").safeParse(phone);
    if (!parsed.success) {
      setFieldError(toFieldErrors(parsed.error)[""] ?? { key: "validation.phone", values: {} });
      return;
    }
    setFieldError(null);
    setPending(true);
    const outcome = await requestCode(parsed.data, locale, t("auth.requestFailed"));
    setPending(false);
    if (outcome.ok) {
      onSent(parsed.data, outcome.expiresIn);
      return;
    }
    setFailure(outcome.message);
    if (outcome.retryAfter !== null) setBlockedUntil(Date.now() + outcome.retryAfter * 1000);
  }

  const phoneId = `${id}-phone`;
  const errorId = `${id}-phone-error`;
  const hintId = `${id}-phone-hint`;

  return (
    <Card className="w-full max-w-md shadow-md">
      <CardHeader>
        <CardTitle role="heading" aria-level={1} className="text-2xl">
          {t("auth.signInTitle")}
        </CardTitle>
        <CardDescription>{t("auth.signInSubtitle")}</CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={onSubmit} noValidate className="flex flex-col gap-5">
          <div className="flex flex-col gap-2">
            <Label htmlFor={phoneId}>{t("auth.phoneNumber")}</Label>
            <Input
              id={phoneId}
              name="phone"
              type="tel"
              inputMode="tel"
              autoComplete="tel"
              autoFocus
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              aria-invalid={fieldError ? true : undefined}
              aria-describedby={fieldError ? `${hintId} ${errorId}` : hintId}
              disabled={pending}
            />
            <p id={hintId} className="text-sm text-muted-foreground">
              {t("auth.phoneHint")}
            </p>
            {fieldError ? (
              <p id={errorId} role="alert" className="text-sm font-medium text-destructive">
                {translate(fieldError.key, fieldError.values)}
              </p>
            ) : null}
          </div>

          <Button type="submit" size="lg" disabled={pending || blockedFor > 0}>
            {pending
              ? t("common.loading")
              : blockedFor > 0
                ? t("auth.tryAgainIn", { seconds: blockedFor })
                : t("auth.sendCode")}
          </Button>

          <div aria-live="polite" className="empty:hidden">
            {failure ? (
              <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
                {failure}
              </p>
            ) : null}
          </div>
        </form>
      </CardContent>
    </Card>
  );
}

function CodeStep({
  step,
  onResent,
  onChangeNumber,
}: {
  step: Extract<Step, { name: "code" }>;
  onResent: (expiresIn: number) => void;
  onChangeNumber: () => void;
}) {
  const t = useTranslations();
  const translate = t as unknown as Translate;
  const locale = useLocale();
  const search = useSearchParams();
  const id = useId();
  const inputRef = useRef<HTMLInputElement>(null);
  const [code, setCode] = useState("");
  const [fieldError, setFieldError] = useState<FieldError | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [blockedUntil, setBlockedUntil] = useState<number | null>(null);
  const [pending, setPending] = useState(false);
  const [resending, setResending] = useState(false);

  const expiresIn = useSecondsLeft(step.expiresAt);
  const resendIn = useSecondsLeft(step.resendAt);
  const blockedFor = useSecondsLeft(blockedUntil);

  async function verify(value: string) {
    const parsed = otpCodeSchema.safeParse(value);
    if (!parsed.success) {
      setFieldError(toFieldErrors(parsed.error)[""] ?? { key: "validation.otp", values: { length: OTP_LENGTH } });
      return;
    }
    setFieldError(null);
    setFailure(null);
    setNotice(null);
    setPending(true);
    try {
      const response = await fetch("/api/auth/otp/verify", {
        method: "POST",
        headers: { "content-type": "application/json", "accept-language": locale },
        body: JSON.stringify({ phone: step.phone, code: parsed.data }),
      });
      if (!response.ok) {
        const problem = await readProblem(response);
        setFailure(problem?.detail ?? problem?.title ?? t("auth.signInFailed"));
        if (response.status === 429) {
          const wait = retryAfterSeconds(response.headers.get("retry-after"));
          if (wait !== null) setBlockedUntil(Date.now() + wait * 1000);
        }
        setCode("");
        setPending(false);
        inputRef.current?.focus();
        return;
      }
      // Signed in: the cookies are set. Ask who we are to decide where to go.
      const { data: me } = await getBrowserApi().GET("/api/v1/me");
      const destination = me
        ? resolvePostSignInPath({ me, locale, next: search.get("next") })
        : `/${locale}/no-access`;
      // A full navigation, so the server renders the next page with the new cookies.
      window.location.assign(destination);
    } catch {
      setFailure(t("auth.signInFailed"));
      setPending(false);
    }
  }

  async function resend() {
    setResending(true);
    setFailure(null);
    setNotice(null);
    const outcome = await requestCode(step.phone, locale, t("auth.requestFailed"));
    setResending(false);
    if (outcome.ok) {
      setCode("");
      setNotice(t("auth.codeResent", { destination: step.phone }));
      onResent(outcome.expiresIn);
      inputRef.current?.focus();
      return;
    }
    setFailure(outcome.message);
    if (outcome.retryAfter !== null) setBlockedUntil(Date.now() + outcome.retryAfter * 1000);
  }

  const codeId = `${id}-code`;
  const errorId = `${id}-code-error`;
  const hintId = `${id}-code-hint`;
  const expired = expiresIn === 0;

  return (
    <Card className="w-full max-w-md shadow-md">
      <CardHeader>
        <CardTitle role="heading" aria-level={1} className="text-2xl">
          {t("auth.codeTitle")}
        </CardTitle>
        <CardDescription>
          {t("auth.enterCode", { length: OTP_LENGTH, destination: step.phone })}
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            void verify(code);
          }}
          noValidate
          className="flex flex-col gap-5"
        >
          <div className="flex flex-col gap-2">
            <Label htmlFor={codeId}>{t("auth.codeLabel")}</Label>
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
              onChange={(e) => {
                const digits = e.target.value.replace(/\D/g, "").slice(0, OTP_LENGTH);
                setCode(digits);
                if (digits.length === OTP_LENGTH && !pending) void verify(digits);
              }}
              className="h-14 text-center font-mono text-2xl tracking-[0.5em]"
              aria-invalid={fieldError ? true : undefined}
              aria-describedby={fieldError ? `${hintId} ${errorId}` : hintId}
              disabled={pending}
            />
            <p id={hintId} className="text-sm text-muted-foreground">
              {expired ? t("auth.codeExpired") : t("auth.codeExpiresIn", { time: formatCountdown(expiresIn) })}
            </p>
            {fieldError ? (
              <p id={errorId} role="alert" className="text-sm font-medium text-destructive">
                {translate(fieldError.key, fieldError.values)}
              </p>
            ) : null}
          </div>

          <Button type="submit" size="lg" disabled={pending || blockedFor > 0 || code.length < OTP_LENGTH}>
            {pending
              ? t("auth.signingIn")
              : blockedFor > 0
                ? t("auth.tryAgainIn", { seconds: blockedFor })
                : t("auth.verifyCode")}
          </Button>

          <div aria-live="polite" className="flex flex-col gap-2 empty:hidden">
            {failure ? (
              <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
                {failure}
              </p>
            ) : null}
            {notice ? (
              <p className="rounded-xl bg-success/10 p-3 text-sm font-medium text-success">{notice}</p>
            ) : null}
          </div>

          <div className="flex flex-wrap items-center justify-between gap-2">
            <Button
              type="button"
              variant="ghost"
              disabled={resendIn > 0 || resending || pending}
              onClick={() => void resend()}
            >
              {resendIn > 0 ? t("auth.resendIn", { seconds: resendIn }) : t("auth.resendCode")}
            </Button>
            <Button type="button" variant="ghost" disabled={pending} onClick={onChangeNumber}>
              {t("auth.changeNumber")}
            </Button>
          </div>
        </form>
      </CardContent>
    </Card>
  );
}
