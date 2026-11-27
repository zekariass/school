"use client";

import { useLocale, useTranslations } from "next-intl";
import { useId, useState, useSyncExternalStore } from "react";
import { formatLoginTextCode, isCompleteLoginTextCode, loginCodeToSend } from "@school/validation/login-code";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Link } from "@/i18n/navigation";
import { getBrowserApi } from "@/lib/api-client";
import { failureOf, type StudentCodeFailure } from "@/lib/auth/student-code";
import { resolvePostSignInPath } from "@/lib/post-sign-in";
import { readProblem, secondsUntil } from "@/lib/problem";
import { QrScanner } from "./qr-scanner";

function subscribeToClock(onTick: () => void) {
  const id = window.setInterval(onTick, 1000);
  return () => window.clearInterval(id);
}
const readClock = () => Math.floor(Date.now() / 1000) * 1000;

/** Whole seconds until `deadline` (epoch ms); re-renders every second. */
function useSecondsLeft(deadline: number | null): number {
  const now = useSyncExternalStore(subscribeToClock, readClock, () => 0);
  return deadline === null ? 0 : secondsUntil(deadline, now);
}

/**
 * The class-code sign-in of a managed student (D41): scan the QR code the teacher printed with the camera, or type the text
 * code. The code lives only in this component's state while it is typed or scanned: never in the address, storage, a query
 * key or a log, and it is sent once, to our own Route Handler (`POST /api/auth/login-code`), which sets the session cookies
 * and returns no token. Every failure looks like a wrong code; a rate limit shows how long to wait.
 */
export function StudentCodeForm() {
  const locale = useLocale();
  const [text, setText] = useState("");
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<StudentCodeFailure | null>(null);
  const [blockedUntil, setBlockedUntil] = useState<number | null>(null);
  const blockedFor = useSecondsLeft(blockedUntil);

  async function submit(raw: string) {
    const code = loginCodeToSend(raw);
    if (pending || blockedFor > 0) return;
    if (!code) {
      setFailure({ kind: "invalid" });
      return;
    }
    setFailure(null);
    setPending(true);
    let answer: Parameters<typeof failureOf>[0];
    try {
      const response = await fetch("/api/auth/login-code", {
        method: "POST",
        headers: { "content-type": "application/json", "accept-language": locale },
        body: JSON.stringify({ code }),
      });
      if (response.ok) {
        // Signed in: the cookies are set. Ask who we are to decide where to go (a full navigation, so the server
        // renders the next page with the new cookies). The code is forgotten first.
        setText("");
        let destination = `/${locale}/student-app`;
        try {
          const { data: me } = await getBrowserApi().GET("/api/v1/me");
          if (me) destination = resolvePostSignInPath({ me, locale });
        } catch {
          // keep the student home
        }
        window.location.assign(destination);
        return;
      }
      const problem = await readProblem(response);
      answer = { status: response.status, code: problem?.code, detail: problem?.detail ?? problem?.title, retryAfter: response.headers.get("retry-after") };
    } catch {
      answer = { status: 0 };
    }
    setPending(false);
    const next = failureOf(answer);
    setFailure(next);
    if (next.kind === "rateLimited") setBlockedUntil(Date.now() + next.seconds * 1000);
    if (next.kind !== "unavailable") setText("");
  }

  return (
    <StudentCodeView
      text={text}
      onText={(value) => {
        setText(formatLoginTextCode(value));
        if (failure && failure.kind !== "rateLimited") setFailure(null);
      }}
      onSubmit={() => void submit(text)}
      onScanned={(code) => void submit(code)}
      pending={pending}
      failure={failure}
      blockedFor={blockedFor}
    />
  );
}

/** The screen itself, without any request: what it shows for a typed code, a failure, a wait and a pending sign-in. */
export function StudentCodeView({
  text,
  onText,
  onSubmit,
  onScanned,
  pending,
  failure,
  blockedFor,
}: {
  text: string;
  onText: (value: string) => void;
  onSubmit: () => void;
  onScanned: (code: string) => void;
  pending: boolean;
  failure: StudentCodeFailure | null;
  blockedFor: number;
}) {
  const t = useTranslations("auth.studentCode");
  const id = useId();
  const ready = isCompleteLoginTextCode(text);
  const failureText =
    failure?.kind === "invalid"
      ? (failure.detail ?? t("errors.invalid"))
      : failure?.kind === "rateLimited"
        ? blockedFor > 0
          ? t("errors.rateLimited", { seconds: blockedFor })
          : null
        : failure?.kind === "unavailable"
          ? t("errors.unavailable")
          : null;
  const inputId = `${id}-code`;
  const errorId = `${id}-error`;

  return (
    <Card className="w-full max-w-md shadow-md" data-testid="student-code">
      <CardHeader>
        <CardTitle role="heading" aria-level={1} className="text-2xl">
          {t("title")}
        </CardTitle>
        <CardDescription>{t("subtitle")}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-6">
        <section aria-label={t("scan.title")} className="flex flex-col gap-3">
          <h2 className="text-lg font-bold">{t("scan.title")}</h2>
          <QrScanner onCode={onScanned} paused={pending || blockedFor > 0} />
        </section>

        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            if (ready) onSubmit();
          }}
        >
          <h2 className="text-lg font-bold">{t("type.title")}</h2>
          <div className="flex flex-col gap-2">
            <Label htmlFor={inputId}>{t("type.label")}</Label>
            <Input
              id={inputId}
              name="class-code"
              type="text"
              value={text}
              onChange={(event) => onText(event.target.value)}
              placeholder="XXXXX-XXXXX"
              autoCapitalize="characters"
              autoCorrect="off"
              autoComplete="off"
              spellCheck={false}
              maxLength={16}
              disabled={pending}
              aria-invalid={failureText ? true : undefined}
              aria-describedby={failureText ? errorId : undefined}
              className="h-14 text-center font-mono text-2xl tracking-widest"
              data-testid="student-code-input"
            />
            <p className="text-sm text-muted-foreground">{t("type.hint")}</p>
          </div>

          <Button type="submit" size="lg" disabled={!ready || pending || blockedFor > 0} data-testid="student-code-submit">
            {pending ? t("signingIn") : blockedFor > 0 ? t("tryAgainIn", { seconds: blockedFor }) : t("submit")}
          </Button>

          <div aria-live="polite" className="empty:hidden">
            {failureText ? (
              <p id={errorId} role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="student-code-error">
                {failureText}
              </p>
            ) : null}
          </div>
        </form>

        <Button asChild variant="ghost">
          <Link href="/sign-in">{t("teacherLink")}</Link>
        </Button>
      </CardContent>
    </Card>
  );
}
