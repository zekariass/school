"use client";

import { useMutation } from "@tanstack/react-query";
import { unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { toParentSheet, type ParentCodeSheet } from "@/lib/school/parent-codes";
import { useSchool } from "./school-context";

export interface IssueParentVariables {
  classId: string;
  className: string;
  /** Absent or empty: every active student of the class. */
  studentIds?: string[];
}

/**
 * Issues printed parent codes (the student's previous live code is revoked) and resolves to the
 * sheet with the plaintext codes. Deliberately NOT idempotent: no `Idempotency-Key`, and the
 * result lives in memory only (`gcTime: 0`). Show it once or hand it to
 * `useIssuedCodes().putParent`, then `reset()` the mutation.
 */
export function useIssueParentCodes() {
  const { school } = useSchool();
  return useMutation({
    gcTime: 0,
    mutationFn: async (vars: IssueParentVariables): Promise<ParentCodeSheet> => {
      const issued = unwrap(
        await getBrowserApi().POST("/api/v1/schools/{schoolId}/classes/{classId}/parent-codes", {
          params: { path: { schoolId: school.id, classId: vars.classId } },
          body: vars.studentIds && vars.studentIds.length > 0 ? { studentIds: vars.studentIds } : {},
          cache: "no-store",
        }),
      );
      return toParentSheet(vars.classId, vars.className, issued, Date.now());
    },
  });
}
