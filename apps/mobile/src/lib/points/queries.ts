import type { components } from '@school/api-client';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { api } from '@/lib/api';
import { request, unwrap } from '@/lib/api-error';

import {
  categoriesKey,
  classPointsKey,
  homeCategoriesKey,
  POINTS_ROOT,
  studentPointsKey,
  templatesKey,
  timelineKey,
} from './keys';

type S = components['schemas'];

export type PointCategory = S['PointCategoryView'];
export type ClassPoints = S['ClassPoints'];
export type StudentPoints = S['StudentPoints'];
export type TimelineItem = S['TimelineItem'];
export type AwardResult = S['AwardResult'];
export type StudentBadge = S['StudentBadgeView'];
export type ActivityTemplate = S['TemplateView'];
export type Section = S['Section'];

/** The skills a teacher can award in a class (the school's and the class's own). Cached for offline awards. */
export function useCategories(schoolId: string | undefined, classId: string | undefined) {
  return useQuery({
    queryKey: categoriesKey(schoolId ?? '', classId ?? ''),
    enabled: !!schoolId && !!classId,
    queryFn: () =>
      request(async () =>
        unwrap(
          await api.GET('/api/v1/schools/{schoolId}/point-categories', {
            params: { path: { schoolId: schoolId! }, query: { classId: classId! } },
          }),
        ),
      ),
  });
}

export interface ClassPointsSnapshot {
  points: ClassPoints;
  /** When the request started: the optimistic totals use it to know what the overview can contain. */
  requestedAt: number;
}

/** The class overview (teachers only). Never persisted: it lists children with their points. */
export function useClassPoints(schoolId: string | undefined, classId: string | undefined) {
  return useQuery({
    queryKey: classPointsKey(classId ?? ''),
    enabled: !!schoolId && !!classId,
    staleTime: 15_000,
    queryFn: (): Promise<ClassPointsSnapshot> => {
      const requestedAt = Date.now();
      return request(async () => ({
        requestedAt,
        points: unwrap(
          await api.GET('/api/v1/schools/{schoolId}/classes/{classId}/points', {
            params: { path: { schoolId: schoolId!, classId: classId! } },
          }),
        ),
      }));
    },
  });
}

/** A student's points: the school section and, for the student and their guardians, the home section. */
export function useStudentPoints(schoolId: string | undefined, studentId: string | undefined) {
  return useQuery({
    queryKey: studentPointsKey(schoolId ?? '', studentId ?? ''),
    enabled: !!schoolId && !!studentId,
    queryFn: () =>
      request(async () =>
        unwrap(
          await api.GET('/api/v1/schools/{schoolId}/students/{studentId}/points', {
            params: { path: { schoolId: schoolId!, studentId: studentId! } },
          }),
        ),
      ),
  });
}

const TIMELINE_PAGE = 20;

type TimelinePage = S['CursorPageTimelineItem'];

/** The points timeline of one section, newest first, loaded page by page. */
export function useTimeline(
  schoolId: string | undefined,
  studentId: string | undefined,
  section: 'SCHOOL' | 'HOME',
  enabled = true,
) {
  return useInfiniteQuery({
    queryKey: timelineKey(schoolId ?? '', studentId ?? '', section),
    enabled: enabled && !!schoolId && !!studentId,
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last: TimelinePage) => last.nextCursor ?? undefined,
    queryFn: ({ pageParam }): Promise<TimelinePage> =>
      request(async () =>
        unwrap(
          await api.GET('/api/v1/schools/{schoolId}/students/{studentId}/points/transactions', {
            params: {
              path: { schoolId: schoolId!, studentId: studentId! },
              query: { section, limit: TIMELINE_PAGE, ...(pageParam ? { cursor: pageParam } : {}) },
            },
          }),
        ),
      ),
  });
}

