package com.nemaz.school.attendance.application;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.attendance.AttendanceStatus;
import com.nemaz.school.attendance.application.CheckinModels.CheckinResult;
import com.nemaz.school.attendance.application.CheckinService.Scan;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.sync.SyncHandler;
import com.nemaz.school.sync.SyncOperation;
import com.nemaz.school.sync.SyncOutcome;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Component;

/**
 * {@code ATTENDANCE_CHECKIN}: a student's card scanned by a teacher while offline, applied exactly like {@code POST
 * /schools/{school}/classes/{class}/attendance/scans} by {@link CheckinService#scan}, as the user, at the time of the sync
 * (a teacher removed from the class, an archived class, a card revoked meanwhile, a roll that locked meanwhile are refused
 * with the same codes as online). The operation's {@code clientOperationId} is the check-in's {@code client_op_id}, so a scan
 * applies once: the second send of the operation is DUPLICATE (the sync ledger), and so is one the online endpoint already
 * took.
 * <p>
 * Outcomes: ACCEPTED is APPLIED; a stored mark that differs (REJECTED_CONFLICT: nothing was overwritten) is CONFLICT with the
 * result as its state; a stored mark that is the same (REJECTED_DUPLICATE) is DUPLICATE; everything else (revoked, unknown
 * or expired card, student not in the class, outside the window) is REJECTED with its error code, and the refusal stays in the
 * ledger of check-ins ({@link SyncOutcome#rejected} keeps what the handler wrote). The state holds the result, the mark and
 * the scanned student's first name and avatar (what the teacher saw), never the token: <b>the token is removed from the
 * payload the sync ledger keeps</b> ({@link #storedPayload}).
 * <p>
 * Payload: {@code {schoolId, classId, date, slot?, subjectId?, token, status?, minutesLate?}}; {@code date} is the school-local
 * date of the roll the scan was for (the roll's {@code timeZone} says which day that is).
 */
@Component
class AttendanceCheckinSyncHandler implements SyncHandler {

	static final String TYPE = "ATTENDANCE_CHECKIN";

	private final CheckinService checkins;

	private final CheckinLedger ledger;

	private final JsonMapper json;

	AttendanceCheckinSyncHandler(CheckinService checkins, CheckinLedger ledger, JsonMapper json) {
		this.checkins = checkins;
		this.ledger = ledger;
		this.json = json;
	}

	@Override
	public String operationType() {
		return TYPE;
	}

	@Override
	public Set<String> entityTypes() {
		return Set.of("ATTENDANCE_CHECKIN");
	}

	@Override
	public SyncOutcome apply(SyncOperation op, CurrentUser user) {
		Payload payload = parse(op.payload());
		boolean seenOnline = this.ledger.byClientOp(payload.schoolId(), op.clientOperationId()).isPresent();
		Scan scan = new Scan(payload.schoolId(), payload.classId(), payload.token(), payload.date(), payload.slot(), payload.subjectId(),
				payload.status(), payload.minutesLate(), op.clientOperationId(), op.clientCreatedAt(), op.recordedAt());
		CheckinResult result = this.checkins.scan(user, scan);
		JsonNode state = this.json.valueToTree(result);
		if (seenOnline) {
			return SyncOutcome.duplicate(payload.schoolId(), result.checkinId(), null, state);
		}
		return switch (result.result()) {
			case ACCEPTED -> SyncOutcome.applied(payload.schoolId(), result.checkinId(), null, state);
			case REJECTED_CONFLICT -> SyncOutcome.conflict(payload.schoolId(), result.checkinId(), null, state);
			case REJECTED_DUPLICATE -> SyncOutcome.duplicate(payload.schoolId(), result.checkinId(), null, state);
			default -> SyncOutcome.rejected(payload.schoolId(), result.code(), 409);
		};
	}

	/** The sync ledger keeps the payload for 30 days: without the token, which is a credential. */
	@Override
	public JsonNode storedPayload(JsonNode payload) {
		if (payload instanceof ObjectNode object) {
			ObjectNode copy = object.deepCopy();
			copy.remove("token");
			return copy;
		}
		return payload;
	}

	private Payload parse(JsonNode node) {
		Payload payload;
		try {
			payload = this.json.treeToValue(node, Payload.class);
		}
		catch (RuntimeException ex) {
			throw ApiException.unprocessable("sync.invalid_payload");
		}
		if (payload == null || payload.schoolId() == null || payload.classId() == null || payload.date() == null
				|| payload.token() == null || payload.token().isBlank() || payload.token().length() > 64) {
			throw ApiException.unprocessable("sync.invalid_payload");
		}
		return payload;
	}

	record Payload(UUID schoolId, UUID classId, LocalDate date, String slot, UUID subjectId, String token, AttendanceStatus status,
			Integer minutesLate) {

		@Override
		public String toString() {
			return "Payload[redacted]";
		}

	}

}
