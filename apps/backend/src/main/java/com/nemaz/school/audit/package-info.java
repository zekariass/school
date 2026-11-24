/**
 * Audit log (Database_Design §4.19): an append-only, monthly partitioned record of who did
 * what, from where, with what outcome. Other modules write through {@link com.nemaz.school.audit.AuditLog}
 * inside their own transaction; platform administrators read it through
 * {@code GET /api/v1/platform/audit-logs}. No code path updates or deletes audit rows.
 */
@ApplicationModule(displayName = "Audit")
package com.nemaz.school.audit;

import org.springframework.modulith.ApplicationModule;
