"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import type { CodeKind, CodeSheet } from "@/lib/school/codes";
import { sk } from "@/lib/school/queries";
import { useSchool } from "./school-context";

export interface IssueVariables {
  classId: string;
  className: string;
  /** Empty or absent: every active student of the class. */
  studentIds?: string[];
  kind: CodeKind;
  ttlDays?: number;
}

/**
 * Issues login codes (revoking the earlier ones) and resolves to the sheet with the
 * plaintext codes. Deliberately NOT idempotent: no `Idempotency-Key`, because a replay
 * would keep the plaintext in the server's idempotency store. The result is kept
 * in memory only (`gcTime: 0`: the mutation cache drops it as soon as nothing observes it).
 */
export function useIssueCodes() {
  const { school } = useSchool();
  const queryClient = useQueryClient();
  return useMutation({
    gcTime: 0,
    mutationFn: async (vars: IssueVariables): Promise<CodeSheet> => {
      const codes = unwrap(
        await getBrowserApi().POST("/api/v1/schools/{schoolId}/classes/{classId}/login-codes", {
          params: { path: { schoolId: school.id, classId: vars.classId } },
          body: {
            type: vars.kind,
            ...(vars.studentIds && vars.studentIds.length > 0 ? { studentIds: vars.studentIds } : {}),
            ...(vars.ttlDays ? { ttlDays: vars.ttlDays } : {}),
          },
          cache: "no-store",
        }),
      );
      return {
        classId: vars.classId,
        className: vars.className,
        kind: vars.kind,
        issuedAt: Date.now(),
        codes: codes.map((c) => ({
          studentId: c.studentId,
          displayName: c.displayName,
          expiresAt: c.expiresAt,
          qrToken: c.qrToken ?? null,
          textCode: c.textCode ?? null,
        })),
      };
    },
    onSuccess: async (sheet) => {
      // Only metadata is cached (never the codes): refresh the "live codes" lists.
      await Promise.all(sheet.codes.map((c) => queryClient.invalidateQueries({ queryKey: sk.studentCodes(school.id, c.studentId) })));
    },
  });
}
