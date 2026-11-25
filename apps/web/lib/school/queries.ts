import type { components } from "@school/api-client";
import { unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";

type S = components["schemas"];

export type SchoolCounts = S["SchoolCounts"];
export type AcademicYear = S["AcademicYearView"];
export type AcademicPeriod = S["AcademicPeriodView"];
export type GradeLevel = S["GradeLevelView"];
export type Subject = S["SubjectView"];
export type ClassView = S["ClassView"];
export type RosterStudent = S["RosterStudent"];
export type ClassTeacher = S["ClassTeacherView"];
export type StudentGroup = S["StudentGroupView"];
export type StaffView = S["StaffView"];
export type StudentView = S["StudentView"];
export type LoginCodeInfo = S["LoginCodeInfo"];
export type IssuedCode = S["IssuedCode"];
export type Campus = S["CampusResponse"];
export type MediaSummary = S["MediaSummary"];
export type TeacherRole = ClassTeacher["role"];
export type ClassType = ClassView["classType"];

/** Page size of the school lists ("Load more"). */
export const PAGE_SIZE = 24;
/** Pickers and roster lists load one big page. */
const PICKER_LIMIT = 100;

const api = () => getBrowserApi();

export const sk = {
  all: (schoolId: string) => ["school", schoolId] as const,
  summary: (schoolId: string) => ["school", schoolId, "summary"] as const,
  years: (schoolId: string) => ["school", schoolId, "years"] as const,
  periods: (schoolId: string, yearId: string) => ["school", schoolId, "years", yearId, "periods"] as const,
  grades: (schoolId: string) => ["school", schoolId, "grades"] as const,
  subjects: (schoolId: string) => ["school", schoolId, "subjects"] as const,
  campuses: (schoolId: string) => ["school", schoolId, "campuses"] as const,
  classes: (schoolId: string) => ["school", schoolId, "classes"] as const,
  classList: (schoolId: string, filters: object) => ["school", schoolId, "classes", "list", filters] as const,
  classOne: (schoolId: string, classId: string) => ["school", schoolId, "classes", "one", classId] as const,
  classRoster: (schoolId: string, classId: string) => ["school", schoolId, "classes", "roster", classId] as const,
  classTeachers: (schoolId: string, classId: string) => ["school", schoolId, "classes", "teachers", classId] as const,
  classGroups: (schoolId: string, classId: string) => ["school", schoolId, "classes", "groups", classId] as const,
  staff: (schoolId: string) => ["school", schoolId, "staff"] as const,
  staffList: (schoolId: string, filters: object) => ["school", schoolId, "staff", "list", filters] as const,
  students: (schoolId: string) => ["school", schoolId, "students"] as const,
  studentList: (schoolId: string, filters: object) => ["school", schoolId, "students", "list", filters] as const,
  studentOne: (schoolId: string, studentId: string) => ["school", schoolId, "students", "one", studentId] as const,
  studentCodes: (schoolId: string, studentId: string) => ["school", schoolId, "students", "codes", studentId] as const,
  configuration: (schoolId: string) => ["school", schoolId, "configuration"] as const,
  settingOverrides: (schoolId: string) => ["school", schoolId, "setting-overrides"] as const,
};

export const definitionsKey = ["school", "definitions"] as const;

export const fetchSummary = async (schoolId: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/summary", { params: { path: { schoolId } } }));

export const fetchYears = async (schoolId: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/academic-years", { params: { path: { schoolId } } }));

export const fetchPeriods = async (schoolId: string, yearId: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/academic-years/{yearId}/periods", {
      params: { path: { schoolId, yearId } },
    }),
  );

export const fetchGrades = async (schoolId: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/grade-levels", { params: { path: { schoolId } } }));

export const fetchSubjects = async (schoolId: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/subjects", { params: { path: { schoolId } } }));

export const fetchCampuses = async (schoolId: string) =>
  unwrap(
    await api().GET("/api/v1/platform/schools/{schoolId}/campuses", {
      params: { path: { schoolId }, query: { limit: PICKER_LIMIT } },
    }),
  );

export interface ClassFilters {
  academicYearId?: string;
  gradeLevelId?: string;
  status?: "ACTIVE" | "ARCHIVED";
  mine?: boolean;
  q?: string;
}

export const fetchClasses = async (schoolId: string, filters: ClassFilters, cursor?: string, limit = PAGE_SIZE) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/classes", {
      params: { path: { schoolId }, query: { ...filters, q: filters.q || undefined, cursor, limit } },
    }),
  );

export const fetchClass = async (schoolId: string, classId: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/classes/{classId}", { params: { path: { schoolId, classId } } }),
  );

export const fetchRoster = async (schoolId: string, classId: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/classes/{classId}/students", {
      params: { path: { schoolId, classId } },
    }),
  );

export const fetchClassTeachers = async (schoolId: string, classId: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/classes/{classId}/teachers", {
      params: { path: { schoolId, classId } },
    }),
  );

export const fetchGroups = async (schoolId: string, classId: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/classes/{classId}/groups", {
      params: { path: { schoolId, classId } },
    }),
  );

export interface StaffFilters {
  staffType?: StaffView["staffType"];
  status?: StaffView["status"];
  q?: string;
}

export const fetchStaff = async (schoolId: string, filters: StaffFilters, cursor?: string, limit = PAGE_SIZE) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/staff", {
      params: { path: { schoolId }, query: { ...filters, q: filters.q || undefined, cursor, limit } },
    }),
  );

export interface StudentFilters {
  classId?: string;
  gradeLevelId?: string;
  status?: NonNullable<StudentView["status"]>;
  q?: string;
}

export const fetchStudents = async (schoolId: string, filters: StudentFilters, cursor?: string, limit = PAGE_SIZE) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/students", {
      params: { path: { schoolId }, query: { ...filters, q: filters.q || undefined, cursor, limit } },
    }),
  );

export const fetchStudent = async (schoolId: string, studentId: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/students/{studentId}", {
      params: { path: { schoolId, studentId } },
    }),
  );

export const fetchStudentCodes = async (schoolId: string, studentId: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/students/{studentId}/login-codes", {
      params: { path: { schoolId, studentId } },
    }),
  );

export const fetchConfiguration = async (schoolId: string) =>
  unwrap(
    await api().GET("/api/v1/platform/schools/{schoolId}/configuration", { params: { path: { schoolId } } }),
  );

export const fetchDefinitions = async () => unwrap(await api().GET("/api/v1/platform/definitions"));

export const fetchSchoolSettingOverrides = async (schoolId: string) =>
  unwrap(
    await api().GET("/api/v1/platform/settings", { params: { query: { scopeType: "SCHOOL", schoolId } } }),
  );
