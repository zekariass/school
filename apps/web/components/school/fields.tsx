"use client";

import { CheckIcon } from "lucide-react";
import { useId } from "react";
import { FormField } from "@/components/console/form-field";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { cn } from "@/lib/utils";

/** Select values may not be empty strings, so "nothing chosen" travels as this sentinel. */
export const NONE = "__none__";

export interface Option {
  value: string;
  label: string;
}

/** A labelled select. With `allowNone`, the first option clears the choice (value ""). */
export function SelectField({
  label,
  value,
  onChange,
  options,
  placeholder,
  noneLabel,
  error,
  hint,
  className,
  disabled,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: readonly Option[];
  placeholder?: string;
  /** Adds an option that clears the value. */
  noneLabel?: string;
  error?: string | null;
  hint?: string;
  className?: string;
  disabled?: boolean;
}) {
  return (
    <FormField label={label} error={error} hint={hint} className={className}>
      {(props) => (
        <Select
          value={value === "" ? (noneLabel ? NONE : "") : value}
          onValueChange={(v) => onChange(v === NONE ? "" : v)}
          disabled={disabled}
        >
          <SelectTrigger {...props} className="w-full data-[size=default]:h-11">
            <SelectValue placeholder={placeholder} />
          </SelectTrigger>
          <SelectContent>
            {noneLabel ? <SelectItem value={NONE}>{noneLabel}</SelectItem> : null}
            {options.map((o) => (
              <SelectItem key={o.value} value={o.value}>
                {o.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      )}
    </FormField>
  );
}

/** A native checkbox with a visible label (44px target). */
export function CheckboxField({
  label,
  checked,
  onChange,
  hint,
  disabled,
}: {
  label: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
  hint?: string;
  disabled?: boolean;
}) {
  const id = useId();
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="flex min-h-11 cursor-pointer items-center gap-3 text-sm font-medium">
        <input
          id={id}
          type="checkbox"
          checked={checked}
          disabled={disabled}
          onChange={(e) => onChange(e.target.checked)}
          className="size-5 accent-primary"
        />
        {label}
      </label>
      {hint ? <p className="ps-8 text-sm text-muted-foreground">{hint}</p> : null}
    </div>
  );
}

/** A row of round swatches (colors) or tiles (emoji): one is chosen. */
export function SwatchPicker({
  label,
  value,
  onChange,
  choices,
  kind,
  describe,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  choices: readonly string[];
  kind: "color" | "emoji";
  /** Accessible name of a choice. */
  describe?: (choice: string) => string;
}) {
  const legend = useId();
  return (
    <div role="radiogroup" aria-labelledby={legend} className="flex flex-col gap-1.5">
      <span id={legend} className="text-sm leading-none font-medium">
        {label}
      </span>
      <div className="flex flex-wrap gap-2">
        {choices.map((choice) => {
          const selected = value.toLowerCase() === choice.toLowerCase();
          return (
            <button
              key={choice}
              type="button"
              role="radio"
              aria-checked={selected}
              aria-label={describe?.(choice) ?? choice}
              onClick={() => onChange(choice)}
              style={kind === "color" ? { backgroundColor: choice } : undefined}
              className={cn(
                "grid size-11 place-items-center rounded-full border-2 text-xl transition-transform outline-none hover:scale-105 focus-visible:ring-[3px] focus-visible:ring-ring/50",
                kind === "emoji" && "rounded-xl bg-muted",
                selected ? "border-foreground" : "border-transparent",
              )}
            >
              {kind === "color" ? selected ? <CheckIcon className="size-5 text-white drop-shadow" aria-hidden /> : null : choice}
            </button>
          );
        })}
      </div>
    </div>
  );
}
