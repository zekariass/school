"use client";

import { useQuery } from "@tanstack/react-query";
import { fetchConfiguration, sk } from "@/lib/school/queries";
import { useSchool } from "./school-context";
import { SP } from "@/lib/school/access";

/**
 * The effective value of a school setting (`GET .../configuration`, needs `school.read`).
 * `undefined` while loading, when the caller may not read it, or when the key is unknown.
 */
export function useSchoolSetting<T>(key: string): { value: T | undefined; isPending: boolean } {
  const { school, permissions } = useSchool();
  const enabled = permissions.has(SP.read);
  const configuration = useQuery({
    queryKey: sk.configuration(school.id),
    queryFn: () => fetchConfiguration(school.id),
    enabled,
    retry: false,
  });
  const setting = configuration.data?.settings.find((s) => s.key === key);
  return { value: setting?.value as T | undefined, isPending: enabled && configuration.isPending };
}
