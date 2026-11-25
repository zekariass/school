package com.nemaz.school.sync;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.attendance.AttendanceTestSupport;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.support.Persona;
import com.nemaz.school.sync.application.SyncRetention;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The sync endpoint: one result per operation in order, replays, conflicts between devices, queues that went stale,
 * limits and retention.
 */
@IntegrationTest
class SyncApiTest extends AttendanceTestSupport {

	private static final String SYNC = "/api/v1/sync";

	@Autowired
	SyncRetention retention;

	// --- building operations ----------------------------------------------------------------

	private static Map<String, Object> op(UUID device, String type, String entity, Integer baseVersion, Object payload,
			Instant createdAt) {
		Map<String, Object> op = new LinkedHashMap<>();
		op.put("deviceId", device);
		op.put("clientOperationId", Ids.newId());
		op.put("operationType", type);
		op.put("entityType", entity);
		if (baseVersion != null) {
			op.put("baseVersion", baseVersion);
		}
		op.put("payload", payload);
		op.put("clientCreatedAt", createdAt.toString());
		return op;
	}

	private static Map<String, Object> rollPayload(Setup s, UUID classId, LocalDate date, Map<String, Object>... marks) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("schoolId", s.schoolId());
		payload.put("classId", classId);
		payload.put("date", date.toString());
		payload.put("records", new ArrayList<>(List.of(marks)));
		return payload;
	}

	@SafeVarargs
	private Map<String, Object> attendanceOp(UUID device, Setup s, LocalDate date, Map<String, Object>... marks) {
		return op(device, "ATTENDANCE_MARK", "ATTENDANCE_SESSION", null, rollPayload(s, s.classId(), date, marks), Instant.now());
	}

	private Map<String, Object> awardOp(UUID device, Setup s, UUID categoryId, List<UUID> students) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("schoolId", s.schoolId());
		payload.put("classId", s.classId());
		payload.put("categoryId", categoryId);
		payload.put("studentIds", students);
		return op(device, "POINT_AWARD", "POINT_TRANSACTION", null, payload, Instant.now());
	}

	private JsonNode sync(Persona as, List<Map<String, Object>> operations) throws Exception {
		return call(HttpMethod.POST, SYNC, as, Map.of("operations", operations), 200).get("results");
	}

	private static String status(JsonNode result) {
		return result.get("status").asString();
	}

	private UUID kindness(Setup s) throws Exception {
		get(s.api() + "/point-categories", s.admin());
		return this.jdbc.queryForObject("select id from point_categories where school_id = ? and template_code = 'kindness'",
				UUID.class, s.schoolId());
	}

	private int ledger(UUID classId) {
		return this.jdbc.queryForObject("select count(*) from point_transactions where class_id = ?", Integer.class, classId);
	}

	// --- the protocol -----------------------------------------------------------------------

	@Test
	void aMixedBatchGetsOneResultPerOperationInOrderAndNothingBlocksTheNext() throws Exception {
		Setup s = setup("Sync mix school");
		UUID device = Ids.newId();
		UUID kindness = kindness(s);
		take(s, s.lead(), rollBody(today(), false, mark(s.s1(), "ABSENT")), 200);

		List<Map<String, Object>> batch = new ArrayList<>();
		batch.add(attendanceOp(device, s, today(), mark(s.s2(), "PRESENT")));
		batch.add(awardOp(device, s, kindness, List.of(s.s1())));
		batch.add(op(device, "ATTENDANCE_MARK", "ATTENDANCE_SESSION", null,
				rollPayload(s, s.otherClassId(), today(), mark(s.s1(), "PRESENT")), Instant.now()));
		batch.add(attendanceOp(device, s, today(), mark(s.s1(), "PRESENT")));
		batch.add(op(device, "TELEPORT", "THING", null, Map.of("x", 1), Instant.now()));
		batch.add(attendanceOp(device, s, today(), mark(s.s3(), "LATE")));

		JsonNode results = sync(s.lead(), batch);
		assertThat(results).hasSize(6);
		assertThat(results).extracting(SyncApiTest::status)
			.containsExactly("APPLIED", "APPLIED", "REJECTED", "CONFLICT", "REJECTED", "APPLIED");
		for (int i = 0; i < 6; i++) {
			assertThat(results.get(i).get("clientOperationId").asString()).isEqualTo(batch.get(i).get("clientOperationId").toString());
		}
		// APPLIED: the server state after, with versions
		assertThat(outcome(results.get(0).get("state"), s.s2()).get("record").get("status").asString()).isEqualTo("PRESENT");
		assertThat(uuid(results.get(0), "entityId")).isNotNull();
		assertThat(results.get(1).get("state").get("transactions")).hasSize(1);
		// REJECTED: a stable code and a localized detail
		assertThat(results.get(2).get("problem").get("code").asString()).isEqualTo("class.not_found");
		assertThat(results.get(2).get("problem").get("status").asInt()).isEqualTo(404);
		assertThat(results.get(2).get("problem").get("detail").asString()).isNotBlank();
		assertThat(results.get(4).get("problem").get("code").asString()).isEqualTo("sync.unknown_operation");
		// CONFLICT: what is there now and what was sent
		JsonNode conflict = outcome(results.get(3).get("state"), s.s1());
		assertThat(conflict.get("outcome").asString()).isEqualTo("CONFLICT");
		assertThat(conflict.get("record").get("status").asString()).isEqualTo("ABSENT");
		assertThat(conflict.get("submitted").get("status").asString()).isEqualTo("PRESENT");
		// the operation after the failures still ran
		assertThat(this.jdbc.queryForObject("select status from attendance_records where student_id = ?", String.class, s.s3()))
			.isEqualTo("LATE");
		assertThat(this.jdbc.queryForObject("select source from attendance_records where student_id = ?", String.class, s.s3()))
			.isEqualTo("OFFLINE_SYNC");
		assertThat(ledger(s.classId())).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select count(*) from sync_operations where user_id = ?", Integer.class, s.leadId()))
			.isEqualTo(6);

		// the whole batch again: every result is a DUPLICATE with the first answer, and nothing is written twice
		JsonNode replay = sync(s.lead(), batch);
		assertThat(replay).hasSize(6);
		for (int i = 0; i < 6; i++) {
			assertThat(status(replay.get(i))).isEqualTo("DUPLICATE");
			assertThat(replay.get(i).get("originalStatus").asString()).isEqualTo(status(results.get(i)));
			assertThat(replay.get(i).get("state")).isEqualTo(results.get(i).get("state"));
			assertThat(replay.get(i).get("entityId")).isEqualTo(results.get(i).get("entityId"));
		}
		assertThat(replay.get(2).get("problem").get("code").asString()).isEqualTo("class.not_found");
		assertThat(ledger(s.classId())).isEqualTo(1);
		assertThat(countRecords(s.classId())).isEqualTo(3);
		assertThat(this.jdbc.queryForObject("select count(*) from sync_operations where user_id = ?", Integer.class, s.leadId()))
			.isEqualTo(6);
		// and a third time, in another language
		JsonNode amharic = this.json.readTree(this.mvc
			.perform(MockMvcRequestBuilders.post(SYNC)
				.with(s.lead().bearer())
				.header("Accept-Language", "am")
				.contentType("application/json")
				.content(this.json.writeValueAsString(Map.of("operations", batch.subList(2, 3)))))
			.andReturn()
			.getResponse()
			.getContentAsString());
		assertThat(amharic.get("results").get(0).get("problem").get("detail").asString()).contains("ክፍል");
	}

	@Test
	void parallelReplaysOfTheSameOperationApplyOnce() throws Exception {
		Setup s = setup("Sync parallel school");
		UUID kindness = kindness(s);
		List<Map<String, Object>> batch = List.of(awardOp(Ids.newId(), s, kindness, List.of(s.s1(), s.s2())));
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<String>> futures = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				Callable<String> call = () -> {
					String body = perform(HttpMethod.POST, SYNC, s.lead(), Map.of("operations", batch)).andReturn()
						.getResponse()
						.getContentAsString();
					return status(this.json.readTree(body).get("results").get(0));
				};
				futures.add(pool.submit(call));
			}
			List<String> statuses = new ArrayList<>();
			for (Future<String> f : futures) {
				statuses.add(f.get());
			}
			assertThat(statuses.stream().filter("APPLIED"::equals)).hasSize(1);
			assertThat(statuses.stream().filter("DUPLICATE"::equals)).hasSize(7);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(ledger(s.classId())).isEqualTo(2);
		assertThat(this.jdbc.queryForObject("select count(*) from sync_operations where user_id = ?", Integer.class, s.leadId()))
			.isEqualTo(1);
	}

	@Test
	void onlineAndSyncNeverAwardTwiceForOneAction() throws Exception {
		Setup s = setup("Sync award school");
		UUID kindness = kindness(s);
		UUID device = Ids.newId();
		// the app queued it, sent it online too (the answer got lost), and later syncs the queue
		Map<String, Object> award = awardOp(device, s, kindness, List.of(s.s1()));
		UUID opId = UUID.fromString(award.get("clientOperationId").toString());
		call(HttpMethod.POST, s.api() + "/classes/" + s.classId() + "/points/awards", s.lead(),
				Map.of("categoryId", kindness, "studentIds", List.of(s.s1()), "clientOpId", opId), 201);
		JsonNode result = sync(s.lead(), List.of(award)).get(0);
		assertThat(status(result)).isEqualTo("DUPLICATE");
		assertThat(result.get("state").get("replayed").asBoolean()).isTrue();
		assertThat(ledger(s.classId())).isEqualTo(1);
		// the other way round
		Map<String, Object> second = awardOp(device, s, kindness, List.of(s.s2()));
		assertThat(status(sync(s.lead(), List.of(second)).get(0))).isEqualTo("APPLIED");
		JsonNode online = call(HttpMethod.POST, s.api() + "/classes/" + s.classId() + "/points/awards", s.lead(),
				Map.of("categoryId", kindness, "studentIds", List.of(s.s2()), "clientOpId", second.get("clientOperationId")), 200);
		assertThat(online.get("replayed").asBoolean()).isTrue();
		assertThat(ledger(s.classId())).isEqualTo(2);
	}

	// --- conflicts --------------------------------------------------------------------------

	@Test
	void twoDevicesMarkingTheSameStudentKeepTheFirstWriteAndTheLaterOneGetsAConflictWithBothSides() throws Exception {
		Setup s = setup("Sync conflict school");
		UUID deviceA = Ids.newId();
		UUID deviceB = Ids.newId();
		Instant early = Instant.now().minusSeconds(3600);
		Instant late = Instant.now().minusSeconds(60);
		// B marked first (earlier device time) but A syncs first
		Map<String, Object> fromB = op(deviceB, "ATTENDANCE_MARK", "ATTENDANCE_SESSION", null,
				rollPayload(s, s.classId(), today(), mark(s.s1(), "PRESENT")), early);
		Map<String, Object> fromA = op(deviceA, "ATTENDANCE_MARK", "ATTENDANCE_SESSION", null,
				rollPayload(s, s.classId(), today(), mark(s.s1(), "ABSENT", null, "called in", null)), late);
		assertThat(status(sync(s.lead(), List.of(fromA)).get(0))).isEqualTo("APPLIED");
		JsonNode b = sync(s.subject(), List.of(fromB)).get(0);
		assertThat(status(b)).isEqualTo("CONFLICT");
		JsonNode detail = outcome(b.get("state"), s.s1());
		assertThat(detail.get("record").get("status").asString()).isEqualTo("ABSENT");
		assertThat(detail.get("record").get("version").asInt()).isZero();
		assertThat(detail.get("submitted").get("status").asString()).isEqualTo("PRESENT");
		assertThat(this.jdbc.queryForObject("select status from attendance_records where student_id = ?", String.class, s.s1()))
			.isEqualTo("ABSENT");
		// the conflict is in the ledger, never discarded
		assertThat(this.jdbc.queryForObject("select status from sync_operations where client_operation_id = ?::uuid", String.class,
				fromB.get("clientOperationId").toString())).isEqualTo("CONFLICT");

		// a later write from another device does not win either, whatever its device time says
		Map<String, Object> laterC = op(Ids.newId(), "ATTENDANCE_MARK", "ATTENDANCE_SESSION", null,
				rollPayload(s, s.classId(), today(), mark(s.s1(), "LATE")), Instant.now());
		assertThat(status(sync(s.subject(), List.of(laterC)).get(0))).isEqualTo("CONFLICT");

		// the user resolves it: the same mark with the version they have now is applied
		Map<String, Object> resolved = op(deviceB, "ATTENDANCE_MARK", "ATTENDANCE_SESSION", null,
				rollPayload(s, s.classId(), today(), mark(s.s1(), "PRESENT", null, null, 0)), Instant.now());
		JsonNode result = sync(s.subject(), List.of(resolved)).get(0);
		assertThat(status(result)).isEqualTo("APPLIED");
		assertThat(outcome(result.get("state"), s.s1()).get("record").get("version").asInt()).isEqualTo(1);
		// two devices that agree do not clash
		Map<String, Object> agree = op(Ids.newId(), "ATTENDANCE_MARK", "ATTENDANCE_SESSION", null,
				rollPayload(s, s.classId(), today(), mark(s.s1(), "PRESENT")), Instant.now());
		JsonNode agreed = sync(s.lead(), List.of(agree)).get(0);
		assertThat(status(agreed)).isEqualTo("APPLIED");
		assertThat(outcome(agreed.get("state"), s.s1()).get("outcome").asString()).isEqualTo("UNCHANGED");
	}

	@Test
	void theBaseVersionOfTheRollMakesAnEditInformedWhileNobodyChangedItSince() throws Exception {
		Setup s = setup("Sync base version school");
		JsonNode taken = take(s, s.lead(), rollBody(today(), true), 200);
		int version = taken.get("sessionVersion").asInt();
		Map<String, Object> informed = op(Ids.newId(), "ATTENDANCE_MARK", "ATTENDANCE_SESSION", version,
				rollPayload(s, s.classId(), today(), mark(s.s1(), "ABSENT")), Instant.now());
		assertThat(status(sync(s.subject(), List.of(informed)).get(0))).isEqualTo("APPLIED");
		// the roll moved on: the same base version is stale now
		Map<String, Object> stale = op(Ids.newId(), "ATTENDANCE_MARK", "ATTENDANCE_SESSION", version,
				rollPayload(s, s.classId(), today(), mark(s.s1(), "LATE")), Instant.now());
		assertThat(status(sync(s.lead(), List.of(stale)).get(0))).isEqualTo("CONFLICT");
	}

	@Test
	void aDevicesOwnQueuedEditsOfTheSameRecordFollowEachOther() throws Exception {
		Setup s = setup("Sync queue school");
		UUID device = Ids.newId();
		Instant t0 = Instant.now().minusSeconds(600);
		Map<String, Object> first = op(device, "ATTENDANCE_MARK", "ATTENDANCE_SESSION", null,
				rollPayload(s, s.classId(), today(), mark(s.s1(), "ABSENT")), t0);
		Map<String, Object> second = op(device, "ATTENDANCE_MARK", "ATTENDANCE_SESSION", null,
				rollPayload(s, s.classId(), today(), mark(s.s1(), "LATE", 10, null, null)), t0.plusSeconds(60));
		// an edit made before the first one, queued out of order, is not a successor
		Map<String, Object> older = op(device, "ATTENDANCE_MARK", "ATTENDANCE_SESSION", null,
				rollPayload(s, s.classId(), today(), mark(s.s1(), "EXCUSED")), t0.minusSeconds(60));
		JsonNode results = sync(s.lead(), List.of(first, second, older));
		assertThat(results).extracting(SyncApiTest::status).containsExactly("APPLIED", "APPLIED", "CONFLICT");
		assertThat(this.jdbc.queryForObject("select status || ':' || version from attendance_records where student_id = ?",
				String.class, s.s1())).isEqualTo("LATE:1");
	}

	// --- stale queues -----------------------------------------------------------------------

	@Test
	void aQueueThatWentStaleIsRejectedWithTheCodesOfTheOnlineEndpoint() throws Exception {
		Setup s = setup("Sync stale school");
		UUID device = Ids.newId();
		UUID kindness = kindness(s);

		// the assistant was removed from the class while offline
		this.jdbc.update("update class_teachers set status = 'FORMER', left_at = now() where teacher_id = ?", s.assistantId());
		JsonNode removed = sync(s.assistant(), List.of(attendanceOp(device, s, today(), mark(s.s1(), "PRESENT")),
				awardOp(device, s, kindness, List.of(s.s1()))));
		assertThat(status(removed.get(0))).isEqualTo("REJECTED");
		assertThat(removed.get(0).get("problem").get("code").asString()).isEqualTo("class.not_found");
		assertThat(status(removed.get(1))).isEqualTo("REJECTED");
		assertThat(removed.get(1).get("problem").get("code").asString()).isEqualTo("class.not_found");

		// a student left the class (yesterday): a mark for today is refused, one for the day before was valid
		setting(s.schoolId(), "attendance.lock_after_hours", 720);
		this.jdbc.update("update class_students set status = 'FORMER', left_at = now() - interval '1 day', left_reason = 'TRANSFERRED_CLASS' where student_id = ?",
				s.s3());
		JsonNode left = sync(s.lead(), List.of(attendanceOp(device, s, today(), mark(s.s3(), "PRESENT")),
				attendanceOp(device, s, today().minusDays(3), mark(s.s3(), "PRESENT")), awardOp(device, s, kindness, List.of(s.s3()))));
		assertThat(status(left.get(0))).isEqualTo("REJECTED");
		assertThat(left.get(0).get("problem").get("code").asString()).isEqualTo("attendance.student_not_in_class");
		assertThat(status(left.get(1))).isEqualTo("APPLIED");
		assertThat(left.get(2).get("problem").get("code").asString()).isEqualTo("points.student_not_in_class");

		// the roll locked while the app was offline: only the lead (or an admin) with a reason may still write it
		setting(s.schoolId(), "attendance.lock_after_hours", 24);
		LocalDate old = today().minusDays(4);
		Map<String, Object> lateMark = attendanceOp(device, s, old, mark(s.s1(), "ABSENT"));
		Map<String, Object> overridden = op(device, "ATTENDANCE_MARK", "ATTENDANCE_SESSION", null, withReason(
				rollPayload(s, s.classId(), old, mark(s.s2(), "ABSENT")), "Register was on paper"), Instant.now());
		JsonNode lockedResults = sync(s.subject(), List.of(lateMark));
		assertThat(status(lockedResults.get(0))).isEqualTo("REJECTED");
		assertThat(lockedResults.get(0).get("problem").get("code").asString()).isEqualTo("attendance.session_locked");
		JsonNode byLead = sync(s.lead(), List.of(overridden));
		assertThat(status(byLead.get(0))).isEqualTo("APPLIED");
		assertThat(byLead.get(0).get("state").get("overridden").asBoolean()).isTrue();
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'attendance.roll.override' and school_id = ?",
				Integer.class, s.schoolId())).isEqualTo(1);
		// ... and without the reason it is a different rejection
		Map<String, Object> noReason = attendanceOp(device, s, old, mark(s.s3(), "ABSENT"));
		assertThat(sync(s.lead(), List.of(noReason)).get(0).get("problem").get("code").asString())
			.isEqualTo("attendance.override_reason_required");

		// the class was archived
		call(HttpMethod.POST, s.api() + "/classes/" + s.classId() + "/archive", s.admin(), null, 200);
		JsonNode archived = sync(s.lead(), List.of(attendanceOp(device, s, today(), mark(s.s1(), "PRESENT")),
				awardOp(device, s, kindness, List.of(s.s1()))));
		assertThat(archived.get(0).get("problem").get("code").asString()).isEqualTo("class.archived");
		assertThat(archived.get(1).get("problem").get("code").asString()).isEqualTo("class.archived");
	}

	private static Map<String, Object> withReason(Map<String, Object> payload, String reason) {
		payload.put("overrideReason", reason);
		return payload;
	}

	// --- time -------------------------------------------------------------------------------

	@Test
	void theDeviceTimeIsKeptButClampedAndTheServerClockDecides() throws Exception {
		Setup s = setup("Sync time school");
		UUID device = Ids.newId();
		Instant hoursAgo = Instant.now().minus(Duration.ofHours(3));
		Instant future = Instant.now().plus(Duration.ofDays(2));
		Instant ancient = Instant.now().minus(Duration.ofDays(40));
		Map<String, Object> normal = op(device, "ATTENDANCE_MARK", "ATTENDANCE_SESSION", null,
				rollPayload(s, s.classId(), today(), mark(s.s1(), "PRESENT")), hoursAgo);
		Map<String, Object> ahead = op(device, "ATTENDANCE_MARK", "ATTENDANCE_SESSION", null,
				rollPayload(s, s.classId(), today(), mark(s.s2(), "PRESENT")), future);
		Map<String, Object> tooOld = op(device, "ATTENDANCE_MARK", "ATTENDANCE_SESSION", null,
				rollPayload(s, s.classId(), today(), mark(s.s3(), "PRESENT")), ancient);
		Instant before = Instant.now();
		JsonNode results = sync(s.lead(), List.of(normal, ahead, tooOld));
		assertThat(status(results.get(0))).isEqualTo("APPLIED");
		assertThat(results.get(0).get("timeAdjusted").asBoolean()).isFalse();
		assertThat(status(results.get(1))).isEqualTo("APPLIED");
		assertThat(results.get(1).get("timeAdjusted").asBoolean()).isTrue();
		assertThat(status(results.get(2))).isEqualTo("REJECTED");
		assertThat(results.get(2).get("problem").get("code").asString()).isEqualTo("sync.operation_too_old");
		Instant recorded1 = this.jdbc.queryForObject("select recorded_at from attendance_records where student_id = ?",
				java.sql.Timestamp.class, s.s1()).toInstant();
		assertThat(recorded1).isBetween(hoursAgo.minusSeconds(1), hoursAgo.plusSeconds(1));
		Instant recorded2 = this.jdbc.queryForObject("select recorded_at from attendance_records where student_id = ?",
				java.sql.Timestamp.class, s.s2()).toInstant();
		assertThat(recorded2).isBetween(before.minusSeconds(5), Instant.now().plusSeconds(5));
		// the ledger keeps both the device time and the receipt time
		Map<String, Object> row = this.jdbc.queryForMap("select client_created_at, recorded_at, received_at from sync_operations where client_operation_id = ?::uuid",
				ahead.get("clientOperationId").toString());
		assertThat(((java.sql.Timestamp) row.get("client_created_at")).toInstant()).isBetween(future.minusSeconds(1), future.plusSeconds(1));
		assertThat(((java.sql.Timestamp) row.get("recorded_at")).toInstant()).isBefore(Instant.now().plusSeconds(5));
	}

	// --- shape and limits -------------------------------------------------------------------

	@Test
	void theBatchHasLimitsAndAnOperationMustKnowItsKind() throws Exception {
		Setup s = setup("Sync limits school");
		UUID device = Ids.newId();
		// a batch of 100 is fine, 101 is not
		List<Map<String, Object>> hundred = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			hundred.add(op(device, "NOPE", "NOPE", null, Map.of(), Instant.now()));
		}
		assertThat(sync(s.lead(), hundred)).hasSize(100);
		hundred.add(op(device, "NOPE", "NOPE", null, Map.of(), Instant.now()));
		assertThat(errorCode(call(HttpMethod.POST, SYNC, s.lead(), Map.of("operations", hundred), 413)))
			.isEqualTo("sync.too_many_operations");
		// a payload over 64 KiB is rejected on its own, the others still run
		Map<String, Object> huge = op(device, "ATTENDANCE_MARK", "ATTENDANCE_SESSION", null, Map.of("pad", "x".repeat(70_000)),
				Instant.now());
		JsonNode results = sync(s.lead(), List.of(huge, attendanceOp(device, s, today(), mark(s.s1(), "PRESENT"))));
		assertThat(results.get(0).get("problem").get("code").asString()).isEqualTo("sync.payload_too_large");
		assertThat(status(results.get(1))).isEqualTo("APPLIED");
		// the whole request is limited too
		List<Map<String, Object>> fat = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			fat.add(op(device, "NOPE", "NOPE", null, Map.of("pad", "x".repeat(60_000)), Instant.now()));
		}
		assertThat(errorCode(call(HttpMethod.POST, SYNC, s.lead(), Map.of("operations", fat), 413))).isEqualTo("sync.payload_too_large");
		// kinds and entities must match; payloads must parse
		assertThat(sync(s.lead(), List.of(op(device, "ATTENDANCE_MARK", "POINT_TRANSACTION", null, Map.of(), Instant.now())))
			.get(0).get("problem").get("code").asString()).isEqualTo("sync.entity_type_mismatch");
		assertThat(sync(s.lead(), List.of(op(device, "ATTENDANCE_MARK", "ATTENDANCE_SESSION", null, Map.of("classId", "nope"),
				Instant.now()))).get(0).get("problem").get("code").asString()).isEqualTo("sync.invalid_payload");
		assertThat(sync(s.lead(), List.of(op(device, "ATTENDANCE_MARK", "ATTENDANCE_SESSION", null,
				Map.of("schoolId", s.schoolId(), "classId", s.classId()), Instant.now()))).get(0).get("problem").get("code").asString())
			.isEqualTo("sync.invalid_payload");
		// a malformed request is a 400 for the whole request, an empty batch is fine
		call(HttpMethod.POST, SYNC, s.lead(), Map.of("operations", List.of(Map.of("operationType", "X"))), 400);
		call(HttpMethod.POST, SYNC, s.lead(), Map.of(), 400);
		assertThat(sync(s.lead(), List.of())).isEmpty();
		call(HttpMethod.POST, SYNC, null, Map.of("operations", List.of()), 401);
	}

	@Test
	void anOperationIdBelongsToTheUserAndTheDevice() throws Exception {
		Setup s = setup("Sync scope school");
		UUID device = Ids.newId();
		Map<String, Object> mine = attendanceOp(device, s, today(), mark(s.s1(), "PRESENT"));
		assertThat(status(sync(s.lead(), List.of(mine)).get(0))).isEqualTo("APPLIED");
		// another user with the same ids is a different operation (and an unknown one is no information leak)
		JsonNode other = sync(s.subject(), List.of(mine)).get(0);
		assertThat(status(other)).isEqualTo("APPLIED");
		assertThat(outcome(other.get("state"), s.s1()).get("outcome").asString()).isEqualTo("UNCHANGED");
		// the same user on another device likewise
		Map<String, Object> elsewhere = new LinkedHashMap<>(mine);
		elsewhere.put("deviceId", Ids.newId());
		assertThat(status(sync(s.lead(), List.of(elsewhere)).get(0))).isEqualTo("APPLIED");
		assertThat(this.jdbc.queryForObject("select count(*) from sync_operations where client_operation_id = ?::uuid", Integer.class,
				mine.get("clientOperationId").toString())).isEqualTo(3);
	}

	@Test
	void syncedMarksNotifyGuardiansLikeOnlineOnes() throws Exception {
		Setup s = setup("Sync notify school");
		sync(s.lead(), List.of(attendanceOp(Ids.newId(), s, today(), mark(s.s1(), "ABSENT"))));
		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(this.jdbc.queryForObject(
				"select count(*) from notifications where type_code = 'ATTENDANCE_MARKED' and user_id = ?", Integer.class,
				s.guardian1().userId())).isEqualTo(1));
	}

	@Test
	void theLedgerLosesChildrensDataAfterTheRetentionPeriodButStillRecognizesReplays() throws Exception {
		Setup s = setup("Sync retention school");
		UUID device = Ids.newId();
		Map<String, Object> mark = attendanceOp(device, s, today(), mark(s.s1(), "ABSENT", null, "kept private", null));
		JsonNode first = sync(s.lead(), List.of(mark)).get(0);
		assertThat(first.get("state")).isNotNull();
		assertThat(this.jdbc.queryForObject("select count(*) from sync_operations where user_id = ? and cast(payload as text) like '%kept private%'",
				Integer.class, s.leadId())).isEqualTo(1);
		// recent rows stay
		this.retention.purge();
		assertThat(this.jdbc.queryForObject("select count(*) from sync_operations where user_id = ? and payload is not null",
				Integer.class, s.leadId())).isEqualTo(1);
		// after 40 days the payload and the state go, the row stays
		this.jdbc.update("update sync_operations set received_at = now() - interval '40 days' where user_id = ?", s.leadId());
		assertThat(this.retention.purge()).isGreaterThanOrEqualTo(1);
		Map<String, Object> row = this.jdbc.queryForMap("select payload, payload_purged_at, status, cast(result as text) as result from sync_operations where user_id = ?",
				s.leadId());
		assertThat(row.get("payload")).isNull();
		assertThat(row.get("payload_purged_at")).isNotNull();
		assertThat(row.get("status")).isEqualTo("APPLIED");
		assertThat((String) row.get("result")).doesNotContain("kept private").doesNotContain("state");
		JsonNode replay = sync(s.lead(), List.of(mark)).get(0);
		assertThat(status(replay)).isEqualTo("DUPLICATE");
		assertThat(replay.get("originalStatus").asString()).isEqualTo("APPLIED");
		assertThat(replay.has("state") && !replay.get("state").isNull()).isFalse();
		assertThat(countRecords(s.classId())).isEqualTo(1);
		// after a year the row goes too
		this.jdbc.update("update sync_operations set received_at = now() - interval '400 days' where user_id = ?", s.leadId());
		this.retention.purge();
		assertThat(this.jdbc.queryForObject("select count(*) from sync_operations where user_id = ?", Integer.class, s.leadId()))
			.isZero();
	}

	@Test
	void theEndpointIsRateLimitedPerUser() throws Exception {
		Persona user = this.personas.newUser("sync-rate");
		int limited = 0;
		for (int i = 0; i < 125; i++) {
			int status = this.mvc.perform(MockMvcRequestBuilders.post(SYNC)
				.with(user.bearer())
				.contentType("application/json")
				.content("{\"operations\":[]}")).andReturn().getResponse().getStatus();
			if (status == 429) {
				limited++;
			}
			else {
				assertThat(status).isEqualTo(200);
			}
		}
		assertThat(limited).isBetween(1, 5);
	}

}
