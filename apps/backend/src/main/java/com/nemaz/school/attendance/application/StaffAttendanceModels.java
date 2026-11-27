package com.nemaz.school.attendance.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.attendance.StaffAttendanceStatus;
import com.nemaz.school.common.pagination.CursorPage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request and response shapes of the staff attendance API (Implementation Plan M7d, Database_Design §4.34). Schema
 * names are prefixed {@code Staff} so that they stay unique in the OpenAPI document. There is no leave, pay or other HR
 * data here (D14).
 */
public final class StaffAttendanceModels {

	private StaffAttendanceModels() {
	}

	// --- requests ---------------------------------------------------------------------------

	@Schema(description = "Mark staff for one school-local day. records: the staff to mark; markRestPresent additionally marks every other ACTIVE staff member of the day who has no record PRESENT. A staff member who already has a record for the day is left as it is (EXISTS): changing it is a correction with a reason")
	public record StaffDayRequest(
			@Size(max = 500, message = "validation.tooLong") @Valid List<@NotNull @Valid StaffMarkInput> records,
			Boolean markRestPresent) {

		@Override
		public String toString() {
			return "StaffDayRequest[redacted]";
		}

	}

	public record StaffMarkInput(@NotNull(message = "validation.required") UUID staffId,
			@NotNull(message = "validation.required") StaffAttendanceStatus status,
			@Schema(description = "Minutes late, 1 to 600, only with LATE") Integer minutesLate,
			@Size(max = 200, message = "validation.tooLong") String note) {

		@Override
		public String toString() {
			return "StaffMarkInput[redacted]";
		}

	}

	@Schema(description = "Correct a staff record: the new status (and minutes late, only with LATE), optionally a new note (omit to keep it, send an empty text to clear it) and the reason, which is required and kept in the append-only corrections. expectedVersion is the record's version you read")
	public record StaffCorrectionRequest(@NotNull(message = "validation.required") StaffAttendanceStatus status,
			Integer minutesLate, @Size(max = 200, message = "validation.tooLong") String note,
			@NotBlank(message = "validation.required") @Size(max = 200, message = "validation.tooLong") String reason,
			@NotNull(message = "validation.required") Integer expectedVersion) {

		@Override
		public String toString() {
			return "StaffCorrectionRequest[redacted]";
		}

	}

	// --- views ------------------------------------------------------------------------------

	public record StaffRecordView(@NotNull UUID id, @NotNull UUID staffId, @NotNull String staffName, @NotNull LocalDate date,
			@NotNull StaffAttendanceStatus status, Integer minutesLate, String note,
			@Schema(description = "MANUAL; the QR methods arrive with M7e") @NotNull String method, UUID campusId, int version,
			@NotNull UUID recordedBy, @NotNull Instant recordedAt,
			@Schema(description = "How many corrections the record has") int corrections) {
	}

	public record StaffCorrectionView(@NotNull UUID id, @NotNull StaffAttendanceStatus oldStatus,
			@NotNull StaffAttendanceStatus newStatus, Integer oldMinutesLate, Integer newMinutesLate, @NotNull String reason,
			@NotNull UUID correctedBy, @NotNull Instant correctedAt) {
	}

	public record StaffRecordDetail(@NotNull StaffRecordView record, @NotNull List<StaffCorrectionView> corrections) {
	}

	@Schema(description = "A staff member on the day's roll, with the day's record when there is one")
	public record StaffDayEntry(@NotNull UUID staffId, @NotNull String displayName, @NotNull String staffType, String jobTitle,
			UUID campusId, StaffRecordView record) {
	}

	@Schema(description = "The staff roll of a school-local day: ACTIVE staff (and anybody who already has a record that day), keyset paged by name")
	public record StaffDayRoll(@NotNull LocalDate date, @NotNull String timeZone, @NotNull CursorPage<StaffDayEntry> staff) {
	}

	@Schema(description = "CREATED: the record was written. EXISTS: the staff member already had a record for the day, which stays as it is")
	public record StaffMarkOutcome(@NotNull UUID staffId, @NotNull String outcome, @NotNull StaffRecordView record) {
	}

	public record StaffDayResult(@NotNull LocalDate date, int created, int existing, @NotNull List<StaffMarkOutcome> outcomes) {
	}

	// --- reports ----------------------------------------------------------------------------

	public record StaffDailySummaryRow(@NotNull LocalDate date, int marked, int present, int absent, int late, int halfDay,
			int excused, long minutesLate) {
	}

	@Schema(description = "One row per school-local day that has records, oldest first")
	public record StaffDailyReport(@NotNull LocalDate from, @NotNull LocalDate to, @NotNull List<StaffDailySummaryRow> rows) {
	}

	@Schema(description = "A staff member's days in a month, counted per status")
	public record StaffMonthlySummaryRow(@NotNull UUID staffId, @NotNull String displayName, @NotNull String staffType,
			String jobTitle, int marked, int present, int absent, int late, int halfDay, int excused, long minutesLate) {
	}

	public record StaffMonthlyReport(@NotNull String month, @NotNull CursorPage<StaffMonthlySummaryRow> staff) {
	}

}
