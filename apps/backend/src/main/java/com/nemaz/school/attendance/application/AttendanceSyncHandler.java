package com.nemaz.school.attendance.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.attendance.application.AttendanceModels.AttendanceMarkInput;
import com.nemaz.school.attendance.application.AttendanceModels.AttendanceRollRequest;
import com.nemaz.school.attendance.application.AttendanceModels.AttendanceWriteResult;
import com.nemaz.school.attendance.application.AttendanceModels.WriteContext;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.sync.SyncHandler;
import com.nemaz.school.sync.SyncOperation;
import com.nemaz.school.sync.SyncOutcome;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

/**
 * {@code ATTENDANCE_MARK}: a roll (or part of one) taken offline, applied exactly like {@code PUT
 * /schools/{school}/classes/{class}/attendance} by {@link RollService}, as the user, at the time of the sync (so a
 * teacher removed from the class, an archived class, a student who left or a roll that locked meanwhile are REJECTED with
 * the same codes as online: {@code class.access_denied}, {@code class.archived}, {@code attendance.student_not_in_class},
 * {@code attendance.session_locked}). The operation's {@code baseVersion} is the roll's {@code sessionVersion} the device
 * edited from. Per-record conflicts make the whole operation CONFLICT (the marks that did not conflict were written);
 * the state lists, per student, APPLIED, UNCHANGED or CONFLICT with the server's current record and the submitted mark.
 * <p>
 * Payload: {@code {schoolId, classId, date, slot?, subjectId?, groupId?, markAllPresent?, overrideReason?, records: [{studentId,
 * status, minutesLate?, note?, expectedVersion?}]}}.
 */
@Component
class AttendanceSyncHandler implements SyncHandler {

	static final String TYPE = "ATTENDANCE_MARK";

	private final RollService rolls;

	private final JsonMapper json;

	AttendanceSyncHandler(RollService rolls, JsonMapper json) {
		this.rolls = rolls;
		this.json = json;
	}

	@Override
	public String operationType() {
		return TYPE;
	}

	@Override
	public Set<String> entityTypes() {
		return Set.of("ATTENDANCE_SESSION");
	}

	@Override
	public SyncOutcome apply(SyncOperation op, CurrentUser user) {
		Payload payload = parse(op.payload());
		AttendanceRollRequest request = new AttendanceRollRequest(payload.date(), payload.slot(), payload.subjectId(),
				payload.groupId(),
				payload.markAllPresent(), op.baseVersion(), payload.overrideReason(), payload.records());
		WriteContext context = new WriteContext("OFFLINE_SYNC", op.deviceId(), op.clientOperationId(), op.recordedAt());
		AttendanceWriteResult result = this.rolls.write(user, payload.schoolId(), payload.classId(), request, context);
		JsonNode state = this.json.valueToTree(result);
		return (result.conflicts() > 0)
				? SyncOutcome.conflict(payload.schoolId(), result.sessionId(), result.sessionVersion(), state)
				: SyncOutcome.applied(payload.schoolId(), result.sessionId(), result.sessionVersion(), state);
	}

	private Payload parse(JsonNode node) {
		Payload payload;
		try {
			payload = this.json.treeToValue(node, Payload.class);
		}
		catch (RuntimeException ex) {
			throw ApiException.unprocessable("sync.invalid_payload");
		}
		if (payload == null || payload.schoolId() == null || payload.classId() == null || payload.date() == null) {
			throw ApiException.unprocessable("sync.invalid_payload");
		}
		if (payload.records() != null
				&& payload.records().stream().anyMatch((r) -> r == null || r.studentId() == null || r.status() == null)) {
			throw ApiException.unprocessable("sync.invalid_payload");
		}
		return payload;
	}

	record Payload(UUID schoolId, UUID classId, LocalDate date, String slot, UUID subjectId, UUID groupId,
			Boolean markAllPresent, String overrideReason, List<AttendanceMarkInput> records) {
	}

}
