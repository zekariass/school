package com.nemaz.school.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.messaging.application.MessagingMaintenance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;

/**
 * Rule book R11.3: the text of a message exists in the messages table and in nothing else: not in the audit log, the stored
 * events, the notifications, the pushes, the realtime payloads or the application log.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class NoTextLeakTest extends MessagingTestSupport {

	@Autowired
	MessagingMaintenance maintenance;

	@Test
	void messageTextIsNeverInAuditEventsNotificationsOrLogs(CapturedOutput output) throws Exception {
		Msg m = school("leak");
		String g1Token = device(m.g1());
		String studentToken = device(m.student1());
		UUID thread = teacherStudentThread(m);
		UUID parentThread = parentTeacherThread(m);
		UUID classThread = classConversation(m);
		UUID parents = parentGroup(m);
		List<UUID> sent = new java.util.ArrayList<>();
		// every kind of write, each with its own canary
		sent.add(send(m.lead(), thread, "CANARY-DIRECT teacher to student"));
		UUID edited = send(m.student1(), thread, "CANARY-EDIT-BEFORE");
		call(HttpMethod.PATCH, conv(thread) + "/messages/" + edited, m.student1(), body("body", "CANARY-EDIT-AFTER"), 200);
		UUID deleted = send(m.student1(), thread, "CANARY-DELETED");
		call(HttpMethod.DELETE, conv(thread) + "/messages/" + deleted, m.student1(), null, 204);
		UUID reported = send(m.g2(), parents, "CANARY-REPORTED in the parent group");
		call(HttpMethod.POST, conv(parents) + "/messages/" + reported + "/report", m.lead(), body("reason", "OTHER", "note", "CANARY-NOTE"), 204);
		UUID hidden = send(m.student2(), classThread, "CANARY-HIDDEN");
		call(HttpMethod.POST, conv(classThread) + "/messages/" + hidden + "/hide", m.lead(), body("reason", "off topic"), 204);
		UUID reacted = send(m.g1(), parentThread, "CANARY-REACTED");
		call(HttpMethod.PUT, conv(parentThread) + "/messages/" + reacted + "/reaction", m.lead(), body("emoji", "👍"), 200);
		post(conv(parentThread) + "/messages", m.lead(), body("clientMessageId", UUID.randomUUID(), "body", "CANARY-SCHEDULED",
				"sendAt", this.clock.instant().plus(Duration.ofMinutes(5))), 201);
		this.clock.advance(Duration.ofMinutes(6));
		this.maintenance.sendScheduled();
		this.clock.reset();
		UUID blockedMember = memberIdOf(m.student1(), thread, m.lead());
		post("/api/v1/me/blocks", m.student1(), body("conversationId", thread, "memberId", blockedMember), 201);
		post(m.api() + "/review/conversations/" + thread + "/read", m.admin(), body("reason", "routine check of the observed thread"), 403);
		sent.add(edited);
		sent.add(deleted);
		// wait until the asynchronous deliveries are through
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(this.jdbc.queryForObject(
				"select count(*) from messages where notified_at is null and seq is not null and conversation_id in (?, ?, ?, ?)", Integer.class,
				thread, parentThread, classThread, parents)).isZero());
		// the audit log
		assertThat(this.jdbc.queryForObject("""
				select count(*) from audit_logs where metadata::text like '%CANARY%' or changes::text like '%CANARY%'
				  or target_type::text like '%CANARY%'""", Integer.class)).isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action like 'messaging.%'", Integer.class)).isGreaterThan(8);
		// the stored events
		assertThat(this.jdbc.queryForObject("select count(*) from event_publication where serialized_event like '%CANARY%'", Integer.class)).isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from event_publication where serialized_event like '%MessageSent%' or serialized_event like '%messageId%'",
				Integer.class)).isGreaterThan(0);
		// notifications and pushes
		assertThat(this.jdbc.queryForObject("""
				select count(*) from notifications where title like '%CANARY%' or body like '%CANARY%' or data::text like '%CANARY%'""", Integer.class))
			.isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from notifications where type_code like '%MESSAGE%'", Integer.class)).isGreaterThan(3);
		for (var push : this.pushes.sent()) {
			assertThat(push.toString()).doesNotContain("CANARY");
		}
		assertThat(this.pushes.sentTo(g1Token).size() + this.pushes.sentTo(studentToken).size()).isGreaterThan(0);
		// translations cache, reports table keep the note out of everything but the report row; the log has nothing
		assertThat(output.getAll()).doesNotContain("CANARY");
		assertThat(sent).isNotEmpty();
	}

	@Test
	void requestObjectsAreRedactedInLogs() {
		var request = new com.nemaz.school.messaging.api.MessageController.SendMessageRequest(UUID.randomUUID(), null, "CANARY-TOSTRING", null,
				null, null, null);
		assertThat(request.toString()).doesNotContain("CANARY");
		assertThat(new com.nemaz.school.messaging.api.MessageController.EditMessageRequest("CANARY-EDIT").toString()).doesNotContain("CANARY");
		assertThat(new com.nemaz.school.messaging.api.MessageController.ReportMessageRequest("OTHER", "CANARY-NOTE").toString())
			.doesNotContain("CANARY");
		assertThat(new com.nemaz.school.messaging.application.MessageService.SendCommand(UUID.randomUUID(), null, "CANARY", null, null, null, null)
			.toString()).doesNotContain("CANARY");
	}

}
