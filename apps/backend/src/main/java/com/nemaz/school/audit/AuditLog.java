package com.nemaz.school.audit;

import java.util.Map;

/**
 * Writes audit rows in the caller's transaction (a JDBC insert on the transaction's
 * connection), so a rolled-back change leaves no audit row and a committed one always has
 * one. Captured automatically: the acting {@code CurrentUser} and their roles (unless
 * {@link AuditEntry#actor} overrides it), the request id, client IP and user agent.
 *
 * <p>
 * Never put secrets in an entry: no codes, passwords, tokens, keys, or full phone numbers
 * and emails (use {@code common.security.Masking}).
 */
public interface AuditLog {

	void record(AuditEntry entry);

	default void record(String action, String targetType, java.util.UUID targetId, AuditOutcome outcome,
			Map<String, ?> metadata) {
		record(AuditEntry.of(action, outcome).target(targetType, targetId).metadata(metadata));
	}

}
