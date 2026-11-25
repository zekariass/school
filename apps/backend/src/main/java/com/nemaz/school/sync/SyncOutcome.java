package com.nemaz.school.sync;

import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * What a handler did with an operation. {@code state} is what the client needs to show: the server's state after an
 * APPLIED, the server's current state (with the client's rejected values) for a CONFLICT. It is returned again,
 * unchanged, for every replay (until the retention job removes it), so keep it small and free of anything the caller
 * may not see.
 */
public record SyncOutcome(Kind kind, UUID schoolId, UUID entityId, Integer version, JsonNode state) {

	public enum Kind {

		APPLIED, CONFLICT,
		/** Already applied through another path (the online endpoint with the same client operation id). */
		DUPLICATE

	}

	public static SyncOutcome applied(UUID schoolId, UUID entityId, Integer version, JsonNode state) {
		return new SyncOutcome(Kind.APPLIED, schoolId, entityId, version, state);
	}

	public static SyncOutcome conflict(UUID schoolId, UUID entityId, Integer version, JsonNode state) {
		return new SyncOutcome(Kind.CONFLICT, schoolId, entityId, version, state);
	}

	public static SyncOutcome duplicate(UUID schoolId, UUID entityId, Integer version, JsonNode state) {
		return new SyncOutcome(Kind.DUPLICATE, schoolId, entityId, version, state);
	}

}
