"use client";

import { useQuery } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { useRouter } from "@/i18n/navigation";
import { unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage, fieldErrors, hasProblemCode } from "@/lib/console/errors";
import {
  fetchCountries,
  fetchOrganizations,
  queryKeys,
  type Country,
} from "@/lib/console/queries";
import { isValidSlug, slugify, SLUG_MAX, SLUG_MIN } from "@/lib/console/slug";
import { FormField } from "./form-field";
import { useSubmit } from "./use-submit";

const NONE = "NONE";

/** Create a school; language, timezone and calendar default from the chosen country. */
export function SchoolForm() {
  const t = useTranslations("console");
  const locale = useLocale();
  const router = useRouter();

  const countries = useQuery({ queryKey: queryKeys.countries, queryFn: fetchCountries, staleTime: 600_000 });
  const organizations = useQuery({
    queryKey: queryKeys.organizations,
    queryFn: () => fetchOrganizations(),
  });

  const [name, setName] = useState("");
  const [slug, setSlug] = useState("");
  const [slugTouched, setSlugTouched] = useState(false);
  const [countryCode, setCountryCode] = useState<string | null>(null);
  const [organizationId, setOrganizationId] = useState(NONE);
  const [language, setLanguage] = useState<string | null>(null);
  const [timezone, setTimezone] = useState<string | null>(null);
  const [calendar, setCalendar] = useState<string | null>(null);
  const [clientErrors, setClientErrors] = useState<Record<string, string>>({});

  const list = countries.data ?? [];
  // The country (and the defaults that follow it) fall back to the first enabled one until chosen.
  const country: Country | undefined = list.find((c) => c.code === countryCode) ?? list[0];
  const effectiveLanguage = language ?? country?.defaultLanguageCode ?? "";
  const effectiveTimezone = timezone ?? country?.defaultTimezone ?? "";
  const effectiveCalendar = calendar ?? country?.defaultCalendarCode ?? "";
  const orgChoices = (organizations.data?.items ?? []).filter((o) => o.countryCode === country?.code);
  const effectiveOrganization = orgChoices.some((o) => o.id === organizationId) ? organizationId : NONE;

  const submit = useSubmit({
    mutationFn: async (_: void, idempotencyKey) =>
      unwrap(
        await getBrowserApi().POST("/api/v1/platform/schools", {
          headers: { "Idempotency-Key": idempotencyKey },
          body: {
            name: name.trim(),
            slug,
            countryCode: country?.code ?? "",
            ...(effectiveOrganization !== NONE ? { organizationId: effectiveOrganization } : {}),
            defaultLanguageCode: effectiveLanguage,
            timezone: effectiveTimezone,
            defaultCalendarCode: effectiveCalendar,
          },
        }),
      ),
    invalidate: [queryKeys.schoolsAll],
    successMessage: t("schools.created"),
    errorFallback: t("saveFailed"),
    toastOnError: false,
    onSuccess: (school) => router.push(`/platform/schools/${school.id}`),
  });

  const serverErrors = fieldErrors(submit.error);
  const slugTaken = hasProblemCode(submit.error, "school.slug_taken");

  function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const errors: Record<string, string> = {};
    if (!name.trim()) errors.name = t("validation.required");
    if (!isValidSlug(slug)) errors.slug = t("schools.slugRule", { min: SLUG_MIN, max: SLUG_MAX });
    if (!country) errors.country = t("validation.required");
    setClientErrors(errors);
    if (Object.keys(errors).length === 0) submit.mutate();
  }

  const slugHint = slug
    ? isValidSlug(slug)
      ? t("schools.slugPreview", { slug })
      : t("schools.slugRule", { min: SLUG_MIN, max: SLUG_MAX })
    : t("schools.slugRule", { min: SLUG_MIN, max: SLUG_MAX });

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-6">
      <div>
        <h1 className="text-2xl font-bold">{t("schools.create")}</h1>
        <p className="text-muted-foreground">{t("schools.createSubtitle")}</p>
      </div>

      <Card>
        <CardContent>
          <form onSubmit={onSubmit} noValidate className="flex flex-col gap-5">
            <FormField label={t("fields.name")} error={clientErrors.name ?? serverErrors.name}>
              {(props) => (
                <Input
                  {...props}
                  value={name}
                  maxLength={200}
                  autoComplete="off"
                  onChange={(e) => {
                    setName(e.target.value);
                    if (!slugTouched) setSlug(slugify(e.target.value));
                  }}
                />
              )}
            </FormField>

            <FormField
              label={t("fields.slug")}
              hint={slugHint}
              error={clientErrors.slug ?? serverErrors.slug ?? (slugTaken ? t("schools.slugTaken") : undefined)}
            >
              {(props) => (
                <Input
                  {...props}
                  value={slug}
                  maxLength={SLUG_MAX}
                  autoComplete="off"
                  spellCheck={false}
                  className="font-mono"
                  onChange={(e) => {
                    setSlugTouched(true);
                    setSlug(e.target.value.toLowerCase());
                  }}
                />
              )}
            </FormField>

            <FormField label={t("fields.country")} error={clientErrors.country ?? serverErrors.countryCode}>
              {(props) => (
                <Select
                  value={country?.code ?? ""}
                  onValueChange={(value) => {
                    setCountryCode(value);
                    setLanguage(null);
                    setTimezone(null);
                    setCalendar(null);
                  }}
                >
                  <SelectTrigger {...props} className="w-full">
                    <SelectValue placeholder={countries.isPending ? t("loading") : undefined} />
                  </SelectTrigger>
                  <SelectContent>
                    {list.map((c) => (
                      <SelectItem key={c.code} value={c.code}>
                        {c.nameI18n[locale] ?? c.nameI18n.en ?? c.code}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )}
            </FormField>

            <FormField label={t("fields.organization")} hint={t("schools.organizationHint")}>
              {(props) => (
                <Select value={effectiveOrganization} onValueChange={setOrganizationId}>
                  <SelectTrigger {...props} className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value={NONE}>{t("schools.independent")}</SelectItem>
                    {orgChoices.map((o) => (
                      <SelectItem key={o.id} value={o.id}>
                        {o.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )}
            </FormField>

            <div className="grid gap-5 sm:grid-cols-3">
              <FormField label={t("fields.language")} error={serverErrors.defaultLanguageCode}>
                {(props) => (
                  <Select value={effectiveLanguage} onValueChange={setLanguage}>
                    <SelectTrigger {...props} className="w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {(country?.languages ?? []).map((l) => (
                        <SelectItem key={l.code} value={l.code}>
                          {l.nativeName}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                )}
              </FormField>
              <FormField label={t("fields.calendar")} error={serverErrors.defaultCalendarCode}>
                {(props) => (
                  <Select value={effectiveCalendar} onValueChange={setCalendar}>
                    <SelectTrigger {...props} className="w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {(country?.calendars ?? []).map((c) => (
                        <SelectItem key={c.code} value={c.code}>
                          {c.name}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                )}
              </FormField>
              <FormField label={t("fields.timezone")} error={serverErrors.timezone}>
                {(props) => (
                  <Input
                    {...props}
                    value={effectiveTimezone}
                    onChange={(e) => setTimezone(e.target.value)}
                    autoComplete="off"
                    spellCheck={false}
                  />
                )}
              </FormField>
            </div>

            {submit.isError && !slugTaken && Object.keys(serverErrors).length === 0 ? (
              <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
                {errorMessage(submit.error, t("saveFailed"))}
              </p>
            ) : null}

            <div className="flex flex-wrap justify-end gap-3">
              <Button type="button" variant="outline" size="lg" onClick={() => router.push("/platform/schools")}>
                {t("cancel")}
              </Button>
              <Button type="submit" size="lg" disabled={submit.isPending || !country}>
                {submit.isPending ? t("saving") : t("schools.create")}
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
