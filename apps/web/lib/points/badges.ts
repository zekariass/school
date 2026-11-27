/** Badge definitions: the rule's parameters as the API wants them, and the form's small helpers. */

export type BadgeCriteriaType = "MANUAL" | "POINTS_TOTAL" | "CATEGORY_TOTAL" | "ATTENDANCE_STREAK";

const TYPES: readonly BadgeCriteriaType[] = ["MANUAL", "POINTS_TOTAL", "CATEGORY_TOTAL", "ATTENDANCE_STREAK"];

/** The badge type when the API sent one we know; null for anything newer than this client. */
export const asBadgeType = (value: string): BadgeCriteriaType | null => TYPES.find((t) => t === value) ?? null;

/** The API's code format: lower-case letters, digits and underscores, starting with a letter, 2-41 characters. */
export const BADGE_CODE = /^[a-z][a-z0-9_]{1,40}$/;

/** "Super Helper!" becomes "super_helper": a starting point for the code that the user may change. */
/** A code suggested from the first language whose name makes a valid slug (a name in another script makes none). */
export function suggestCode(languages: readonly string[], names: Readonly<Record<string, string>>): string {
  for (const code of languages) {
    const slug = slugFromName(names[code] ?? "");
    if (slug) return slug;
  }
  return "";
}

export function slugFromName(name: string): string {
  const slug = name
    .normalize("NFKD")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "")
    .slice(0, 41);
  return /^[a-z]/.test(slug) ? slug : "";
}

function wholeIn(text: string, min: number, max: number): number | null {
  if (text.trim() === "") return null;
  const value = Number(text);
  return Number.isInteger(value) && value >= min && value <= max ? value : null;
}

/**
 * The `criteria` map for a badge type from the form's fields, or null when the numbers are not valid.
 * MANUAL has no parameters (an empty object is sent as nothing); CATEGORY_TOTAL needs the category on creation.
 */
export function badgeCriteria(
  type: BadgeCriteriaType,
  fields: { threshold: string; days: string; categoryId: string },
): Record<string, unknown> | null {
  switch (type) {
    case "MANUAL":
      return null;
    case "POINTS_TOTAL": {
      const threshold = wholeIn(fields.threshold, 1, 100_000);
      return threshold === null ? null : { threshold };
    }
    case "CATEGORY_TOTAL": {
      const threshold = wholeIn(fields.threshold, 1, 100_000);
      if (threshold === null) return null;
      return fields.categoryId ? { threshold, categoryId: fields.categoryId } : { threshold };
    }
    case "ATTENDANCE_STREAK": {
      const days = wholeIn(fields.days, 1, 365);
      return days === null ? null : { days };
    }
  }
}
