/**
 * Query keys of the moderation and safeguarding screens. Case and incident data is about children: it lives only in the
 * in-memory TanStack cache, and what carries text (a case's detail, the reported content) is dropped as soon as the
 * screen that asked for it is gone (`gcTime` 0). Ids only ever appear in keys, never text.
 */
export const modk = {
  all: ["moderation"] as const,
  queueAll: ["moderation", "queue"] as const,
  schoolQueue: (schoolId: string, status: string, priority: string, targetType: string) =>
    ["moderation", "queue", schoolId, status, priority, targetType] as const,
  platformQueue: (status: string, priority: string, targetType: string) => ["moderation", "queue", "platform", status, priority, targetType] as const,
  case: (caseId: string) => ["moderation", "case", caseId] as const,
  content: (caseId: string) => ["moderation", "case", caseId, "content"] as const,
  emergency: (country: string) => ["moderation", "emergency", country] as const,
  incidentsAll: ["moderation", "incidents"] as const,
  incidents: (scope: string, status: string, severity: string, category: string) => ["moderation", "incidents", scope, status, severity, category] as const,
  incident: (incidentId: string) => ["moderation", "incident", incidentId] as const,
  capability: (schoolId: string) => ["moderation", "capability", schoolId] as const,
};

/** Lists of references and enums only (no text): a few minutes in memory. */
export const QUEUE_GC_MS = 3 * 60_000;

/** A case's detail (reporter details, decision reasons) and the reported content: not kept once nobody is looking. */
export const CONTENT_GC_MS = 0;
