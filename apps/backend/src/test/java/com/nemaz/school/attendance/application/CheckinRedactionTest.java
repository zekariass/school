package com.nemaz.school.attendance.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.nemaz.school.attendance.application.CheckinService.Scan;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/** The internal carriers of a token redact themselves, and the sync handler strips the token from what the ledger keeps. */
class CheckinRedactionTest {

	@Test
	void theInternalScanAndThePayloadDoNotPrintTheToken() {
		String secret = "SECRETSECRETSECRETSECRE";
		Scan scan = new Scan(UUID.randomUUID(), UUID.randomUUID(), secret, LocalDate.now(), null, null, null, null, null, null, Instant.now());
		assertThat(scan.toString()).doesNotContain(secret);
		assertThat(new AttendanceCheckinSyncHandler.Payload(UUID.randomUUID(), UUID.randomUUID(), LocalDate.now(), null, null, secret, null, null)
			.toString()).doesNotContain(secret);
	}

	@Test
	void theSyncHandlerKeepsTheTokenOutOfTheLedgerPayload() {
		JsonMapper json = JsonMapper.builder().build();
		AttendanceCheckinSyncHandler handler = new AttendanceCheckinSyncHandler(null, null, json);
		ObjectNode payload = json.createObjectNode();
		payload.put("schoolId", UUID.randomUUID().toString());
		payload.put("token", "SECRETSECRETSECRETSECRE");
		payload.put("date", "2026-11-27");
		ObjectNode stored = (ObjectNode) handler.storedPayload(payload);
		assertThat(stored.has("token")).isFalse();
		assertThat(stored.get("date").asString()).isEqualTo("2026-11-27");
		// the operation as received is untouched
		assertThat(payload.has("token")).isTrue();
	}

}
