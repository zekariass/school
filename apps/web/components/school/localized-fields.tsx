"use client";

import { useLocale } from "next-intl";
import { languageLabel } from "@school/i18n";
import { FormField } from "@/components/console/form-field";
import { Input } from "@/components/ui/input";

/**
 * One input per language for a text the API keeps as a language-to-text map. `label` builds the field label from the
 * language's name (an i18n message such as "Name ({language})"); the languages come from the school's country (D42).
 */
export function LocalizedInputs({
  languages,
  values,
  onChange,
  label,
  maxLength,
  error,
  hint,
}: {
  languages: readonly string[];
  values: Readonly<Record<string, string>>;
  onChange: (next: Record<string, string>) => void;
  label: (language: string) => string;
  maxLength: number;
  error?: string | null;
  hint?: string;
}) {
  const locale = useLocale();
  return (
    <>
      {languages.map((code, index) => (
        <FormField
          key={code}
          label={label(languageLabel(code, locale))}
          // the first input carries the validation message and the hint
          error={index === 0 ? error : null}
          hint={index === 0 ? hint : undefined}
        >
          {(props) => (
            <Input
              {...props}
              value={values[code] ?? ""}
              maxLength={maxLength}
              lang={code}
              dir="auto"
              onChange={(e) => onChange({ ...values, [code]: e.target.value })}
            />
          )}
        </FormField>
      ))}
    </>
  );
}
