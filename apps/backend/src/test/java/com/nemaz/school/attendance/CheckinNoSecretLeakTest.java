package com.nemaz.school.attendance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.attendance.application.CheckinModels.CredentialSheet;
import com.nemaz.school.attendance.application.CheckinModels.CredentialSheetItem;
import com.nemaz.school.attendance.application.CheckinModels.IssuedAttendanceCredential;
import com.nemaz.school.attendance.application.CheckinModels.QrCheckinRequest;
import com.nemaz.school.attendance.application.CheckinModels.QrCode;
import com.nemaz.school.attendance.application.CheckinModels.QrCodes;
import com.nemaz.school.attendance.application.CheckinModels.StaffScanRequest;
import com.nemaz.school.attendance.application.CheckinModels.StaticScanRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;

import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tokens, rotating codes, the pepper and the server key exist where they must and nowhere else: not in the audit log, the stored
 * events, the notifications, the pushes, the sync ledger, the check-in ledger, the session rows, the credential rows (which hold
 * a keyed hash) or the application log; and the request and answer objects redact themselves.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class CheckinNoSecretLeakTest extends QrTestSupport {

	@org.springframework.beans.factory.annotation.Autowired
	com.nemaz.school.support.RecordingPushSender pushes;

	@Test
	void secretsAreNeverInAuditEventsNotificationsTheSyncLedgerOrLogs(CapturedOutput output) throws Exception {
		Setup s = setup("Leak school");
		methods(s, ALL_METHODS.toArray(String[]::new));
		setting(s.schoolId(), "attendance.staff.enabled", true);
		atNoon();
		List<String> secrets = new ArrayList<>();
		String forged = "FORGEDFORGEDFORGEDFORGE";
		secrets.add(forged);

		// every kind of credential and code, every outcome
		String t1 = issue(s, s.s1());
		String t2 = issue(s, s.s2());
		String t3 = issue(s, s.s3());
		secrets.addAll(List.of(t1, t2, t3));
		JsonNode sheet = call(HttpMethod.POST, s.api() + "/classes/" + s.classId() + "/attendance-credentials", s.admin(),
				map("replaceExisting", true), 200);
		for (JsonNode item : sheet.get("items")) {
			secrets.add(item.get("qrPayload").asString());
		}
		String revoked = sheet.get("items").get(0).get("qrPayload").asString();
		call(HttpMethod.POST, s.api() + "/students/" + s.s1() + "/attendance-credentials/revoke", s.admin(), map("reason", "LOST"), 200);
		String live = sheet.get("items").get(1).get("qrPayload").asString();

		scan(s, s.lead(), live, 200);
		scan(s, s.lead(), revoked, 409);
		scan(s, s.lead(), "AAAAAAAAAAAAAAAAAAAAAA", 409);
		scan(s, s.subject(), live, 409);
		Map<String, Object> syncOp = new LinkedHashMap<>();
		syncOp.put("deviceId", newId());
		syncOp.put("clientOperationId", newId());
		syncOp.put("operationType", "ATTENDANCE_CHECKIN");
		syncOp.put("entityType", "ATTENDANCE_CHECKIN");
		syncOp.put("payload", map("schoolId", s.schoolId(), "classId", s.classId(), "date", today().toString(), "token", revoked));
		syncOp.put("clientCreatedAt", this.clock.instant().toString());
		call(HttpMethod.POST, "/api/v1/sync", s.lead(), map("operations", List.of(syncOp)), 200);

		UUID id = openCode(s);
		JsonNode codes = get(qrBase(s) + "/" + id + "/codes", s.lead());
		for (JsonNode code : codes.get("codes")) {
			secrets.add(code.get("code").asString());
		}
		checkIn(s, id, s.student1(), currentCode(id), 200);
		checkIn(s, id, newClassStudent(s, "Dawit"), forged, 409);
		UUID staffSession = uuid(call(HttpMethod.POST, s.api() + "/staff-attendance/qr-sessions", s.admin(), map(), 201), "id");
		for (JsonNode code : get(qrBase(s) + "/" + staffSession + "/codes", s.admin()).get("codes")) {
			secrets.add(code.get("code").asString());
		}
		checkIn(s, staffSession, s.lead(), currentCode(staffSession), 200);
		String staffCard = call(HttpMethod.POST, s.api() + "/staff/" + this.jdbc.queryForObject("select id from school_staff where user_id = ?", UUID.class, s.subjectId())
				+ "/attendance-credentials", s.admin(), null, 201).get("qrPayload").asString();
		secrets.add(staffCard);
		call(HttpMethod.POST, s.api() + "/staff-attendance/scans", s.admin(), map("token", staffCard), 200);
		secrets.removeIf((secret) -> secret.length() < 20);
		assertThat(secrets).hasSizeGreaterThan(40);

		// let the asynchronous listeners (notifications) run
		Thread.sleep(2500);

		for (String secret : secrets) {
			String like = "%" + secret + "%";
			assertThat(count("select count(*) from audit_logs where cast(metadata as text) like ? or cast(changes as text) like ? or cast(target_type as text) like ?",
					like, like, like)).as("audit " + mask(secret)).isZero();
			assertThat(count("select count(*) from event_publication where serialized_event like ?", like)).as("events").isZero();
			assertThat(count("select count(*) from notifications where title like ? or body like ? or cast(data as text) like ?", like, like, like))
				.as("notifications").isZero();
			assertThat(count("select count(*) from sync_operations where cast(payload as text) like ? or cast(result as text) like ?", like, like))
				.as("sync ledger " + mask(secret)).isZero();
			assertThat(count("select count(*) from attendance_checkins c where cast(c as text) like ?", like)).as("check-in ledger").isZero();
			assertThat(count("select count(*) from attendance_qr_sessions q where cast(q as text) like ?", like)).as("sessions").isZero();
			assertThat(count("select count(*) from attendance_credentials c where cast(c as text) like ?", like)).as("credentials hold a hash only").isZero();
			assertThat(count("select count(*) from attendance_records r where cast(r as text) like ?", like)).as("records").isZero();
			assertThat(count("select count(*) from staff_attendance_records r where cast(r as text) like ?", like)).as("staff records").isZero();
			assertThat(output.getAll()).as("log " + mask(secret)).doesNotContain(secret);
		}
		for (var push : this.pushes.sent()) {
			for (String secret : secrets) {
				assertThat(push.toString()).doesNotContain(secret);
			}
		}
		// the server's keys are not in any answer or any log either
		for (String key : List.of(QR_KEY, PEPPER_T1, PEPPER_T0)) {
			assertThat(output.getAll()).doesNotContain(key);
			assertThat(count("select count(*) from audit_logs where cast(metadata as text) like ?", "%" + key + "%")).isZero();
		}
	}

	private static String mask(String secret) {
		return secret.substring(0, 3) + "...";
	}

	@Test
	void requestsAndAnswersRedactThemselves() {
		String secret = "SECRETSECRETSECRETSECRE";
		assertThat(new StaticScanRequest(secret, null, null, null, null, null, null).toString()).doesNotContain(secret);
		assertThat(new StaffScanRequest(secret, null).toString()).doesNotContain(secret);
		assertThat(new QrCheckinRequest(secret).toString()).doesNotContain(secret);
		assertThat(new QrCode(java.time.Instant.now(), java.time.Instant.now(), secret, "id." + secret).toString()).doesNotContain(secret);
		assertThat(new QrCodes(UUID.randomUUID(), java.time.Instant.now(), 30, List.of(new QrCode(java.time.Instant.now(), java.time.Instant.now(), secret, secret)))
			.toString()).doesNotContain(secret);
		assertThat(new IssuedAttendanceCredential(null, secret).toString()).doesNotContain(secret);
		CredentialSheetItem item = new CredentialSheetItem(UUID.randomUUID(), "A", UUID.randomUUID(), secret, java.time.Instant.now(), java.time.Instant.now());
		assertThat(item.toString()).doesNotContain(secret);
		assertThat(new CredentialSheet(List.of(item), List.of()).toString()).doesNotContain(secret);
	}

}
