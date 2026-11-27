"use client";

import { useId } from "react";
import { useTranslations } from "next-intl";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

/** Which country a national phone number is read in: the name and the calling code, as the API sent them. */
export function PhoneCountryPicker({
  countries,
  value,
  onChange,
  disabled,
}: {
  countries: readonly { code: string; name: string; phoneCountryCode: string }[];
  value: string | undefined;
  onChange: (code: string) => void;
  disabled?: boolean;
}) {
  const t = useTranslations("auth");
  const id = useId();
  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={id}>{t("phoneCountry")}</Label>
      <Select value={value ?? ""} onValueChange={onChange} disabled={disabled}>
        <SelectTrigger id={id} className="w-full data-[size=default]:h-11">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {countries.map((c) => (
            <SelectItem key={c.code} value={c.code}>
              {c.name} ({c.phoneCountryCode})
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}
