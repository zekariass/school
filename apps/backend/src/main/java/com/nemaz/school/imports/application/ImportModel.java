package com.nemaz.school.imports.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.imports.domain.DateParser.DateSystem;
import com.nemaz.school.imports.domain.ImportType;

/** The import job as the database holds it, and the small value types the processors share. */
public final class ImportModel {

	private ImportModel() {
	}

	public enum Status {

		UPLOADED, PREVIEWED, VALIDATING, VALIDATED, COMMITTING, COMPLETED, FAILED, CANCELLED

	}

	public enum RowStatus {

		VALID, INVALID, DUPLICATE, SKIPPED

	}

	/** What the dry run intends for a row (null while a possible duplicate is unresolved). */
	public enum Action {

		CREATE, UPDATE, NONE

	}

	record Job(UUID id, UUID schoolId, ImportType type, UUID mediaId, String filename, String fingerprint,
			UUID mappingProfileId, Map<String, Integer> columnMapping, ImportOptions options, Map<String, Object> detected,
			List<String> headers, UUID externalSystemId, Status status, Integer totalRows, Integer validRows,
			Integer errorRows, Integer duplicateRows, Integer createdCount, Integer updatedCount, Integer unchangedCount,
			Integer skippedCount, int progressDone, int progressTotal, String validatedHash, String failurePhase,
			Integer failureRowNumber, String failureCode, UUID initiatedBy, Instant startedAt, Instant completedAt,
			Instant rowsPurgedAt, Instant createdAt, Instant updatedAt) {
	}

	/** A stored row. {@code raw} and {@code normalized} are null once purged. */
	record Row(UUID id, UUID jobId, int rowNumber, List<String> raw, Map<String, Object> normalized, String rowHash,
			RowStatus status, Action action, List<Map<String, Object>> errors, List<Map<String, Object>> warnings,
			List<Map<String, Object>> candidates, String resolution, UUID resolvedEntityId, String entityType, UUID entityId,
			String applyResult, Instant appliedAt) {
	}

	record Counts(int total, int valid, int invalid, int duplicate, int duplicateOpen, int skipped, int toCreate,
			int toUpdate, int unchanged) {
	}

}
