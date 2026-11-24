import type { components } from "@school/api-client";
import { unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";

type S = components["schemas"];

export type School = S["SchoolResponse"];
export type Campus = S["CampusResponse"];
export type Organization = S["OrganizationResponse"];
export type Country = S["CountryResponse"];
export type ScopeRef = S["ScopeRef"];
export type SchoolStatus = School["status"];
export type RoleAssignment = S["RoleAssignmentResponse"];
export type AuditEntry = S["AuditLogResponse"];
export type PlanInfo = S["PlanInfo"];
export type Definitions = S["DefinitionsResponse"];
export type SchoolConfiguration = S["SchoolConfigurationResponse"];

/** The page size the console asks for (the API caps it). */
export const PAGE_SIZE = 25;
/** Pickers load one big page instead of paging. */
const PICKER_LIMIT = 100;

export const queryKeys = {
  countries: ["reference", "countries"] as const,
  languages: ["reference", "languages"] as const,
  plans: ["platform", "plans"] as const,
  definitions: ["platform", "definitions"] as const,
  schools: (filters: object) => ["platform", "schools", filters] as const,
  schoolsAll: ["platform", "schools"] as const,
  school: (id: string) => ["platform", "school", id] as const,
  campuses: (schoolId: string) => ["platform", "school", schoolId, "campuses"] as const,
  configuration: (schoolId: string) => ["platform", "school", schoolId, "configuration"] as const,
  admins: (schoolId: string) => ["platform", "school", schoolId, "admins"] as const,
  organizations: ["platform", "organizations"] as const,
  user: (id: string) => ["platform", "user", id] as const,
  featureOverrides: (scope: ScopeRef) => ["platform", "feature-overrides", scope] as const,
  settingOverrides: (scope: ScopeRef) => ["platform", "setting-overrides", scope] as const,
  audit: (filters: object) => ["platform", "audit", filters] as const,
};

const api = () => getBrowserApi();

export async function fetchCountries(): Promise<Country[]> {
  return unwrap(await api().GET("/api/v1/reference/countries"));
}

export async function fetchPlans(): Promise<PlanInfo[]> {
  return unwrap(await api().GET("/api/v1/platform/plans"));
}

export async function fetchDefinitions(): Promise<Definitions> {
  return unwrap(await api().GET("/api/v1/platform/definitions"));
}

export interface SchoolFilters {
  status?: SchoolStatus;
  countryCode?: string;
  organizationId?: string;
}

export async function fetchSchools(filters: SchoolFilters, cursor?: string, limit = PAGE_SIZE) {
  return unwrap(
    await api().GET("/api/v1/platform/schools", {
      params: { query: { ...filters, cursor, limit } },
    }),
  );
}

export async function fetchSchool(schoolId: string): Promise<School> {
  return unwrap(await api().GET("/api/v1/platform/schools/{schoolId}", { params: { path: { schoolId } } }));
}

export async function fetchCampuses(schoolId: string) {
  return unwrap(
    await api().GET("/api/v1/platform/schools/{schoolId}/campuses", {
      params: { path: { schoolId }, query: { limit: PICKER_LIMIT } },
    }),
  );
}

export async function fetchSchoolConfiguration(schoolId: string): Promise<SchoolConfiguration> {
  return unwrap(
    await api().GET("/api/v1/platform/schools/{schoolId}/configuration", { params: { path: { schoolId } } }),
  );
}

export async function fetchOrganizations(cursor?: string, limit = PICKER_LIMIT) {
  return unwrap(
    await api().GET("/api/v1/platform/organizations", { params: { query: { cursor, limit } } }),
  );
}

export async function fetchSchoolAdmins(schoolId: string) {
  return unwrap(
    await api().GET("/api/v1/platform/role-assignments", {
      params: { query: { scopeType: "SCHOOL", schoolId, status: "ACTIVE", limit: PICKER_LIMIT } },
    }),
  );
}

export async function fetchUser(id: string) {
  return unwrap(await api().GET("/api/v1/platform/users/{id}", { params: { path: { id } } }));
}

export async function fetchFeatureOverrides(scope: ScopeRef) {
  return unwrap(
    await api().GET("/api/v1/platform/feature-overrides", {
      params: {
        query: {
          scopeType: scope.type,
          countryCode: scope.countryCode,
          organizationId: scope.organizationId,
          schoolId: scope.schoolId,
        },
      },
    }),
  );
}

export async function fetchSettingOverrides(scope: ScopeRef) {
  return unwrap(
    await api().GET("/api/v1/platform/settings", {
      params: {
        query: {
          scopeType: scope.type,
          countryCode: scope.countryCode,
          organizationId: scope.organizationId,
          schoolId: scope.schoolId,
        },
      },
    }),
  );
}

export interface AuditFilters {
  actionPrefix?: string;
  actorUserId?: string;
  targetType?: string;
  schoolId?: string;
  from?: string;
  to?: string;
}

export async function fetchAudit(filters: AuditFilters, cursor?: string) {
  return unwrap(
    await api().GET("/api/v1/platform/audit-logs", {
      params: { query: { ...filters, cursor, limit: PAGE_SIZE } },
    }),
  );
}
