package com.nemaz.school.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.DomainEventRecorder;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Rule book R11: reporting and moderation hooks; nothing but ids leaves the module. */
@IntegrationTest
class ModerationAndReportTest extends MessagingTestSupport {

	@Autowired
	DomainEventRecorder recorder;

	@Test
	void anyMemberCanReportAndOnlyIdsLeaveTheModule() throws Exception {
		Msg m = school("mod-report");
		UUID classThread = classConversation(m);
		UUID message = send(m.student1(), classThread, "CANARY-REPORT mean words");
		String path = conv(classThread) + "/messages/" + message + "/report";
		call(HttpMethod.POST, path, m.student2(), body("reason", "BULLYING", "note", "CANARY-NOTE he said it again"), 204);
		call(HttpMethod.POST, path, m.student2(), body("reason", "BULLYING", "note", "CANARY-NOTE he said it again"), 204);
		assertThat(this.jdbc.queryForObject("select count(*) from message_reports where message_id = ?", Integer.class, message)).isEqualTo(1);
		// the audit row and the event carry ids and the reason code only
		var audit = this.jdbc.queryForList("select actor_user_id, metadata::text as metadata from audit_logs where action = 'messaging.message.report' and target_id = ?",
				message);
		assertThat(audit).hasSize(1);
		assertThat(audit.getFirst().get("actor_user_id")).isEqualTo(m.student2().userId());
		assertThat((String) audit.getFirst().get("metadata")).contains("BULLYING").contains(classThread.toString()).doesNotContain("CANARY");
		var events = this.recorder.of(com.nemaz.school.messaging.MessageReported.class).stream()
			.filter((e) -> e.messageId().equals(message))
			.toList();
		assertThat(events).hasSize(1);
		assertThat(events.getFirst().reporterId()).isEqualTo(m.s2());
		assertThat(events.getFirst().schoolId()).isEqualTo(m.schoolId());
		assertThat(events.getFirst().toString()).doesNotContain("CANARY");
		// the conversation's moderators and the school's reviewers are told, generically; the reporter is not
		awaitNotification(m.lead(), "MESSAGE_REPORTED");
		awaitNotification(m.co(), "MESSAGE_REPORTED");
		awaitNotification(m.admin(), "MESSAGE_REPORTED");
		assertThat(notificationsOf(m.student2(), "MESSAGE_REPORTED")).isEmpty();
		assertThat(notificationsOf(m.subject(), "MESSAGE_REPORTED")).isEmpty();
		for (var n : notificationsOf(m.lead(), "MESSAGE_REPORTED")) {
			assertThat(n.toString()).doesNotContain("CANARY").doesNotContain("Abel").doesNotContain("Bethlehem");
		}
		assertThat(notificationsOf(m.admin(), "MESSAGE_REPORTED").getFirst().get("data").toString()).contains("/s/").contains("/messaging/review");
		// the report shows up for reviewers as ids and a code
		JsonNode reports = get(m.api() + "/review/reports", m.admin());
		assertThat(reports).hasSize(1);
		assertThat(reports.toString()).doesNotContain("CANARY");
		// observers may report too
		Msg k = school("mod-report-observer");
		UUID thread = teacherStudentThread(k);
		UUID fromTeacher = send(k.lead(), thread, "an odd request");
		call(HttpMethod.POST, conv(thread) + "/messages/" + fromTeacher + "/report", k.g1(), body("reason", "SAFETY"), 204);
		assertThat(this.jdbc.queryForObject("select count(*) from message_reports where conversation_id = ?", Integer.class, thread)).isEqualTo(1);
		// own messages, tombstones, unknown reasons and outsiders
		assertThat(code(call(HttpMethod.POST, path, m.student1(), body("reason", "SPAM"), 409))).isEqualTo("messaging.cannot_report_own");
		assertThat(code(call(HttpMethod.POST, path, m.student3(), body("reason", "WEIRD"), 400))).isEqualTo("messaging.report_reason_invalid");
		assertThat(statusOf(HttpMethod.POST, path, m.stranger(), body("reason", "SPAM"))).isEqualTo(404);
		UUID gone = send(m.student1(), classThread, "oops");
		call(HttpMethod.DELETE, conv(classThread) + "/messages/" + gone, m.student1(), null, 204);
		assertThat(statusOf(HttpMethod.POST, conv(classThread) + "/messages/" + gone + "/report", m.student3(), body("reason", "SPAM"))).isEqualTo(409);
	}

