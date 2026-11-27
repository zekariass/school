package com.nemaz.school.sync;

import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * What a handler did with an operation. {@code state} is what the client needs to show: the server's state after an
 * APPLIED, the server's current state (with the client's rejected values) for a CONFLICT. It is returned again,
 * unchanged, for every replay (until the retention job removes it), so keep it small and free of anything the caller
 * may not see.
 */
public record SyncOutcome(Kind kind, UUID schoolId, UUID entityId, Integer version, JsonNode state, String problemCode,
		int problemStatus) {

	public enum Kind {

		APPLIED, CONFLICT,
		/** Already applied through another path (the online endpoint with the same client operation id). */
		DUPLICATE,
		/**
		 * The operation can never succeed as sent, but what the handler wrote while finding out must stay (the ledger of a
		 * refused check-in): unlike throwing an {@link com.nemaz.school.common.error.ApiException}, which rolls the whole
		 * operation back, this commits the handler's writes and stores the operation as REJECTED with the problem.
		 */
		REJECTED

	}

	public static SyncOutcome applied(UUID schoolId, UUID entityId, Integer version, JsonNode state) {
		return new SyncOutcome(Kind.APPLIED, schoolId, entityId, version, state, null, 0);
	}

	public static SyncOutcome conflict(UUID schoolId, UUID entityId, Integer version, JsonNode state) {
		return new SyncOutcome(Kind.CONFLICT, schoolId, entityId, version, state, null, 0);
	}

	public static SyncOutcome duplicate(UUID schoolId, UUID entityId, Integer version, JsonNode state) {
		return new SyncOutcome(Kind.DUPLICATE, schoolId, entityId, version, state, null, 0);
	}

	/** Refused for good with the stable error {@code problemCode} (and its HTTP status), keeping the handler's own writes. */
	public static SyncOutcome rejected(UUID schoolId, String problemCode, int problemStatus) {
		return new SyncOutcome(Kind.REJECTED, schoolId, null, null, null, problemCode, problemStatus);
	}

}
