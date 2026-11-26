/**
 * Query keys of messaging. Message text, names and drafts are personal data about children: they live only in the
 * in-memory TanStack cache (short `gcTime`), never in a URL, storage or log. Ids only appear in keys.
 */
export const mk = {
  all: ["messaging"] as const,
  inboxAll: ["messaging", "inbox"] as const,
  inbox: (schoolId: string | null, studentId: string | null) => ["messaging", "inbox", schoolId ?? "all", studentId ?? "all"] as const,
  unread: ["messaging", "unread"] as const,
  scheduled: ["messaging", "scheduled"] as const,
  conversation: (id: string) => ["messaging", "conversation", id] as const,
  members: (id: string) => ["messaging", "conversation", id, "members"] as const,
  messages: (id: string) => ["messaging", "messages", id] as const,
  capabilities: (schoolId: string) => ["messaging", "capabilities", schoolId] as const,
  contacts: (schoolId: string, studentId: string, audience: string) => ["messaging", "contacts", schoolId, studentId, audience] as const,
  groups: (schoolId: string, status: string) => ["messaging", "groups", schoolId, status] as const,
  blocks: ["messaging", "blocks"] as const,
  review: (schoolId: string) => ["messaging", "review", schoolId] as const,
  reviewConversations: (schoolId: string) => ["messaging", "review", schoolId, "conversations"] as const,
  reviewReports: (schoolId: string, status: string) => ["messaging", "review", schoolId, "reports", status] as const,
  reviewBlocks: (schoolId: string) => ["messaging", "review", schoolId, "blocks"] as const,
};

/** Memory-only, short-lived: a closed conversation does not stay in the browser for long. */
export const MESSAGING_GC_MS = 60_000;
