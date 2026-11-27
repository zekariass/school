import { getBrowserApi } from "@/lib/api-client";
import { unwrap } from "@/lib/api-error";
import type {
  ConcernRequest,
  DecisionRequest,
  IncidentOpenReason,
  Priority,
  ReportRequest,
} from "./types";

const api = () => getBrowserApi();

export const QUEUE_PAGE = 20;
export const INCIDENT_PAGE = 20;

export interface QueueFilters {
  status: string;
  priority: string;
  targetType: string;
}

/** Filters that mean "no filter" are left out of the query string. */
function queueQuery(filters: QueueFilters, cursor?: string) {
  return {
    status: filters.status === "OPEN" ? undefined : filters.status,
    priority: filters.priority || undefined,
    targetType: filters.targetType || undefined,
    cursor,
    limit: QUEUE_PAGE,
  };
}

// ---- reports -----------------------------------------------------------------------------------------------------

/** Files a report. The `Idempotency-Key` makes a repeated submit answer with the first report. */
export const createReport = async (body: ReportRequest, idempotencyKey: string) =>
  unwrap(await api().POST("/api/v1/moderation/reports", { headers: { "Idempotency-Key": idempotencyKey }, body }));

export const fetchEmergencyInfo = async (country: string) =>
  unwrap(await api().GET("/api/v1/safety/emergency-info", { params: { query: { country } } }));

// ---- queues and cases --------------------------------------------------------------------------------------------

export const fetchSchoolQueue = async (schoolId: string, filters: QueueFilters, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/moderation/cases", {
      params: { path: { schoolId }, query: queueQuery(filters, cursor) },
    }),
  );

export const fetchPlatformQueue = async (filters: QueueFilters, cursor?: string) =>
  unwrap(await api().GET("/api/v1/platform/moderation/cases", { params: { query: queueQuery(filters, cursor) } }));

export const fetchCase = async (caseId: string) =>
  unwrap(await api().GET("/api/v1/moderation/cases/{caseId}", { params: { path: { caseId } } }));

/** The audited, rate-limited, case-bound read of the reported content (60 an hour): only on a person's request. */
export const fetchCaseContent = async (caseId: string) =>
  unwrap(await api().GET("/api/v1/moderation/cases/{caseId}/content", { params: { path: { caseId } } }));

/** Takes the case (no assignee: the API assigns the caller). */
export const assignCase = async (caseId: string) =>
  unwrap(await api().POST("/api/v1/moderation/cases/{caseId}/assign", { params: { path: { caseId } }, body: {} }));

export const decideCase = async (caseId: string, body: DecisionRequest) =>
  unwrap(await api().POST("/api/v1/moderation/cases/{caseId}/decision", { params: { path: { caseId } }, body }));

export type ReasonAction = "reopen" | "restore" | "lift-suspension" | "refer-up";

/** The four moves that need only a reason of 10 to 500 characters. */
export async function reasonedCaseAction(caseId: string, action: ReasonAction, reason: string) {
  const params = { path: { caseId } };
  const body = { reason };
  switch (action) {
    case "reopen":
      return unwrap(await api().POST("/api/v1/moderation/cases/{caseId}/reopen", { params, body }));
    case "restore":
      return unwrap(await api().POST("/api/v1/moderation/cases/{caseId}/restore", { params, body }));
    case "lift-suspension":
      return unwrap(await api().POST("/api/v1/moderation/cases/{caseId}/lift-suspension", { params, body }));
    case "refer-up":
      return unwrap(await api().POST("/api/v1/moderation/cases/{caseId}/refer-up", { params, body }));
  }
}

export const setCasePriority = async (caseId: string, priority: Priority) =>
  unwrap(await api().PUT("/api/v1/moderation/cases/{caseId}/priority", { params: { path: { caseId } }, body: { priority } }));

export const setCaseLegalHold = async (caseId: string, held: boolean) =>
  unwrap(await api().PUT("/api/v1/moderation/cases/{caseId}/legal-hold", { params: { path: { caseId } }, body: { held } }));

// ---- safeguarding ------------------------------------------------------------------------------------------------

export const fetchSafeguardingCapability = async (schoolId: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/safeguarding/capability", { params: { path: { schoolId } } }));

export const fetchSchoolIncidents = async (schoolId: string, filters: { status: string; severity: string; category: string }, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/safeguarding/incidents", {
      params: {
        path: { schoolId },
        query: { status: filters.status || undefined, severity: filters.severity || undefined, category: filters.category || undefined, cursor, limit: INCIDENT_PAGE },
      },
    }),
  );

export const fetchPlatformIncidents = async (filters: { status: string; severity: string; category: string }, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/platform/safeguarding/incidents", {
      params: {
        query: { status: filters.status || undefined, severity: filters.severity || undefined, category: filters.category || undefined, cursor, limit: INCIDENT_PAGE },
      },
    }),
  );

/** Raises a concern (staff). The answer is the incident's id only: whoever raises a concern cannot read it back. */
export const raiseConcern = async (schoolId: string, body: ConcernRequest, idempotencyKey: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/safeguarding/concerns", {
      params: { path: { schoolId } },
      headers: { "Idempotency-Key": idempotencyKey },
      body,
    }),
  );

export const fetchIncident = async (incidentId: string) =>
  unwrap(await api().GET("/api/v1/safeguarding/incidents/{incidentId}", { params: { path: { incidentId } } }));

/** Opens the incident: the decrypted narrative and notes. Audited with the reason; rate limited (60 an hour). */
export const openIncident = async (incidentId: string, reason: IncidentOpenReason) =>
  unwrap(await api().POST("/api/v1/safeguarding/incidents/{incidentId}/open", { params: { path: { incidentId } }, body: { reason } }));

export const addIncidentNote = async (incidentId: string, note: string) =>
  unwrap(await api().POST("/api/v1/safeguarding/incidents/{incidentId}/notes", { params: { path: { incidentId } }, body: { note } }));

export const changeIncidentStatus = async (incidentId: string, status: string, note?: string) =>
  unwrap(await api().POST("/api/v1/safeguarding/incidents/{incidentId}/status", { params: { path: { incidentId } }, body: { status, ...(note ? { note } : {}) } }));

export const assignIncident = async (incidentId: string, assigneeId: string) =>
  unwrap(await api().POST("/api/v1/safeguarding/incidents/{incidentId}/assign", { params: { path: { incidentId } }, body: { assigneeId } }));

export const recordGuardianInformed = async (incidentId: string, body: { at?: string; note?: string }) =>
  unwrap(await api().POST("/api/v1/safeguarding/incidents/{incidentId}/guardian-informed", { params: { path: { incidentId } }, body }));

export const recordReferral = async (incidentId: string, body: { referredTo: string; at?: string; note?: string }) =>
  unwrap(await api().POST("/api/v1/safeguarding/incidents/{incidentId}/referral", { params: { path: { incidentId } }, body }));

export const setIncidentLegalHold = async (incidentId: string, held: boolean) =>
  unwrap(await api().PUT("/api/v1/safeguarding/incidents/{incidentId}/legal-hold", { params: { path: { incidentId } }, body: { held } }));
