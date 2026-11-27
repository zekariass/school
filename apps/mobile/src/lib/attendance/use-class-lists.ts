import { api } from '@/lib/api';
import { request, unwrap } from '@/lib/api-error';
import { groupsFromCache, groupsToCache, subjectsFromCache, subjectsToCache, type CachedGroup, type CachedSubject } from '@/lib/offline/cache';
import { useCachedQuery } from '@/lib/offline/use-cached-query';

import { classGroupsKey, classSubjectsKey } from './keys';
import { activeClassSubjects } from './period';

/**
 * The class's active subjects, for the subject of a period roll. A subject is catalogue data (an id and a name):
 * it is kept offline so that a period roll can be started with no connection.
 */
export function useClassSubjects(schoolId: string | undefined, classId: string | undefined, enabled = true) {
  return useCachedQuery<CachedSubject[]>({
    queryKey: classSubjectsKey(classId ?? ''),
    enabled: enabled && !!schoolId && !!classId,
    kind: 'subjects',
    scope: classId ?? '',
    fetch: () =>
      request(async () => {
        const [detail, catalogue] = await Promise.all([
          api.GET('/api/v1/schools/{schoolId}/classes/{classId}', { params: { path: { schoolId: schoolId!, classId: classId! } } }),
          api.GET('/api/v1/schools/{schoolId}/subjects', { params: { path: { schoolId: schoolId! } } }),
        ]);
        return activeClassSubjects(unwrap(detail).subjectIds, unwrap(catalogue));
      }),
    toCache: subjectsToCache,
    fromCache: subjectsFromCache,
  });
}

/**
 * The class's saved student groups (an elective's roster; `GET .../groups`, teachers of the class). Kept offline as
 * ids and names so an elective's roll can be cut from the class roll with no connection.
 */
export function useClassGroups(schoolId: string | undefined, classId: string | undefined, enabled = true) {
  return useCachedQuery<CachedGroup[]>({
    queryKey: classGroupsKey(classId ?? ''),
    enabled: enabled && !!schoolId && !!classId,
    kind: 'groups',
    scope: classId ?? '',
    fetch: () =>
      request(async () => {
        const groups = unwrap(await api.GET('/api/v1/schools/{schoolId}/classes/{classId}/groups', { params: { path: { schoolId: schoolId!, classId: classId! } } }));
        return groups.map((group) => ({ id: group.id, name: group.name, studentIds: group.studentIds }));
      }),
    toCache: groupsToCache,
    fromCache: groupsFromCache,
  });
}
