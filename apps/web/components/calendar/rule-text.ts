"use client";

import { useLocale, useTranslations } from "next-intl";
import { dayLabel, weekdayName } from "@/lib/calendar/format";
import { WEEKDAY_CODES, type RuleSummary } from "@/lib/calendar/recurrence";
import type { CalendarSystem } from "@/lib/calendar/dates";

/**
 * A repeat rule in plain words, in the viewer's language and calendar: "Every 2 weeks on Monday and Wednesday, 6 times".
 * The structure comes from `summarize` (pure, tested); only the words are here.
 */
export function useRuleText() {
  const t = useTranslations("family.calendar.rule");
  const locale = useLocale();
  return (summary: RuleSummary, system: CalendarSystem): string => {
    let base: string;
    // Plural-free messages (the `family` catalog is shared with the phone, where ICU plurals fail): one key per case.
    const many = summary.interval !== 1;
    if (summary.freq === "DAILY") base = many ? t("dailyMany", { interval: summary.interval }) : t("dailyOne");
    else if (summary.freq === "WEEKLY") {
      const names = summary.days.map((code) => weekdayName(WEEKDAY_CODES.indexOf(code) as 0, locale, "long"));
      const days = new Intl.ListFormat(locale, { style: "long", type: "conjunction" }).format(names);
      base = many ? t("weeklyMany", { interval: summary.interval, days }) : t("weeklyOne", { days });
    } else {
      const day = summary.monthDay ?? 1;
      base = many ? t("monthlyMany", { interval: summary.interval, day }) : t("monthlyOne", { day });
    }
    const end = summary.end;
    if (end.kind === "until") return `${base}, ${t("until", { date: dayLabel(end.date, system, locale) })}`;
    if (end.kind === "count") return `${base}, ${end.count === 1 ? t("countOne") : t("countMany", { count: end.count })}`;
    return base;
  };
}
