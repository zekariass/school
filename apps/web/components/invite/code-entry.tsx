"use client";

import { KeyRoundIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useId, useState } from "react";
import { formatParentCode, isCompleteParentCode, normalizeParentCode } from "@school/validation/guardians";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { InviteFlow } from "./invite-flow";

/**
 * "I have a parent code": for a parent who holds the printed code but has no account yet (an unknown
 * phone number cannot sign in, so the code is also accepted before signing in). The code stays in
 * this component's state: never in the URL, storage or a query key.
 */
export function CodeEntry() {
  const t = useTranslations("family.connect");
  const id = useId();
  const [value, setValue] = useState("");
  const [code, setCode] = useState<string | null>(null);
  const [incomplete, setIncomplete] = useState(false);

  // Remounted with the code as key, so another code starts a fresh preview.
  if (code) return <InviteFlow key={code} keyOf={{ code }} />;

  return (
    <Card className="w-full max-w-md shadow-md">
      <CardHeader>
        <CardTitle role="heading" aria-level={1} className="flex items-center gap-2 text-2xl">
          <KeyRoundIcon className="size-6" aria-hidden />
          {t("title")}
        </CardTitle>
        <CardDescription>{t("body")}</CardDescription>
      </CardHeader>
      <CardContent>
        <form
          noValidate
          className="flex flex-col gap-5"
          onSubmit={(event) => {
            event.preventDefault();
            const complete = isCompleteParentCode(value);
            setIncomplete(!complete);
            if (complete) setCode(normalizeParentCode(value));
          }}
        >
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-code`}>{t("codeLabel")}</Label>
            <Input
              id={`${id}-code`}
              name="parent-code"
              autoFocus
              autoComplete="off"
              autoCapitalize="characters"
              spellCheck={false}
              maxLength={12}
              className="h-14 text-center font-mono text-2xl tracking-widest"
              value={value}
              onChange={(e) => setValue(formatParentCode(e.target.value))}
              aria-invalid={incomplete ? true : undefined}
              aria-describedby={`${id}-hint`}
            />
            <p id={`${id}-hint`} className={incomplete ? "text-sm font-medium text-destructive" : "text-sm text-muted-foreground"} role={incomplete ? "alert" : undefined}>
              {incomplete ? t("incomplete") : t("codeHint")}
            </p>
          </div>
          <Button type="submit" size="lg">
            {t("continue")}
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
