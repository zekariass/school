package com.nemaz.school.engagement.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.engagement.application.AwardService.AwardCommand;
import com.nemaz.school.engagement.application.AwardService.AwardResult;
import com.nemaz.school.sync.SyncHandler;
import com.nemaz.school.sync.SyncOperation;
import com.nemaz.school.sync.SyncOutcome;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

/**
 * {@code POINT_AWARD}: an award queued offline, applied by {@link AwardService} exactly like the online endpoints (class
 * award when the payload names a {@code classId}, otherwise a school-wide award), as the user, when the operation is
 * synced: a teacher removed from the class or a student who left meanwhile gets the same refusal as online. The
 * operation's {@code clientOperationId} is the award's {@code clientOpId}, so the online and the sync path can never award
 * twice for one action: if the ledger already holds it (the online call got through before the app learned of it), the
 * result is DUPLICATE with the original rows. Points are stamped with the server's time at receipt (the ledger is
 * append-only and its time is not the device's).
 * <p>
 * Payload: {@code {schoolId, classId?, categoryId, studentIds?, wholeClass?, points?, note?}}.
 */
@Component
class PointAwardSyncHandler implements SyncHandler {

	static final String TYPE = "POINT_AWARD";

	private final AwardService awards;

	private final JsonMapper json;

	PointAwardSyncHandler(AwardService awards, JsonMapper json) {
		this.awards = awards;
		this.json = json;
	}

	@Override
	public String operationType() {
		return TYPE;
	}

	@Override
	public Set<String> entityTypes() {
		return Set.of("POINT_TRANSACTION");
	}

	@Override
	public SyncOutcome apply(SyncOperation op, CurrentUser user) {
		Payload payload = parse(op.payload());
		AwardCommand command = new AwardCommand(payload.categoryId(), payload.studentIds(), payload.wholeClass(),
				payload.points(), payload.note(), op.clientOperationId());
		AwardResult result = (payload.classId() != null)
				? this.awards.awardInClass(user, payload.schoolId(), payload.classId(), command)
				: this.awards.awardInSchool(user, payload.schoolId(), command);
		UUID entityId = (result.batchId() != null) ? result.batchId() : result.transactions().getFirst().id();
		JsonNode state = this.json.valueToTree(result);
		return result.replayed() ? SyncOutcome.duplicate(payload.schoolId(), entityId, null, state)
				: SyncOutcome.applied(payload.schoolId(), entityId, null, state);
	}

	private Payload parse(JsonNode node) {
		Payload payload;
		try {
			payload = this.json.treeToValue(node, Payload.class);
		}
		catch (RuntimeException ex) {
			throw ApiException.unprocessable("sync.invalid_payload");
		}
		if (payload == null || payload.schoolId() == null || payload.categoryId() == null) {
			throw ApiException.unprocessable("sync.invalid_payload");
		}
		return payload;
	}

	record Payload(UUID schoolId, UUID classId, UUID categoryId, List<UUID> studentIds, Boolean wholeClass, Integer points,
			String note) {
	}

}
