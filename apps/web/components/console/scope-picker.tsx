"use client";

import { useQuery } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { useState } from "react";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import {
  fetchCountries,
  fetchOrganizations,
  fetchSchools,
  queryKeys,
  type ScopeRef,
} from "@/lib/console/queries";

export type ScopeType = ScopeRef["type"];

/**
 * Chooses a configuration scope (platform, country, organization or school) and
 * reports it as a `ScopeRef`, or null while a target is still missing.
 */
export function ScopePicker({
  allowed,
  onChange,
}: {
  allowed: readonly ScopeType[];
  onChange: (scope: ScopeRef | null) => void;
}) {
  const t = useTranslations("console");
  const locale = useLocale();
  const [type, setType] = useState<ScopeType>(allowed[0] ?? "PLATFORM");
  const [target, setTarget] = useState<string>("");

  const countries = useQuery({
    queryKey: queryKeys.countries,
    queryFn: fetchCountries,
    staleTime: 600_000,
    enabled: type === "COUNTRY",
  });
  const organizations = useQuery({
    queryKey: queryKeys.organizations,
    queryFn: () => fetchOrganizations(),
    enabled: type === "ORGANIZATION",
  });
  const schools = useQuery({
    queryKey: queryKeys.schools({ picker: true }),
    queryFn: () => fetchSchools({}, undefined, 100),
    enabled: type === "SCHOOL",
  });

  function emit(nextType: ScopeType, nextTarget: string) {
    if (nextType === "PLATFORM") return onChange({ type: "PLATFORM" });
    if (!nextTarget) return onChange(null);
    if (nextType === "COUNTRY") return onChange({ type: "COUNTRY", countryCode: nextTarget });
    if (nextType === "ORGANIZATION") return onChange({ type: "ORGANIZATION", organizationId: nextTarget });
    return onChange({ type: "SCHOOL", schoolId: nextTarget });
  }

  const options: Array<{ value: string; label: string }> =
    type === "COUNTRY"
      ? (countries.data ?? []).map((c) => ({ value: c.code, label: c.nameI18n[locale] ?? c.nameI18n.en ?? c.code }))
      : type === "ORGANIZATION"
        ? (organizations.data?.items ?? []).map((o) => ({ value: o.id, label: o.name }))
        : type === "SCHOOL"
          ? (schools.data?.items ?? []).map((s) => ({ value: s.id, label: s.name }))
          : [];
  const loading = countries.isPending && type === "COUNTRY"
    ? true
    : organizations.isPending && type === "ORGANIZATION"
      ? true
      : schools.isPending && type === "SCHOOL";

  return (
    <div className="flex flex-wrap gap-4">
      <div className="flex min-w-48 flex-col gap-1.5">
        <Label htmlFor="scope-type">{t("scope.level")}</Label>
        <Select
          value={type}
          onValueChange={(value) => {
            setType(value as ScopeType);
            setTarget("");
            emit(value as ScopeType, "");
          }}
        >
          <SelectTrigger id="scope-type" className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {allowed.map((s) => (
              <SelectItem key={s} value={s}>
                {t(`scope.${s}`)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
      {type !== "PLATFORM" ? (
        <div className="flex min-w-64 flex-col gap-1.5">
          <Label htmlFor="scope-target">{t(`scope.target.${type}`)}</Label>
          <Select
            value={target}
            onValueChange={(value) => {
              setTarget(value);
              emit(type, value);
            }}
          >
            <SelectTrigger id="scope-target" className="w-full">
              <SelectValue placeholder={loading ? t("loading") : t("scope.choose")} />
            </SelectTrigger>
            <SelectContent>
              {options.map((o) => (
                <SelectItem key={o.value} value={o.value}>
                  {o.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      ) : null}
    </div>
  );
}
