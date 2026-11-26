import type { components } from '@school/api-client';
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';

import { api } from '@/lib/api';
import { request, unwrap } from '@/lib/api-error';
import { classAbsencesKey, classRollsKey, studentAbsencesKey, studentHistoryKey } from '@/lib/attendance/keys';

import { toRequestBody, type AbsenceForm } from './form';

type S = components['schemas'];

export type AbsenceReport = S['AbsenceReportView'];
export type AttendanceHistoryItem = S['AttendanceHistoryItem'];

const PAGE = 30;
type ReportPage = S['CursorPageAbsenceReportView'];
type HistoryPage = S['CursorPageAttendanceHistoryItem'];

/**
 * Waiting and acknowledged absence reports of the students in a class, oldest first (the API's default window: a
 * week back to 60 days ahead). Reason and note come only for teachers who may read them. Online only: parents'
 * words about a child's health are not kept on the phone.
 */
export function useClassAbsenceReports(schoolId: string | undefined, classId: string | undefined) {
  return useInfiniteQuery({
    queryKey: classAbsencesKey(classId ?? ''),
    enabled: !!schoolId && !!classId,
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last: ReportPage) => last.nextCursor ?? undefined,
    queryFn: ({ pageParam }): Promise<ReportPage> =>
      request(async () =>
        unwrap(
          await api.GET('/api/v1/schools/{schoolId}/classes/{classId}/absence-reports', {
            params: { path: { schoolId: schoolId!, classId: classId! }, query: { limit: PAGE, ...(pageParam ? { cursor: pageParam } : {}) } },
          }),
        ),
      ),
  });
}

/** The reports the signed-in guardian filed for a child, newest first. */
export function useMyAbsenceReports(schoolId: string | undefined, studentId: string | undefined) {
  return useInfiniteQuery({
    queryKey: studentAbsencesKey(schoolId ?? '', studentId ?? ''),
    enabled: !!schoolId && !!studentId,
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last: ReportPage) => last.nextCursor ?? undefined,
    queryFn: ({ pageParam }): Promise<ReportPage> =>
      request(async () =>
        unwrap(
          await api.GET('/api/v1/schools/{schoolId}/students/{studentId}/absence-reports', {
            params: { path: { schoolId: schoolId!, studentId: studentId! }, query: { limit: PAGE, ...(pageParam ? { cursor: pageParam } : {}) } },
          }),
        ),
      ),
  });
}

/** A child's attendance history, newest first (a guardian's view: no teacher notes). */
export function useAttendanceHistory(schoolId: string | undefined, studentId: string | undefined, enabled = true) {
  return useInfiniteQuery({
    queryKey: studentHistoryKey(schoolId ?? '', studentId ?? ''),
    enabled: enabled && !!schoolId && !!studentId,
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last: HistoryPage) => last.nextCursor ?? undefined,
    queryFn: ({ pageParam }): Promise<HistoryPage> =>
      request(async () =>
        unwrap(
          await api.GET('/api/v1/schools/{schoolId}/students/{studentId}/attendance', {
            params: { path: { schoolId: schoolId!, studentId: studentId! }, query: { limit: PAGE, ...(pageParam ? { cursor: pageParam } : {}) } },
          }),
        ),
      ),
  });
}

/** Files an absence report. The key is made once by the form, so a retry after a dropped answer files it once. */
export function useReportAbsence(schoolId: string | undefined, studentId: string | undefined) {
  const queryClient = useQueryClient();
  return useMutation({
    networkMode: 'always',
    retry: false,
    mutationFn: ({ form, idempotencyKey }: { form: AbsenceForm; idempotencyKey: string }) =>
      request(async () =>
        unwrap(
          await api.POST('/api/v1/schools/{schoolId}/students/{studentId}/absence-reports', {
            params: { path: { schoolId: schoolId!, studentId: studentId! } },
            body: toRequestBody(form),
            headers: { 'Idempotency-Key': idempotencyKey },
          }),
        ),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: studentAbsencesKey(schoolId ?? '', studentId ?? '') }),
  });
}

/** The guardian withdraws a report. */
export function useCancelAbsence(schoolId: string | undefined, studentId: string | undefined) {
  const queryClient = useQueryClient();
  return useMutation({
    networkMode: 'always',
    retry: false,
    mutationFn: (reportId: string) =>
      request(async () =>
        unwrap(await api.POST('/api/v1/schools/{schoolId}/absence-reports/{reportId}/cancel', { params: { path: { schoolId: schoolId!, reportId } } })),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: studentAbsencesKey(schoolId ?? '', studentId ?? '') }),
  });
}

export type Decision = 'acknowledge' | 'decline';

/** A teacher acknowledges or declines a waiting report (once). An acknowledgement shows on the roll as a hint. */
export function useDecideAbsence(schoolId: string | undefined, classId: string | undefined) {
  const queryClient = useQueryClient();
  return useMutation({
    networkMode: 'always',
    retry: false,
    mutationFn: ({ reportId, decision, note }: { reportId: string; decision: Decision; note?: string }) =>
      request(async () => {
        const params = { params: { path: { schoolId: schoolId!, reportId } }, body: note ? { note } : {} } as const;
        return unwrap(
          decision === 'acknowledge'
            ? await api.POST('/api/v1/schools/{schoolId}/absence-reports/{reportId}/acknowledge', params)
            : await api.POST('/api/v1/schools/{schoolId}/absence-reports/{reportId}/decline', params),
        );
      }),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: classAbsencesKey(classId ?? '') }),
        queryClient.invalidateQueries({ queryKey: classRollsKey(classId ?? '') }),
      ]),
  });
}

