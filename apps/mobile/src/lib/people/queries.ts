import type { components } from '@school/api-client';
import { useInfiniteQuery, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';

import { api } from '@/lib/api';
import { request, unwrap } from '@/lib/api-error';
import { rosterFromCache, rosterToCache } from '@/lib/offline/cache';
import { offlineCache } from '@/lib/offline/runtime';
import { useCachedQuery } from '@/lib/offline/use-cached-query';

import {
  classKey,
  classTeachersKey,
  myClassesKey,
  rosterKey,
  studentKey,
  studentSearchKey,
  STUDENT_ROOT,
  STUDENT_SEARCH_ROOT,
} from './keys';
import { toRoster } from './roster';

type MyClass = components['schemas']['MyClass'];

/**
 * The caller's active classes as student or teacher (`GET /me/classes`). A teacher's list is also kept in the
 * offline cache (class names are not child data), and every successful refresh purges what is cached for classes
 * they no longer teach.
 */
export function useMyClasses() {
  return useCachedQuery<MyClass[]>({
    queryKey: myClassesKey,
    enabled: true,
    kind: 'classes',
    scope: '',
    fetch: () =>
      request(async () => {
        const classes = unwrap(await api.GET('/api/v1/me/classes'));
        void offlineCache.purgeClassesExcept(classes.filter((cls) => cls.role === 'TEACHER').map((cls) => cls.id)).catch(() => {});
        return classes;
      }),
    toCache: (classes) => classes.filter((cls) => cls.role === 'TEACHER'),
    fromCache: (stored) => (Array.isArray(stored) ? (stored as MyClass[]) : null),
  });
}

/** One of the caller's classes, found in the cached list (it carries the school id every other call needs). */
export function useMyClass(classId: string | undefined) {
  const query = useMyClasses();
  const cls = query.data?.find((item) => item.id === classId);
  return { query, cls };
}

export function useClassDetail(schoolId: string | undefined, classId: string | undefined) {
  return useQuery({
    queryKey: classKey(classId ?? ''),
    enabled: !!schoolId && !!classId,
    queryFn: () =>
      request(async () =>
        unwrap(await api.GET('/api/v1/schools/{schoolId}/classes/{classId}', { params: { path: { schoolId: schoolId!, classId: classId! } } })),
      ),
  });
}

export function useClassTeachers(schoolId: string | undefined, classId: string | undefined) {
  return useQuery({
    queryKey: classTeachersKey(classId ?? ''),
    enabled: !!schoolId && !!classId,
    queryFn: () =>
      request(async () =>
        unwrap(
          await api.GET('/api/v1/schools/{schoolId}/classes/{classId}/teachers', {
            params: { path: { schoolId: schoolId!, classId: classId! } },
          }),
        ),
      ),
  });
}

/**
 * The class roster. What is kept offline is id, name and avatar only (`rosterToCache`): date of birth and the like
 * are fetched on the student's own screen and never persisted.
 */
export function useRoster(schoolId: string | undefined, classId: string | undefined) {
  return useCachedQuery({
    queryKey: rosterKey(classId ?? ''),
    enabled: !!schoolId && !!classId,
    kind: 'roster',
    scope: classId ?? '',
    fetch: () =>
      request(async () =>
        toRoster(
          unwrap(
            await api.GET('/api/v1/schools/{schoolId}/classes/{classId}/students', {
              params: { path: { schoolId: schoolId!, classId: classId! } },
            }),
          ),
        ),
      ),
    toCache: rosterToCache,
    fromCache: (stored) => {
      const roster = rosterFromCache(stored);
      return roster.length > 0 || (Array.isArray(stored) && stored.length === 0) ? roster : null;
    },
  });
}

/** One student's record (FULL for the student's teachers). Not persisted offline. */
export function useStudent(schoolId: string | undefined, studentId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: studentKey(schoolId ?? '', studentId ?? ''),
    enabled: enabled && !!schoolId && !!studentId,
    queryFn: () =>
      request(async () =>
        unwrap(
          await api.GET('/api/v1/schools/{schoolId}/students/{studentId}', {
            params: { path: { schoolId: schoolId!, studentId: studentId! } },
          }),
        ),
      ),
  });
}

const SEARCH_PAGE = 50;

type StudentPage = components['schemas']['CursorPageStudentView'];

/** The school's students the caller may see (teachers: students of their own classes), filtered by name. */
export function useStudentSearch(schoolId: string | undefined, q: string) {
  return useInfiniteQuery({
    queryKey: studentSearchKey(schoolId ?? '', q),
    enabled: !!schoolId,
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last: StudentPage) => last.nextCursor ?? undefined,
    queryFn: ({ pageParam }): Promise<StudentPage> =>
      request(async () =>
        unwrap(
          await api.GET('/api/v1/schools/{schoolId}/students', {
            params: {
              path: { schoolId: schoolId! },
              query: { limit: SEARCH_PAGE, ...(q ? { q } : {}), ...(pageParam ? { cursor: pageParam } : {}) },
            },
          }),
        ),
      ),
  });
}

/** After a roster change: the roster, the class counts and the student lists are stale. */
export function invalidateRoster(queryClient: QueryClient, classId: string): Promise<unknown> {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: rosterKey(classId) }),
    queryClient.invalidateQueries({ queryKey: classKey(classId) }),
    queryClient.invalidateQueries({ queryKey: myClassesKey }),
    queryClient.invalidateQueries({ queryKey: [STUDENT_SEARCH_ROOT] }),
    queryClient.invalidateQueries({ queryKey: [STUDENT_ROOT] }),
  ]);
}

export function useRosterInvalidation() {
  const queryClient = useQueryClient();
  return (classId: string) => invalidateRoster(queryClient, classId);
}