	@Test
	void moderatorsHideWithAReason() throws Exception {
		Msg m = school("mod-hide");
		UUID classThread = classConversation(m);
		UUID message = send(m.student1(), classThread, "borderline");
		call(HttpMethod.POST, conv(classThread) + "/messages/" + message + "/hide", m.co(), body("reason", "please be kind"), 204);
		var audit = this.jdbc.queryForList("select actor_user_id, metadata::text as metadata from audit_logs where action = 'messaging.message.hide' and target_id = ?", message);
		assertThat(audit).hasSize(1);
		assertThat(audit.getFirst().get("actor_user_id")).isEqualTo(m.co().userId());
		assertThat((String) audit.getFirst().get("metadata")).contains("please be kind").doesNotContain("borderline");
		// a school administrator hides without being a member; a stranger and another school's administrator cannot
		UUID second = send(m.student2(), classThread, "also borderline");
		call(HttpMethod.POST, conv(classThread) + "/messages/" + second + "/hide", m.admin(), body("reason", "reported to me"), 204);
		assertThat(statusOf(HttpMethod.POST, conv(classThread) + "/messages/" + second + "/unhide", m.otherAdmin(), null)).isEqualTo(404);
		// direct conversations have no moderators: only a reviewer, and only once the conversation is reviewable
		UUID thread = parentTeacherThread(m);
		UUID direct = send(m.g1(), thread, "private words");
		assertThat(statusOf(HttpMethod.POST, conv(thread) + "/messages/" + direct + "/hide", m.admin(), body("reason", "curious"))).isEqualTo(404);
		call(HttpMethod.POST, conv(thread) + "/messages/" + direct + "/report", m.lead(), body("reason", "INAPPROPRIATE"), 204);
		call(HttpMethod.POST, conv(thread) + "/messages/" + direct + "/hide", m.admin(), body("reason", "reported and reviewed"), 204);
		assertThat(get(conv(thread) + "/messages/" + direct, m.lead()).get("status").asString()).isEqualTo("HIDDEN");
	}

	@Test
	void nobodyEditsOthersMessages() throws Exception {
		Msg m = school("mod-edit");
		UUID classThread = classConversation(m);
		UUID message = send(m.student1(), classThread, "my words");
		String path = conv(classThread) + "/messages/" + message;
		for (var who : new com.nemaz.school.support.Persona[] { m.lead(), m.co(), m.student2() }) {
			assertThat(code(call(HttpMethod.PATCH, path, who, body("body", "rewritten"), 403))).isEqualTo("messaging.not_your_message");
			assertThat(code(call(HttpMethod.DELETE, path, who, null, 403))).isEqualTo("messaging.not_your_message");
		}
		assertThat(statusOf(HttpMethod.PATCH, path, m.admin(), body("body", "rewritten"))).isEqualTo(404);
		assertThat(get(path, m.student2()).get("body").asString()).isEqualTo("my words");
	}

	@Test
	void removalAuditsTheReason() throws Exception {
		Msg m = school("mod-remove");
		UUID classThread = classConversation(m);
		send(m.student3(), classThread, "CANARY-REMOVE");
		UUID chala = memberIdOf(m.lead(), classThread, m.student3());
		call(HttpMethod.POST, conv(classThread) + "/members/" + chala + "/remove", m.co(), body("reason", "moved to another school"), 200);
		var audit = this.jdbc.queryForList("select actor_user_id, metadata::text as metadata from audit_logs where action = 'messaging.member.remove' and target_id = ?",
				classThread);
		assertThat(audit).hasSize(1);
		assertThat(audit.getFirst().get("actor_user_id")).isEqualTo(m.co().userId());
		assertThat((String) audit.getFirst().get("metadata")).contains("moved to another school").doesNotContain("CANARY");
	}

	@Test
	void reportsAreRateLimited() throws Exception {
		Msg m = school("mod-limit");
		UUID classThread = classConversation(m);
		int limited = -1;
		for (int i = 1; i <= 25 && limited < 0; i++) {
			UUID message = send(m.lead(), classThread, "announcement " + i);
			int status = statusOf(HttpMethod.POST, conv(classThread) + "/messages/" + message + "/report", m.student1(), body("reason", "SPAM"));
			if (status == 429) {
				limited = i;
			}
			else {
				assertThat(status).isEqualTo(204);
			}
		}
		assertThat(limited).isBetween(20, 24);
	}

}
