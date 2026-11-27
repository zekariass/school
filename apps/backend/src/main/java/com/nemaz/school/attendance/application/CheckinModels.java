package com.nemaz.school.attendance.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.attendance.AttendanceStatus;
import com.nemaz.school.media.MediaSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The request and response shapes of the QR check-in API (M7e): credentials (cards), rotating QR sessions, and the answer
 * of a scan. Schema names are prefixed {@code Attendance} (or {@code Qr}, {@code Checkin}) so that they stay unique in
 * the OpenAPI document.
 * <p>
 * <b>No location.</b> Nothing here carries, and no endpoint accepts or stores, a latitude, a longitude, an address or a
 * network identity of the device (decision D38). Unknown JSON members are ignored by the mapper and never read.
 * <p>
 * <b>Secrets.</b> A QR payload (the bare token of a credential) exists in {@link IssuedAttendanceCredential} and
 * {@link CredentialSheetItem} only, once, at issue; both redact {@code toString}. {@link QrCode#code} is the code of a
 * rotating window and is returned only to the people who may run the session.
 */
public final class CheckinModels {

	private CheckinModels() {
	}

	// --- credentials ------------------------------------------------------------------------

	/** Why a credential was revoked (stored as text; the database checks the list). */
	public enum CredentialReason {

		LOST, REPLACED, LEFT_SCHOOL, SUSPECTED_COPY, OTHER

	}

	@Schema(description = "Issue a credential (a card QR) for one person. ttlDays: days it stays valid (1 to 1095, default 365)")
	public record IssueCredentialRequest(@Schema(description = "Days the credential stays valid, 1 to 1095 (default 365)") Integer ttlDays) {
	}

	@Schema(description = "Replace the person's live credential: it is revoked with the reason (default REPLACED, never LEFT_SCHOOL) and a new one is issued in the same transaction")
	public record ReissueCredentialRequest(
			@Schema(description = "LOST, REPLACED (default), SUSPECTED_COPY or OTHER") CredentialReason reason,
			@Schema(description = "Days the new credential stays valid, 1 to 1095 (default 365)") Integer ttlDays) {
	}

	@Schema(description = "Revoke the person's live credential: its next scan is refused and logged")
	public record RevokeCredentialRequest(@NotNull(message = "validation.required") CredentialReason reason) {
	}

	@Schema(description = "The state of a credential: LIVE (not revoked, not expired), EXPIRED or REVOKED. Never the QR itself")
	public record AttendanceCredentialView(@NotNull UUID id, @NotNull @Schema(description = "STUDENT or STAFF") String subjectKind,
			@Schema(description = "The student's user id (STUDENT)") UUID studentId,
			@Schema(description = "The school_staff id (STAFF)") UUID staffId, @NotNull String displayName,
			@NotNull Instant issuedAt, @NotNull UUID issuedBy, @NotNull Instant expiresAt, Instant revokedAt,
			String revokedReason, Instant lastUsedAt,
			@Schema(description = "Set when two scanners read this credential within a minute: a sign that a copy is in use. The school decides (revoke it with SUSPECTED_COPY); a scan is not refused because of it") Instant suspectedCopyAt,
			@NotNull @Schema(description = "LIVE, EXPIRED or REVOKED") String state) {
	}

	@Schema(description = "A new credential. qrPayload is the bare token to print as a QR code: it is shown ONCE, here, and can never be read again (only its keyed hash is stored)")
	public record IssuedAttendanceCredential(@NotNull AttendanceCredentialView credential, @NotNull String qrPayload) {

		@Override
		public String toString() {
			return "IssuedAttendanceCredential[redacted]";
		}

	}

	@Schema(description = "Credentials for a class sheet: students of the class (studentIds narrows it) who have no live credential get one; with replaceExisting the others get a new one too (the old is revoked as REPLACED)")
	public record CredentialSheetRequest(@Size(max = 500, message = "validation.tooLong") List<@NotNull UUID> studentIds,
			Boolean replaceExisting, @Schema(description = "Days the credentials stay valid, 1 to 1095 (default 365)") Integer ttlDays) {
	}

	@Schema(description = "One printable card of a sheet: first name for the label, the QR payload (shown once)")
	public record CredentialSheetItem(@NotNull UUID studentId, @NotNull String displayName, @NotNull UUID credentialId,
			@NotNull String qrPayload, @NotNull Instant issuedAt, @NotNull Instant expiresAt) {

		@Override
		public String toString() {
			return "CredentialSheetItem[redacted]";
		}

	}

	public record CredentialSheetSkip(@NotNull UUID studentId, @NotNull @Schema(description = "ALREADY_ISSUED: the student has a live credential and replaceExisting was not set") String reason) {
	}

	public record CredentialSheet(@NotNull List<CredentialSheetItem> items, @NotNull List<CredentialSheetSkip> skipped) {

		@Override
		public String toString() {
			return "CredentialSheet[redacted]";
		}

	}

	// --- rotating sessions ------------------------------------------------------------------

	@Schema(description = "Open a rotating code for the roll of today (school-local date) and a slot. Opening one closes the class's other open codes for that roll")
	public record OpenClassQrSessionRequest(
			@Size(max = 3, message = "validation.invalid") @Schema(description = "DAY (default), AM, PM or P1..P8; one the school enabled") String slot,
			@Schema(description = "Period rolls only (P1..P8): the class's subject the roll is created with, like the roll of the online endpoint") UUID subjectId,
			@Schema(description = "Minutes the code can be used, 1 to 120 (default 15)") Integer durationMinutes,
			@Schema(description = "A check-in more than this many minutes after the code was opened is LATE (0 to 600); omit for nobody late") Integer lateAfterMinutes) {
	}

	@Schema(description = "Open a rotating code for the staff of a campus (or the whole school) for today (school-local date)")
	public record OpenStaffQrSessionRequest(
			@Schema(description = "The campus whose staff may scan; omit for the whole school") UUID campusId,
			@Schema(description = "Minutes the code can be used, 1 to 480 (default 60)") Integer durationMinutes,
			@Schema(description = "A check-in more than this many minutes after the code was opened is LATE (0 to 600); default the school's attendance.staff.late_after_minutes (0: nobody late)") Integer lateAfterMinutes) {
	}

	@Schema(description = "A rotating code session. open is false once it was closed or its time ran out")
	public record QrSessionView(@NotNull UUID id, @NotNull String subjectKind, UUID classId, @NotNull LocalDate date, String slot,
			UUID campusId, @NotNull UUID openedBy, @NotNull Instant opensAt, @NotNull Instant closesAt, int rotationSeconds,
			Integer lateAfterMinutes, @NotNull @Schema(description = "OPEN or CLOSED") String status, boolean open,
			@Schema(description = "Accepted check-ins that were not removed") int acceptedCount, @NotNull String timeZone) {
	}

	@Schema(description = "One accepted check-in of a session. removed: a teacher took it off the roll")
	public record QrSessionEntry(@NotNull UUID checkinId, @NotNull @Schema(description = "The student's user id, or the school_staff id of a staff member") UUID personId,
			@NotNull String displayName, MediaSummary avatar, @NotNull String status, Integer minutesLate,
			@NotNull Instant checkedInAt, boolean removed) {
	}

	@Schema(description = "A session with its live list, oldest first")
	public record QrSessionDetail(@NotNull QrSessionView session, @NotNull List<QrSessionEntry> entries) {
	}

	@Schema(description = "The code of one window. payload is what the QR shows: the session id, a dot and the code")
	public record QrCode(@NotNull Instant startsAt, @NotNull Instant endsAt, @NotNull String code, @NotNull String payload) {

		@Override
		public String toString() {
			return "QrCode[redacted]";
		}

	}

	@Schema(description = "The codes of the current window and the windows ahead (about ten minutes), so the display keeps rotating without a connection. A code is accepted during its own window and the next one")
	public record QrCodes(@NotNull UUID sessionId, @NotNull Instant serverTime, int rotationSeconds, @NotNull List<QrCode> codes) {

		@Override
		public String toString() {
			return "QrCodes[redacted]";
		}

	}

	// --- check-ins --------------------------------------------------------------------------

	@Schema(description = "A student's (or staff member's) own check-in: the code from the QR on the teacher's (manager's) screen, the part after the dot of the payload")
	public record QrCheckinRequest(@NotNull(message = "validation.required") @Size(min = 8, max = 64, message = "validation.invalid") String code) {

		@Override
		public String toString() {
			return "QrCheckinRequest[redacted]";
		}

	}

	@Schema(description = "A teacher scans a student's card for a class roll. status: PRESENT (default) or LATE; nothing else (a scan never marks absent). clientOpId: a UUID the app keeps per scan, so a retry or an offline replay applies once")
	public record StaticScanRequest(
			@NotNull(message = "validation.required") @Size(min = 8, max = 64, message = "validation.invalid") @Schema(description = "The bare token read from the card's QR") String token,
			@Schema(description = "School-local date of the roll (default today)") LocalDate date,
			@Size(max = 3, message = "validation.invalid") @Schema(description = "DAY (default), AM, PM or P1..P8") String slot,
			@Schema(description = "Period rolls only (P1..P8): the class's subject, used when the scan has to create the roll") UUID subjectId,
			AttendanceStatus status, @Schema(description = "Minutes late, 1 to 600, only with LATE") Integer minutesLate,
			UUID clientOpId) {

		@Override
		public String toString() {
			return "StaticScanRequest[redacted]";
		}

	}

	@Schema(description = "A manager scans a staff member's card for today")
	public record StaffScanRequest(
			@NotNull(message = "validation.required") @Size(min = 8, max = 64, message = "validation.invalid") String token,
			UUID clientOpId) {

		@Override
		public String toString() {
			return "StaffScanRequest[redacted]";
		}

	}

	/** The result of a check-in attempt. {@code REJECTED_INVALID} is answered but never stored (there is nobody to name). */
	public enum CheckinResultCode {

		ACCEPTED, REJECTED_EXPIRED, REJECTED_NOT_ENROLLED, REJECTED_DUPLICATE, REJECTED_REVOKED, REJECTED_WINDOW,
		REJECTED_CONFLICT, REJECTED_INVALID

	}

	@Schema(description = "Whom a card belongs to, as far as a scanner may know: first name and avatar, nothing else. personId only when the person is on the class roll for the date")
	public record ScannedPerson(UUID personId, @NotNull String firstName, MediaSummary avatar, Map<String, Object> avatarConfig) {
	}

	@Schema(description = "The answer of a check-in. 200 with result ACCEPTED; 409 with the same body for every refusal that was logged (and for REJECTED_INVALID, which is not). code and detail are the stable error code and its localized sentence. person: first name and avatar of a scanned card (never for a student's own check-in). current: the roll's stored mark when the scan was refused as a duplicate or a conflict (scans by teachers only)")
	public record CheckinResult(@NotNull CheckinResultCode result, AttendanceStatus status, Integer minutesLate, UUID checkinId,
			ScannedPerson person, AttendanceModels.AttendanceRecordView current, String code, String detail) {
	}

	@Schema(description = "How attendance is taken at this school: the QR methods that are on (manual marking is always available) and the rotation of the code")
	public record CheckinMethods(@NotNull List<String> methods, int rotationSeconds, boolean staffAttendanceEnabled,
			int staffLateAfterMinutes) {
	}

}
