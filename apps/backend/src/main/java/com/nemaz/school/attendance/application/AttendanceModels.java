package com.nemaz.school.attendance.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.attendance.AttendanceStatus;
import com.nemaz.school.media.MediaSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The request and response shapes of the roll-call API (also the payload and state of the ATTENDANCE_MARK sync
 * operation). Schema names are prefixed {@code Attendance} so that they stay unique in the OpenAPI document.
 */
public final class AttendanceModels {

	private AttendanceModels() {
	}

	// --- requests ---------------------------------------------------------------------------

	@Schema(description = "Take or correct a roll. records: the marks to write (students left out are not touched: unmarked stay unmarked). markAllPresent: additionally marks every student of the class on that date who has no record yet and is not in records (PRESENT, or EXCUSED when an acknowledged absence report covers the date); existing records are never touched by it")
	public record AttendanceRollRequest(
			@NotNull(message = "validation.required") @Schema(description = "School-local calendar date (the school's or campus's time zone), not in the future beyond a small tolerance") LocalDate date,
			@Size(max = 3, message = "validation.invalid") @Schema(description = "DAY (default), AM, PM or P1..P8; must be one the school enabled (attendance.slots)") String slot,
			@Schema(description = "Subject of a period roll (P1..P8 only; one of the class's subjects). Set when the roll is created; a later request naming another subject is refused (409 attendance.subject_mismatch)") UUID subjectId,
			@Schema(description = "Restricts this request to the members of a saved student group of the class (an elective's group): only they may be marked and markAllPresent covers only them. Not stored with the roll") UUID groupId,
			@Schema(description = "Mark everyone without a record present (see the description above)") Boolean markAllPresent,
			@Schema(description = "The roll's version you read (the sessionVersion): your marks for students whose record has not changed since are applied even without a per-record expectedVersion") Integer baseVersion,
			@Size(max = 200, message = "validation.tooLong") @Schema(description = "Required to edit a locked roll (lead teacher, class manager or attendance manager); audited") String overrideReason,
			@Size(max = 500, message = "validation.tooLong") @Valid List<@NotNull @Valid AttendanceMarkInput> records) {

		@Override
		public String toString() {
			return "AttendanceRollRequest[redacted]";
		}

	}

	public record AttendanceMarkInput(@NotNull(message = "validation.required") UUID studentId,
			@NotNull(message = "validation.required") AttendanceStatus status,
			@Schema(description = "Minutes late, 1 to 600, only with LATE") Integer minutesLate,
			@Size(max = 200, message = "validation.tooLong") @Schema(description = "A short note about this mark (visible to staff, never to the student or guardians)") String note,
			@Schema(description = "The version of the student's record you edited from. Required to change an existing record that holds something else; omit for a student who has no record yet") Integer expectedVersion) {

		@Override
		public String toString() {
			return "AttendanceMarkInput[redacted]";
		}

	}

	/** How and by whom a write is made: the online API or an offline device. */
	public record WriteContext(String source, UUID deviceId, UUID clientOperationId, Instant recordedAt) {

		public static WriteContext online(Instant now) {
			return new WriteContext("ONLINE", null, null, now);
		}

	}

	// --- views ------------------------------------------------------------------------------

	public record AttendanceRecordView(@NotNull UUID id, @NotNull AttendanceStatus status, Integer minutesLate,
			@Schema(description = "Staff only: absent for students and guardians") String note, int version,
			@NotNull UUID recordedBy, @NotNull Instant recordedAt, @NotNull String source, UUID absenceReportId) {
	}

	@Schema(description = "An absence report that covers the date. status SUBMITTED (waiting for a teacher) or ACKNOWLEDGED. reasonCode and note only for the student's lead, co and subject teachers and attendance managers")
	public record AbsenceAnnotationView(@NotNull UUID reportId, @NotNull String status, String reasonCode,
			@NotNull LocalDate fromDate, @NotNull LocalDate toDate, String note) {
	}

	public record AttendanceRollStudent(@NotNull UUID studentId, @NotNull String displayName, MediaSummary avatar,
			Map<String, Object> avatarConfig, AttendanceRecordView record,
			@Schema(description = "EXCUSED when an acknowledged absence report covers the date and there is no record yet; the client may pre-select it") AttendanceStatus suggestedStatus,
			AbsenceAnnotationView absence) {
	}

	@Schema(description = "The roll of a class for a date and slot: every student who belonged to the class that day (or of the requested group), with the record (if any) and the absence-report annotation")
	public record AttendanceRollView(@NotNull UUID classId, @NotNull LocalDate date, @NotNull String slot,
			@NotNull List<String> slots, UUID subjectId, String subjectName,
			@Schema(description = "The group the roster was restricted to, if one was asked for") UUID groupId, UUID sessionId,
			@Schema(description = "The roll's version: send it as baseVersion; null while nobody has taken the roll") Integer sessionVersion,
			UUID takenBy, Instant takenAt,
			@Schema(description = "True once the lock window has passed: only the lead teacher, class managers and attendance managers may edit, with a reason") boolean locked,
			@NotNull Instant locksAt, boolean mayEditLocked, @NotNull String timeZone,
			@NotNull List<AttendanceRollStudent> students, @NotNull AttendanceCounts counts) {
	}

	public record AttendanceCounts(int students, int marked, int unmarked, int present, int absent, int late, int excused,
			int leftEarly) {

		static AttendanceCounts of(int students, Map<AttendanceStatus, Integer> byStatus) {
			int marked = byStatus.values().stream().mapToInt(Integer::intValue).sum();
			return new AttendanceCounts(students, marked, Math.max(0, students - marked),
					byStatus.getOrDefault(AttendanceStatus.PRESENT, 0), byStatus.getOrDefault(AttendanceStatus.ABSENT, 0),
					byStatus.getOrDefault(AttendanceStatus.LATE, 0), byStatus.getOrDefault(AttendanceStatus.EXCUSED, 0),
					byStatus.getOrDefault(AttendanceStatus.LEFT_EARLY, 0));
		}

	}

	@Schema(description = "What happened to one submitted mark: APPLIED (written, record has the new version), UNCHANGED (the record already held exactly this) or CONFLICT (the record changed since you read it: nothing was overwritten, record is what is stored now and submitted is what you sent)")
	public record AttendanceRecordOutcome(@NotNull UUID studentId, @NotNull String outcome,
			@NotNull AttendanceRecordView record, AttendanceSubmittedMark submitted) {
	}

	public record AttendanceSubmittedMark(@NotNull AttendanceStatus status, Integer minutesLate, String note,
			Integer expectedVersion) {
	}

	@Schema(description = "The result of taking a roll. conflicts > 0 means some marks were not written (the others were); the online endpoint then answers 409 with this same body")
	public record AttendanceWriteResult(@NotNull UUID sessionId, @NotNull UUID classId, @NotNull LocalDate date,
			@NotNull String slot, int sessionVersion, @Schema(description = "True when a locked roll was edited with an override reason") boolean overridden,
			int applied, int unchanged, int conflicts, @NotNull List<AttendanceRecordOutcome> outcomes) {
	}

}