/** The HOME skills a guardian may award (404 for a stranger, 403 without `canAwardPoints`). */
export function useHomeCategories(schoolId: string | undefined, studentId: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: homeCategoriesKey(schoolId ?? '', studentId ?? ''),
    enabled: enabled && !!schoolId && !!studentId,
    retry: false,
    queryFn: () =>
      request(async () =>
        unwrap(
          await api.GET('/api/v1/schools/{schoolId}/students/{studentId}/home-point-categories', {
            params: { path: { schoolId: schoolId!, studentId: studentId! } },
          }),
        ),
      ),
  });
}

export interface HomeAwardInput {
  schoolId: string;
  studentId: string;
  categoryId: string;
  points: number | null;
  note: string | null;
  /** Made once per user action by the sheet; a retry after a dropped response reuses it. */
  clientOpId: string;
}

/** A guardian's home award. Online only; the op id makes a repeat harmless. */
export function useAwardHomePoints() {
  const queryClient = useQueryClient();
  return useMutation({
    networkMode: 'always',
    retry: false,
    mutationFn: (input: HomeAwardInput) =>
      request(async () =>
        unwrap(
          await api.POST('/api/v1/schools/{schoolId}/students/{studentId}/home-points', {
            params: { path: { schoolId: input.schoolId, studentId: input.studentId } },
            body: {
              categoryId: input.categoryId,
              clientOpId: input.clientOpId,
              ...(input.points !== null ? { points: input.points } : {}),
              ...(input.note ? { note: input.note } : {}),
            },
          }),
        ),
      ),
    onSuccess: (_result, input) =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: studentPointsKey(input.schoolId, input.studentId) }),
        queryClient.invalidateQueries({ queryKey: [POINTS_ROOT, 'timeline', input.schoolId, input.studentId] }),
      ]),
  });
}

export type ReverseTarget = { kind: 'batch'; batchId: string } | { kind: 'transaction'; transactionId: string };

/** Undoes an award (a batch for several students or the whole class, a transaction for one row). */
export function useReverseAward(schoolId: string | undefined) {
  return useMutation({
    networkMode: 'always',
    retry: false,
    mutationFn: ({ target, clientOpId }: { target: ReverseTarget; clientOpId: string }) =>
      request(async () =>
        target.kind === 'batch'
          ? unwrap(
              await api.POST('/api/v1/schools/{schoolId}/points/batches/{batchId}/reverse', {
                params: { path: { schoolId: schoolId!, batchId: target.batchId } },
                body: { clientOpId },
              }),
            )
          : unwrap(
              await api.POST('/api/v1/schools/{schoolId}/points/transactions/{transactionId}/reverse', {
                params: { path: { schoolId: schoolId!, transactionId: target.transactionId } },
                body: { clientOpId },
              }),
            ),
      ),
  });
}

/** Routines and checklists the teacher may use: their own, those shared with their classes and the school's. (A classId filter would leave out the school-wide ones.) */
export function useRoutineTemplates(schoolId: string | undefined, classId: string | undefined) {
  return useQuery({
    queryKey: templatesKey(schoolId ?? '', classId ?? ''),
    enabled: !!schoolId && !!classId,
    queryFn: () =>
      request(async () => {
        const [routines, checklists] = await Promise.all([
          api.GET('/api/v1/schools/{schoolId}/activity-templates', {
            params: { path: { schoolId: schoolId! }, query: { templateType: 'ROUTINE', limit: 50 } },
          }),
          api.GET('/api/v1/schools/{schoolId}/activity-templates', {
            params: { path: { schoolId: schoolId! }, query: { templateType: 'CHECKLIST', limit: 50 } },
          }),
        ]);
        return [...unwrap(routines).items, ...unwrap(checklists).items];
      }),
  });
}

/** Saves the groups of the group maker as one set (all or nothing). Online only. */
export function useSaveGroupSet(schoolId: string | undefined, classId: string | undefined) {
  return useMutation({
    networkMode: 'always',
    retry: false,
    mutationFn: (groups: { name: string; studentIds: string[] }[]) =>
      request(async () =>
        unwrap(
          await api.POST('/api/v1/schools/{schoolId}/classes/{classId}/group-sets', {
            params: { path: { schoolId: schoolId!, classId: classId! } },
            body: { groups },
          }),
        ),
      ),
  });
}
