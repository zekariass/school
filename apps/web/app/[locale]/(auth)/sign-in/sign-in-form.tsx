"use client";

import { useId, useState, useTransition } from "react";
import { useLocale, useTranslations } from "next-intl";
import { phoneSchema, toFieldErrors, type FieldError } from "@school/validation";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { requestSignInCode, type RequestSignInCodeResult } from "./actions";

type Translate = (key: string, values?: Record<string, string | number>) => string;

export function SignInForm() {
  const t = useTranslations();
  const translate = t as unknown as Translate;
  const locale = useLocale();
  const id = useId();
  const [phone, setPhone] = useState("");
  const [fieldError, setFieldError] = useState<FieldError | null>(null);
  const [result, setResult] = useState<RequestSignInCodeResult | null>(null);
  const [pending, startTransition] = useTransition();

  function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setResult(null);
    const parsed = phoneSchema("ET").safeParse(phone);
    if (!parsed.success) {
      setFieldError(toFieldErrors(parsed.error)[""] ?? { key: "validation.phone", values: {} });
      return;
    }
    setFieldError(null);
    startTransition(async () => {
      const outcome = await requestSignInCode({ phone, locale });
      if (outcome.status === "invalid") setFieldError(outcome.error);
      else setResult(outcome);
    });
  }

  const phoneId = `${id}-phone`;
  const errorId = `${id}-phone-error`;
  const hintId = `${id}-phone-hint`;

  return (
    <Card className="w-full max-w-md shadow-md">
      <CardHeader>
        <CardTitle role="heading" aria-level={1} className="text-2xl">{t("auth.signInTitle")}</CardTitle>
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

          <Button type="submit" size="lg" disabled={pending}>
            {pending ? t("common.loading") : t("auth.sendCode")}
          </Button>

          <div aria-live="polite" className="empty:hidden">
            {result ? <Outcome result={result} /> : null}
          </div>
        </form>
      </CardContent>
    </Card>
  );
}

function Outcome({ result }: { result: RequestSignInCodeResult }) {
  const t = useTranslations();

  if (result.status === "sent") {
    return (
      <p className="rounded-xl bg-success/10 p-3 text-sm font-medium text-success">
        {t("auth.codeRequested", { destination: result.destination })}
      </p>
    );
  }
  if (result.status === "problem" && (result.title || result.detail)) {
    return (
      <div role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm text-destructive">
        {result.title ? <p className="font-semibold">{result.title}</p> : null}
        {result.detail ? <p>{result.detail}</p> : null}
      </div>
    );
  }
  return (
    <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
      {t("auth.requestFailed")}
    </p>
  );
}
