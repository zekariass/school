package com.nemaz.school.sync;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * One queued operation as the handler sees it. {@code recordedAt} is the device time of the user's action, clamped into
 * a sane window around {@code receivedAt} (never in the future, never older than the sync service accepts): use it as
 * the "when did it happen" of the data, never to decide who wins.
 */
public record SyncOperation(UUID deviceId, UUID clientOperationId, String operationType, String entityType,
		Integer baseVersion, JsonNode payload, Instant clientCreatedAt, Instant recordedAt, Instant receivedAt) {

	@Override
	public String toString() {
		return "SyncOperation[" + this.operationType + " " + this.clientOperationId + "]";
	}

}
