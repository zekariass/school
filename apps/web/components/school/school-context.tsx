"use client";

import { createContext, useContext, useMemo } from "react";
import type { components } from "@school/api-client";
import { SP } from "@/lib/school/access";

export interface SchoolSession {
  school: {
    id: string;
    slug: string;
    name: string;
    /** The school's IANA zone. */
    timezone: string;
    /** The school's country: calling code, calendars and the languages its texts can be written in. */
    country: components["schemas"]["CountryConfig"];
  };
  userId: string;
  /** Permission codes that apply to this school (the API still checks every call). */
  permissions: ReadonlySet<string>;
  isTeacher: boolean;
  isStaff: boolean;
}

const SchoolContext = createContext<SchoolSession | null>(null);

export function SchoolProvider({
  school,
  userId,
  permissions,
  isTeacher,
  isStaff,
  children,
}: {
  school: SchoolSession["school"];
  userId: string;
  permissions: string[];
  isTeacher: boolean;
  isStaff: boolean;
  children: React.ReactNode;
}) {
  const value = useMemo<SchoolSession>(
    () => ({ school, userId, permissions: new Set(permissions), isTeacher, isStaff }),
    [school, userId, permissions, isTeacher, isStaff],
  );
  return <SchoolContext.Provider value={value}>{children}</SchoolContext.Provider>;
}

export function useSchool(): SchoolSession {
  const value = useContext(SchoolContext);
  if (!value) throw new Error("useSchool needs a SchoolProvider");
  return value;
}

/** True when the user holds `permission` for this school. */
export function useSchoolCan(permission: string): boolean {
  return useSchool().permissions.has(permission);
}

/** Convenience flags for the current school. */
export function useSchoolRoles() {
  const { permissions, isTeacher } = useSchool();
  return {
    isClassAdmin: permissions.has(SP.classManage),
    isTeacher,
    canReadSchool: permissions.has(SP.read),
  };
}
