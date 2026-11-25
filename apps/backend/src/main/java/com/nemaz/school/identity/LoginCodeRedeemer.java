package com.nemaz.school.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Implemented by the module that owns student login codes (people). {@code POST /auth/login-code}
 * hands it whatever the client submitted; it answers with the student to sign in, or empty for every
 * kind of failure (unknown, revoked, expired code; withdrawn student; inactive school), so callers can
 * only ever give one uniform error.
 */
public interface LoginCodeRedeemer {

	/** Validates the code and records its use ({@code last_used_at}). Never throws for a bad code. */
	Optional<LoginCodeGrant> redeem(String submitted, Instant now);

	/** {@code schoolId} and {@code codeId} are for the audit log. */
	record LoginCodeGrant(UUID userId, UUID schoolId, UUID codeId) {
	}

}
