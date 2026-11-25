/**
 * Printed parent codes (M5b). Like the student login codes, the plaintext exists only in the
 * response of the issue call and in the memory of the page that asked for it (see
 * `IssuedCodesProvider`): never in a URL, storage or a query cache.
 */
import { formatParentCode } from "@school/validation/guardians";

export interface ParentCodeEntry {
  studentId: string;
  /** The API only gives the child's first name for a parent code. */
  childFirstName: string;
  /** Formatted as printed (`XXXXX-XXXXX`). */
  code: string;
  expiresAt: string;
  maxUses: number;
}

export interface ParentCodeSheet {
  classId: string;
  className: string;
  issuedAt: number;
  codes: ParentCodeEntry[];
}

/** The sheet if it belongs to this class and has cards to print. */
export function parentSheetForClass(sheet: ParentCodeSheet | null | undefined, classId: string): ParentCodeSheet | null {
  if (!sheet || sheet.classId !== classId || sheet.codes.length === 0) return null;
  return sheet;
}

export function toParentSheet(
  classId: string,
  className: string,
  issued: readonly { studentId: string; childFirstName: string; code: string; expiresAt: string; maxUses: number }[],
  issuedAt: number,
): ParentCodeSheet {
  return {
    classId,
    className,
    issuedAt,
    codes: issued.map((c) => ({
      studentId: c.studentId,
      childFirstName: c.childFirstName,
      code: formatParentCode(c.code),
      expiresAt: c.expiresAt,
      maxUses: c.maxUses,
    })),
  };
}

/** Cards sorted by first name so the sheet is easy to hand out. */
export function sortedForPrint(codes: readonly ParentCodeEntry[]): ParentCodeEntry[] {
  return [...codes].sort((a, b) => a.childFirstName.localeCompare(b.childFirstName));
}
