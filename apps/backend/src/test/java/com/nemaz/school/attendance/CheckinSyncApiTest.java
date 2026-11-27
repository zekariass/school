package com.nemaz.school.attendance;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ATTENDANCE_CHECKIN} on the sync endpoint (M7e): a card scanned offline applies once, a replay is DUPLICATE, a stored
 * mark that differs is CONFLICT, a refusal is REJECTED with its code and stays in the ledger, each operation has its own
 * transaction, and the token never reaches the sync ledger.
 */
@IntegrationTest
class CheckinSyncApiTest extends QrTestSupport {

	private static final String SYNC = "/api/v1/sync";

	private Setup syncSetup(String label) {
		Setup s = setup(label);
		methods(s, ALL_METHODS.toArray(String[]::new));
		return s;
	}

	private Map<String, Object> op(UUID device, Setup s, String token, LocalDate date, Object... extra) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("schoolId", s.schoolId());
		payload.put("classId", s.classId());
		payload.put("date", date.toString());
		payload.put("token", token);
		for (int i = 0; i < extra.length; i += 2) {
			payload.put((String) extra[i], extra[i + 1]);
		}
		Map<String, Object> op = new LinkedHashMap<>();
		op.put("deviceId", device);
		op.put("clientOperationId", newId());
		op.put("operationType", "ATTENDANCE_CHECKIN");
		op.put("entityType", "ATTENDANCE_CHECKIN");
		op.put("payload", payload);
		op.put("clientCreatedAt", this.clock.instant().toString());
		return op;
	}

	@SafeVarargs
	private JsonNode sync(Persona as, Map<String, Object>... operations) throws Exception {
		return call(HttpMethod.POST, SYNC, as, Map.of("operations", new ArrayList<>(List.of(operations))), 200).get("results");
	}

	@Test
	void aCardScannedOfflineAppliesOnceAndTheSecondSendIsADuplicate() throws Exception {
		Setup s = syncSetup("Sync scan school");
		String token = issue(s, s.s1());
		UUID device = newId();
		Map<String, Object> op = op(device, s, token, today());
		JsonNode first = sync(s.lead(), op).get(0);
		assertThat(first.get("status").asString()).isEqualTo("APPLIED");
		assertThat(first.get("state").get("result").asString()).isEqualTo("ACCEPTED");
		assertThat(first.get("state").get("person").get("firstName").asString()).isEqualTo("Abel");
		assertThat(recordStatus(s.classId(), s.s1())).isEqualTo("PRESENT");
		assertThat(this.jdbc.queryForObject("select source from attendance_records where student_id = ?", String.class, s.s1())).isEqualTo("QR_STATIC");
		assertThat(this.jdbc.queryForObject("select client_op_id from attendance_checkins where student_id = ?", UUID.class, s.s1()))
			.isEqualTo(UUID.fromString(op.get("clientOperationId").toString()));

		// the same operation again (the device did not get the answer): the first outcome, nothing written twice
		JsonNode second = sync(s.lead(), op).get(0);
		assertThat(second.get("status").asString()).isEqualTo("DUPLICATE");
		assertThat(second.get("originalStatus").asString()).isEqualTo("APPLIED");
		assertThat(ledgerResults(s.schoolId())).containsExactly("ACCEPTED");
		assertThat(countRecords(s.classId())).isEqualTo(1);
		// the sync ledger never kept the token, neither in the payload nor in the result
		String stored = this.jdbc.queryForObject("select cast(payload as text) || cast(result as text) from sync_operations where client_operation_id = ?",
				String.class, UUID.fromString(op.get("clientOperationId").toString()));
		assertThat(stored).doesNotContain(token).contains(s.classId().toString());
		assertThat(this.jdbc.queryForObject("select cast(payload as text) from sync_operations where client_operation_id = ?", String.class,
				UUID.fromString(op.get("clientOperationId").toString()))).doesNotContain("token");
	}

	@Test
	void anotherScanOfTheSameStudentIsADuplicateAndADifferentStoredMarkIsAConflict() throws Exception {
		Setup s = syncSetup("Sync dup school");
		take(s, s.lead(), rollBody(today(), false, mark(s.s3(), "ABSENT", null, "note", null)), 200);
		String t1 = issue(s, s.s1());
		String t3 = issue(s, s.s3());
		UUID device = newId();
		sync(s.lead(), op(device, s, t1, today()));
		JsonNode again = sync(s.subject(), op(newId(), s, t1, today())).get(0);
		assertThat(again.get("status").asString()).isEqualTo("DUPLICATE");
		assertThat(again.get("state").get("result").asString()).isEqualTo("REJECTED_DUPLICATE");
		JsonNode conflict = sync(s.lead(), op(device, s, t3, today())).get(0);
		assertThat(conflict.get("status").asString()).isEqualTo("CONFLICT");
		assertThat(conflict.get("state").get("current").get("status").asString()).isEqualTo("ABSENT");
		assertThat(recordStatus(s.classId(), s.s3())).isEqualTo("ABSENT");
		assertThat(ledgerResults(s.schoolId())).containsExactly("ACCEPTED", "REJECTED_DUPLICATE", "REJECTED_CONFLICT");
	}

	@Test
	void aRefusedScanIsRejectedWithItsCodeAndTheRefusalStaysInTheLedger() throws Exception {
		Setup s = syncSetup("Sync refuse school");
		String revoked = issue(s, s.s1());
		call(HttpMethod.POST, s.api() + "/students/" + s.s1() + "/attendance-credentials/revoke", s.admin(), map("reason", "LOST"), 200);
		Map<String, Object> revokedOp = op(newId(), s, revoked, today());
		JsonNode r = sync(s.lead(), revokedOp).get(0);
		assertThat(r.get("status").asString()).isEqualTo("REJECTED");
		assertThat(r.get("problem").get("code").asString()).isEqualTo("attendance.credential_revoked");
		assertThat(r.get("retryable").asBoolean()).isFalse();
		assertThat(r.get("problem").get("detail").asString()).isNotBlank();
		// not rolled back: the ledger has the refusal and the scanner, the audit has the denied scan, the sync ledger has no token
		assertThat(ledgerResults(s.schoolId())).containsExactly("REJECTED_REVOKED");
		assertThat(this.jdbc.queryForObject("select scanner_user_id from attendance_checkins where student_id = ?", UUID.class, s.s1())).isEqualTo(s.leadId());
		assertThat(count("select count(*) from audit_logs where action = 'attendance.scan.refused' and school_id = ?", s.schoolId())).isEqualTo(1);
		UUID opId = UUID.fromString(revokedOp.get("clientOperationId").toString());
		assertThat(this.jdbc.queryForObject("select status from sync_operations where client_operation_id = ?", String.class, opId)).isEqualTo("REJECTED");
		assertThat(this.jdbc.queryForObject("select cast(payload as text) from sync_operations where client_operation_id = ?", String.class, opId))
			.doesNotContain(revoked);
		// sending it again is a duplicate of the rejection, not a second log row
		JsonNode again = sync(s.lead(), revokedOp).get(0);
		assertThat(again.get("status").asString()).isEqualTo("DUPLICATE");
		assertThat(again.get("originalStatus").asString()).isEqualTo("REJECTED");
		assertThat(ledgerResults(s.schoolId())).hasSize(1);

		// an unknown card, a student not in the class, a roll outside its window, a method that is off, a teacher without access
		JsonNode unknown = sync(s.lead(), op(newId(), s, "AAAAAAAAAAAAAAAAAAAAAA", today())).get(0);
		assertThat(unknown.get("problem").get("code").asString()).isEqualTo("attendance.credential_invalid");
		UUID outsider = this.personas.newStudent(s.schoolId(), "Fikru");
		this.personas.addStudentToClass(s.schoolId(), s.otherClassId(), outsider);
		JsonNode notIn = sync(s.lead(), op(newId(), s, issue(s, outsider), today())).get(0);
		assertThat(notIn.get("status").asString()).isEqualTo("REJECTED");
		assertThat(notIn.get("problem").get("code").asString()).isEqualTo("attendance.student_not_in_class");
		JsonNode old = sync(s.lead(), op(newId(), s, issue(s, s.s2()), today().minusDays(5))).get(0);
		assertThat(old.get("problem").get("code").asString()).isEqualTo("attendance.checkin_window_closed");
		JsonNode noAccess = sync(s.otherTeacher(), op(newId(), s, issue(s, s.s3()), today())).get(0);
		assertThat(noAccess.get("status").asString()).isEqualTo("REJECTED");
		assertThat(noAccess.get("problem").get("code").asString()).isEqualTo("class.not_found");
		methods(s, "MANUAL");
		JsonNode off = sync(s.lead(), op(newId(), s, "BBBBBBBBBBBBBBBBBBBBBB", today())).get(0);
		assertThat(off.get("problem").get("code").asString()).isEqualTo("attendance.method_disabled");
		assertThat(ledgerResults(s.schoolId())).containsExactly("REJECTED_REVOKED", "REJECTED_NOT_ENROLLED", "REJECTED_WINDOW");
		assertThat(countRecords(s.classId())).isZero();
	}

	@Test
	void everyOperationOfABatchHasItsOwnTransaction() throws Exception {
		Setup s = syncSetup("Sync batch school");
		UUID device = newId();
		JsonNode results = sync(s.lead(), op(device, s, issue(s, s.s1()), today()), op(device, s, "AAAAAAAAAAAAAAAAAAAAAA", today()),
				op(device, s, issue(s, s.s2()), today()));
		assertThat(results.get(0).get("status").asString()).isEqualTo("APPLIED");
		assertThat(results.get(1).get("status").asString()).isEqualTo("REJECTED");
		assertThat(results.get(2).get("status").asString()).isEqualTo("APPLIED");
		assertThat(recordStatus(s.classId(), s.s1())).isEqualTo("PRESENT");
		assertThat(recordStatus(s.classId(), s.s2())).isEqualTo("PRESENT");
		// a malformed payload is rejected without touching the others
		Map<String, Object> broken = op(device, s, "x", today());
		((Map<String, Object>) broken.get("payload")).remove("token");
		JsonNode mixed = sync(s.lead(), broken, op(device, s, issue(s, s.s3()), today()));
		assertThat(mixed.get(0).get("problem").get("code").asString()).isEqualTo("sync.invalid_payload");
		assertThat(mixed.get(1).get("status").asString()).isEqualTo("APPLIED");
	}

	@Test
	void anOperationTheOnlineEndpointAlreadyAppliedIsADuplicate() throws Exception {
		Setup s = syncSetup("Sync online school");
		String token = issue(s, s.s1());
		Map<String, Object> op = op(newId(), s, token, today());
		UUID clientOp = UUID.fromString(op.get("clientOperationId").toString());
		scan(s, s.lead(), map("token", token, "clientOpId", clientOp), 200);
		JsonNode result = sync(s.lead(), op).get(0);
		assertThat(result.get("status").asString()).isEqualTo("DUPLICATE");
		assertThat(result.get("state").get("result").asString()).isEqualTo("ACCEPTED");
		assertThat(ledgerResults(s.schoolId())).containsExactly("ACCEPTED");
	}

}
