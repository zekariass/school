package com.nemaz.school.imports.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.imports.application.ImportModel.Action;
import com.nemaz.school.imports.application.ImportModel.RowStatus;
import com.nemaz.school.imports.application.ImportModel.Status;
import com.nemaz.school.imports.domain.ImportType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** What the import API returns. Messages are rendered in the reader's language when the response is built. */
public final class ImportViews {

	private ImportViews() {
	}

	@Schema(description = "How a possible duplicate is decided: LINK to an existing student (studentId), CREATE_NEW, or SKIP the row")
	public enum Resolution {

		LINK, CREATE_NEW, SKIP

	}

	@Schema(description = "Counts of the checked rows; present from VALIDATED on. The last four are absent in history lists and after the purge")
	public record RowCounts(@NotNull int total, @NotNull int valid, @NotNull int invalid,
			@Schema(description = "Possible duplicates still waiting for a decision") @NotNull int duplicates, Integer skipped,
			Integer toCreate, Integer toUpdate,
			@Schema(description = "Valid rows that would change nothing") Integer unchanged) {
	}

	@Schema(description = "What the commit did; present from COMMITTING on")
	public record CommitResult(@NotNull int created, @NotNull int updated, @NotNull int unchanged, @NotNull int skipped) {
	}

	public record Progress(@NotNull int done, @NotNull int total) {
	}

	@Schema(description = "Why a job is FAILED: the phase (VALIDATE or COMMIT), the first failing row if any, and a localized message")
	public record Failure(@NotNull String phase, Integer rowNumber, @NotNull String code, @NotNull String message) {
	}

	public record ImportJobView(@NotNull UUID id, @NotNull ImportType importType, @NotNull Status status,
			@NotNull String filename, @NotNull UUID mediaId, UUID mappingProfileId,
			@Schema(description = "Field key to zero-based column index") @NotNull Map<String, Integer> columnMapping,
			@NotNull ImportOptions options, @NotNull Progress progress, RowCounts rows, CommitResult result, Failure failure,
			@NotNull UUID initiatedBy, @NotNull Instant createdAt, Instant startedAt, Instant completedAt,
			@Schema(description = "True once the raw rows and the uploaded file were purged (retention)") @NotNull boolean rowsPurged) {
	}

	public record Detected(@NotNull String format, String encoding, String delimiter, @NotNull List<String> sheetNames,
			String sheet, @NotNull boolean date1904, @NotNull int formulaCells, @NotNull int mergedRegions) {
	}

	public record FieldView(@NotNull String key, @NotNull String label, @NotNull boolean required,
			@Schema(description = "At least one field of the same group must be mapped") String oneOf) {
	}

	public record PreviewRow(@NotNull int rowNumber, @NotNull List<String> cells,
			@Schema(description = "A row starting with # is an example or comment: it is ignored") @NotNull boolean example) {
	}

	public record AppliedProfile(@NotNull UUID id, @NotNull String name) {
	}

	@Schema(description = "The first lines of the file with what was detected and the suggested column mapping")
	public record ImportPreview(@NotNull ImportJobView job, @NotNull Detected detected, @NotNull List<String> headers,
			@NotNull List<PreviewRow> previewRows,
			@Schema(description = "The job's current mapping: the suggestion until the user saves one") @NotNull Map<String, Integer> suggestedMapping,
			@Schema(description = "What must be mapped: required fields, and 'a|b' where one of a, b is enough") @NotNull List<String> requiredFields,
			@Schema(description = "Required fields still missing from the mapping") @NotNull List<String> missingFields,
			@NotNull List<FieldView> fields, AppliedProfile appliedProfile) {
	}

	public record ImportTypeView(@NotNull ImportType type, @NotNull String label, @NotNull List<FieldView> fields,
			@NotNull List<String> requiredFields) {
	}

	public record ProblemView(String column, @NotNull String code, @NotNull String message) {
	}

	public record ImportRowView(@NotNull UUID id, @NotNull int rowNumber, @NotNull RowStatus status, Action plannedAction,
			@Schema(description = "LINK, CREATE_NEW or SKIP once a possible duplicate was decided") String resolution,
			@Schema(description = "CREATED, UPDATED, UNCHANGED or SKIPPED after the commit") String applyResult,
			@Schema(description = "Original cells in column order; absent after the purge") List<String> cells,
			@Schema(description = "What the commit applies, as understood from the cells; absent after the purge") Map<String, Object> normalized,
			@NotNull List<ProblemView> errors, @NotNull List<ProblemView> warnings,
			@Schema(description = "Existing students that may be the same person (possible duplicates)") @NotNull List<Map<String, Object>> duplicateCandidates,
			UUID entityId) {
	}

}
