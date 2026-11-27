"use client";

import { cn } from "@/lib/utils";

/** A row of mutually exclusive filter chips (`aria-pressed`), the same look as the other lists. */
export function ChipGroup<T extends string>({
  label,
  value,
  options,
  onChange,
  testId,
}: {
  label: string;
  value: T;
  options: readonly { value: T; label: string }[];
  onChange: (value: T) => void;
  testId?: string;
}) {
  return (
    <div role="group" aria-label={label} className="flex flex-wrap items-center gap-2" data-testid={testId}>
      <span className="text-sm font-medium text-muted-foreground">{label}</span>
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          aria-pressed={value === option.value}
          onClick={() => onChange(option.value)}
          data-testid={testId ? `${testId}-${option.value || "any"}` : undefined}
          className={cn(
            "min-h-9 rounded-full border px-3 text-sm font-semibold",
            value === option.value ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent",
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

/** A small coloured label. HIGH and CRITICAL (and their incident twins) use the destructive colour. */
export function Pill({ tone = "muted", children, testId }: { tone?: "muted" | "urgent" | "primary"; children: React.ReactNode; testId?: string }) {
  return (
    <span
      data-testid={testId}
      className={cn(
        "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-semibold",
        tone === "urgent" && "bg-destructive/10 text-destructive",
        tone === "primary" && "bg-primary/10 text-primary",
        tone === "muted" && "bg-secondary text-secondary-foreground",
      )}
    >
      {children}
    </span>
  );
}
