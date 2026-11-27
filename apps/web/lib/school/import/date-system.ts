import { offeredCalendars } from "@school/validation/country";

export type ImportDateSystem = "GREGORIAN" | "ETHIOPIAN";

/**
 * The date systems an import may read dates in: the Gregorian calendar, and the Ethiopian one only where the
 * school's country has it (`country.calendarCodes`, D42). The API refuses the others with
 * 422 `import.date_system_not_available`.
 */
export function importDateSystems(calendarCodes: readonly string[]): ImportDateSystem[] {
  return offeredCalendars(calendarCodes).map((c) => (c === "ethiopian" ? "ETHIOPIAN" : "GREGORIAN"));
}

/** The saved choice when the school's country still offers it, else Gregorian. */
export function initialDateSystem(saved: ImportDateSystem | null | undefined, offered: readonly ImportDateSystem[]): ImportDateSystem {
  return saved && offered.includes(saved) ? saved : "GREGORIAN";
}
