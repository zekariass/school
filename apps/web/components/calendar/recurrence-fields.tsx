"use client";

import { useLocale, useTranslations } from "next-intl";
import { useId } from "react";
import { useCalendarPreference } from "@/components/calendar-preference";
import { FormField } from "@/components/console/form-field";
import { SelectField } from "@/components/school/fields";
import { Input } from "@/components/ui/input";
import { weekdayName } from "@/lib/calendar/format";
import {
  MAX_COUNT,
  MAX_INTERVAL,
  MAX_YEARS,
  WEEKDAYS_MONDAY_FIRST,
  WEEKDAY_CODES,
  summarize,
  untilLimit,
  weekdayCode,
  monthDayOf,
  type EndMode,
  type Frequency,
  type RecurrenceDraft,
  type RecurrenceProblem,
  type WeekdayCode,
} from "@/lib/calendar/recurrence";
import { cn } from "@/lib/utils";
import { useRuleText } from "./rule-text";

/**
 * The repeat builder: how often (daily, weekly on chosen weekdays, monthly on the start's day), every how many, and how it
 * ends (never, on a day, after a number of events), with the sentence it makes and the limits of the API. The first
 * event is always the event's own start, so its weekday (weekly) or day of the month (monthly) is part of the rule.
 */
export function RecurrenceFields({
  draft,
  startIso,
  lockedRule,
  onChange,
  problem,
  disabled,
}: {
  draft: RecurrenceDraft;
  /** The first day of the event ("yyyy-mm-dd"), or "" while it is not valid. */
  startIso: string;
  /** A stored rule the builder cannot show: kept as it is. */
  lockedRule: string | null;
  onChange: (next: RecurrenceDraft) => void;
  problem: RecurrenceProblem | null;
  disabled?: boolean;
}) {
  const t = useTranslations("school.calendar.recurrence");
  const tp = useTranslations("school.calendar.problems");
  const locale = useLocale();
  const { calendar: system } = useCalendarPreference();
  const ruleText = useRuleText();
  const legend = useId();

  if (lockedRule) {
    return (
      <div className="flex flex-col gap-1 rounded-xl bg-muted/60 p-3 text-sm" data-testid="recurrence-locked">
        <p className="font-semibold">{t("locked")}</p>
        <p className="font-mono text-xs break-all">{lockedRule}</p>
      </div>
    );
  }

  const set = (patch: Partial<RecurrenceDraft>) => onChange({ ...draft, ...patch });
  const repeats = draft.freq !== "NONE";
  const summary = repeats && startIso ? summarize(draft, startIso) : null;
  const startCode: WeekdayCode | null = startIso ? weekdayCode(startIso) : null;
  const unit = draft.freq === "DAILY" ? "days" : draft.freq === "WEEKLY" ? "weeks" : "months";
  const day = startIso ? monthDayOf(startIso) : 1;

  return (
    <fieldset className="flex flex-col gap-3" disabled={disabled} data-testid="recurrence-fields">
      <legend id={legend} className="mb-1 text-sm font-medium">
        {t("title")}
      </legend>
      <SelectField
        label={t("frequency")}
        value={draft.freq}
        onChange={(value) => set({ freq: value as Frequency, byDay: [] })}
        options={(["NONE", "DAILY", "WEEKLY", "MONTHLY"] as const).map((value) => ({ value, label: t(`frequencies.${value}`) }))}
      />
      {repeats ? (
        <>
          <FormField label={t(`every.${unit}`)} error={problem === "intervalRange" ? tp("intervalRange", { max: MAX_INTERVAL }) : null}>
            {(props) => (
              <Input
                {...props}
                type="number"
                inputMode="numeric"
                min={1}
                max={MAX_INTERVAL}
                value={draft.interval ?? ""}
                onChange={(e) => set({ interval: e.target.value === "" ? null : Number(e.target.value) })}
                className="w-28"
                data-testid="recurrence-interval"
              />
            )}
          </FormField>

          {draft.freq === "WEEKLY" ? (
            <div role="group" aria-label={t("weekdays")} className="flex flex-col gap-1.5">
              <span className="text-sm font-medium">{t("weekdays")}</span>
              <div className="flex flex-wrap gap-2">
                {WEEKDAYS_MONDAY_FIRST.map((weekday) => {
                  const code = WEEKDAY_CODES[weekday]!;
                  const locked = code === startCode;
                  const on = locked || draft.byDay.includes(code);
                  return (
                    <button
                      key={code}
                      type="button"
                      aria-pressed={on}
                      aria-label={weekdayName(weekday, locale, "long")}
                      disabled={locked}
                      title={locked ? t("startDayLocked") : undefined}
                      onClick={() => set({ byDay: on ? draft.byDay.filter((c) => c !== code) : [...draft.byDay, code] })}
                      data-testid={`recurrence-day-${code}`}
                      className={cn(
                        "grid h-11 min-w-11 place-items-center rounded-full border px-2 text-sm font-semibold outline-none focus-visible:ring-[3px] focus-visible:ring-ring/60",
                        on ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent",
                        locked && "opacity-90",
                      )}
                    >
                      {weekdayName(weekday, locale, "short")}
                    </button>
                  );
                })}
              </div>
              <p className="text-sm text-muted-foreground">{t("startDayHint")}</p>
            </div>
          ) : null}

          {draft.freq === "MONTHLY" ? (
            <div className="text-sm">
              <p className="font-medium">{t("monthlyOn", { day })}</p>
              {day > 28 ? <p className="text-muted-foreground">{t("monthlyShortMonths", { day })}</p> : null}
            </div>
          ) : null}

          <div role="radiogroup" aria-label={t("ends")} className="flex flex-col gap-2">
            <span className="text-sm font-medium">{t("ends")}</span>
            {(["never", "until", "count"] as const).map((mode) => (
              <div key={mode} className="flex flex-wrap items-center gap-3">
                <label className="flex min-h-11 cursor-pointer items-center gap-2 text-sm font-medium">
                  <input type="radio" name="recurrence-end" className="size-5 accent-primary" checked={draft.end === mode} onChange={() => set({ end: mode as EndMode })} data-testid={`recurrence-end-${mode}`} />
                  {t(`end.${mode}`)}
                </label>
                {mode === "until" && draft.end === "until" ? (
                  <FormField label={t("untilDate")} error={problem === "untilRequired" || problem === "untilBeforeStart" || problem === "untilTooFar" ? tp(problem, { years: MAX_YEARS }) : null}>
                    {(props) => (
                      <Input
                        {...props}
                        type="date"
                        value={draft.until}
                        min={startIso || undefined}
                        max={startIso ? untilLimit(startIso) : undefined}
                        onChange={(e) => set({ until: e.target.value })}
                        data-testid="recurrence-until"
                      />
                    )}
                  </FormField>
                ) : null}
                {mode === "count" && draft.end === "count" ? (
                  <FormField label={t("countLabel")} error={problem === "countRange" ? tp("countRange", { max: MAX_COUNT }) : null}>
                    {(props) => (
                      <Input
                        {...props}
                        type="number"
                        inputMode="numeric"
                        min={1}
                        max={MAX_COUNT}
                        value={draft.count ?? ""}
                        onChange={(e) => set({ count: e.target.value === "" ? null : Number(e.target.value) })}
                        className="w-28"
                        data-testid="recurrence-count"
                      />
                    )}
                  </FormField>
                ) : null}
              </div>
            ))}
          </div>

          <p className="rounded-xl bg-muted/60 p-3 text-sm" data-testid="recurrence-preview">
            <span className="font-semibold">{t("preview")}: </span>
            {summary ? ruleText(summary, system) : t("previewIncomplete")}
          </p>
          <p className="text-xs text-muted-foreground">{t("limits", { interval: MAX_INTERVAL, count: MAX_COUNT, years: MAX_YEARS })}</p>
        </>
      ) : null}
    </fieldset>
  );
}
