"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { KeyRoundIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import {
  RELATIONSHIP_TYPES,
  formatParentCode,
  isCompleteParentCode,
  normalizeParentCode,
  type RelationshipType,
} from "@school/validation/guardians";
import { FormField } from "@/components/console/form-field";
import { SelectField } from "@/components/school/fields";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage } from "@/lib/console/errors";
import { familyKeys } from "@/lib/family/queries";

/**
 * "Connect with a parent code" for a signed-in adult: the printed code from the school. The code is
 * sent once in the request body (never in a URL), kept only in this field, and the school confirms
 * the link before anything is shown.
 */
export function ConnectCodeCard() {
  const t = useTranslations("family.connect");
  const tRel = useTranslations("family.relationship");
  const queryClient = useQueryClient();
  const [code, setCode] = useState("");
  const [relationship, setRelationship] = useState<RelationshipType>("MOTHER");
  const [incomplete, setIncomplete] = useState(false);
  const [done, setDone] = useState<string | null>(null);

  const connect = useMutation({
    gcTime: 0,
    mutationFn: async () =>
      unwrap(
        await getBrowserApi().POST("/api/v1/me/guardian-links", {
          body: { code: normalizeParentCode(code), relationshipType: relationship },
          cache: "no-store",
        }),
      ),
    onSuccess: async (link) => {
      setCode("");
      setDone(link.status === "ACTIVE" ? t("active", { child: link.studentName }) : t("sentNoSchool"));
      await queryClient.invalidateQueries({ queryKey: familyKeys.all });
    },
  });

  return (
    <Card className="shadow-none">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-lg">
          <KeyRoundIcon className="size-5" aria-hidden />
          {t("title")}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            const complete = isCompleteParentCode(code);
            setIncomplete(!complete);
            setDone(null);
            if (complete) connect.mutate();
          }}
        >
          <p className="text-sm text-muted-foreground">{t("body")}</p>
          <FormField label={t("codeLabel")} hint={t("codeHint")} error={incomplete ? t("incomplete") : null}>
            {(props) => (
              <Input
                {...props}
                name="parent-code"
                autoComplete="off"
                autoCapitalize="characters"
                spellCheck={false}
                inputMode="text"
                maxLength={12}
                className="h-12 font-mono text-lg tracking-widest"
                value={code}
                onChange={(e) => setCode(formatParentCode(e.target.value))}
                disabled={connect.isPending}
              />
            )}
          </FormField>
          <SelectField
            label={t("relationshipLabel")}
            value={relationship}
            onChange={(v) => setRelationship(v as RelationshipType)}
            options={RELATIONSHIP_TYPES.map((value) => ({ value, label: tRel(value) }))}
          />
          <Button type="submit" size="lg" disabled={connect.isPending}>
            {connect.isPending ? t("sending") : t("submit")}
          </Button>
          <div aria-live="polite" className="empty:hidden">
            {connect.isError ? (
              <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
                {errorMessage(connect.error, t("failed"))}
              </p>
            ) : null}
            {done ? <p className="rounded-xl bg-success/10 p-3 text-sm font-medium text-success">{done}</p> : null}
          </div>
        </form>
      </CardContent>
    </Card>
  );
}
