import type { components } from '@school/api-client';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { api } from '@/lib/api';
import { request, unwrap } from '@/lib/api-error';

import { staffDayKey, staffDaysKey, staffRecordKey, staffSchoolKey } from './keys';
import type { StaffCorrectionRequest, StaffDayRequest } from './model';

type StaffDayPage = components['schemas']['StaffDayRoll'];

/** Staff data is kept in memory for a short while only (see `keys.ts`). */
const STAFF_GC_MS = 5 * 60 * 1000;
const PAGE = 100;

/**
 * The school's time zone: "today" on the roll is the school's day, never the phone's. There is no endpoint that
 * answers it for a holder of the staff permissions alone, so it is read from the daily dashboard (the day and zone the
 * school is in, `school.attendance.read`) and, when that is refused, from the person's own membership at the school.
 */
export function useStaffSchool(schoolId: string | undefined, membershipZone: string | undefined) {
  return useQuery({
    queryKey: staffSchoolKey(schoolId ?? ''),
    enabled: !!schoolId,
    gcTime: STAFF_GC_MS,
    staleTime: 10 * 60 * 1000,
    queryFn: () =>
      request(async () => {
        try {
          const dashboard = unwrap(
            await api.GET('/api/v1/schools/{schoolId}/attendance/dashboard', { params: { path: { schoolId: schoolId! }, query: { limit: 1 } } }),
          );
          return { timeZone: dashboard.timeZone };
        } catch (error) {
          if (membershipZone) return { timeZone: membershipZone };
          throw error;
        }
      }),
  });
}

/** The staff roll of a school-local day, keyset paged by name. */
export function useStaffDay(schoolId: string | undefined, date: string | null) {
  return useInfiniteQuery({
    queryKey: staffDayKey(schoolId ?? '', date ?? ''),
    enabled: !!schoolId && !!date,
    gcTime: STAFF_GC_MS,
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last: StaffDayPage) => last.staff.nextCursor ?? undefined,
    // A refusal (switched off, not allowed) will not change by asking again.
    retry: (count, error) => !(error instanceof Error && 'status' in error && typeof error.status === 'number' && error.status >= 400 && error.status < 500) && count < 2,
    queryFn: ({ pageParam }): Promise<StaffDayPage> =>
      request(async () =>
        unwrap(
          await api.GET('/api/v1/schools/{schoolId}/staff-attendance/days/{date}', {
            params: { path: { schoolId: schoolId!, date: date! }, query: { limit: PAGE, ...(pageParam ? { cursor: pageParam } : {}) } },
          }),
        ),
      ),
  });
}

/** Marks staff for a day (creates only) and/or everybody else present. Existing records stay (outcome EXISTS). */
export function useMarkStaffDay(schoolId: string | undefined, date: string | null) {
  const queryClient = useQueryClient();
  return useMutation({
    networkMode: 'always',
    retry: false,
    mutationFn: (body: StaffDayRequest) =>
      request(async () =>
        unwrap(
          await api.PUT('/api/v1/schools/{schoolId}/staff-attendance/days/{date}', {
            params: { path: { schoolId: schoolId!, date: date! } },
            body,
          }),
        ),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: staffDaysKey(schoolId ?? '') }),
  });
}

/** One record with its corrections (the append-only history, newest last as the API returns it). */
export function useStaffRecord(schoolId: string | undefined, recordId: string | null) {
  return useQuery({
    queryKey: staffRecordKey(schoolId ?? '', recordId ?? ''),
    enabled: !!schoolId && !!recordId,
    gcTime: STAFF_GC_MS,
    queryFn: () =>
      request(async () =>
        unwrap(
          await api.GET('/api/v1/schools/{schoolId}/staff-attendance/records/{recordId}', {
            params: { path: { schoolId: schoolId!, recordId: recordId! } },
          }),
        ),
      ),
  });
}

/**
 * A correction. The key is made once by the sheet, so a retry after a lost answer is the same correction; a clash with
 * another manager's edit is a 409 (`staff_attendance.version_conflict`) with the API's detail.
 */
export function useCorrectStaff(schoolId: string | undefined) {
  const queryClient = useQueryClient();
  return useMutation({
    networkMode: 'always',
    retry: false,
    mutationFn: ({ recordId, body, idempotencyKey }: { recordId: string; body: StaffCorrectionRequest; idempotencyKey: string }) =>
      request(async () =>
        unwrap(
          await api.POST('/api/v1/schools/{schoolId}/staff-attendance/records/{recordId}/corrections', {
            params: { path: { schoolId: schoolId!, recordId } },
            body,
            headers: { 'Idempotency-Key': idempotencyKey },
          }),
        ),
      ),
    onSuccess: (_detail, { recordId }) =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: staffDaysKey(schoolId ?? '') }),
        queryClient.invalidateQueries({ queryKey: staffRecordKey(schoolId ?? '', recordId) }),
      ]),
  });
}
