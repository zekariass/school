package com.nemaz.school.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.nemaz.school.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Rule book R10: read watermark, "Seen" receipts, unread counts. */
@IntegrationTest
class ReadStateTest extends MessagingTestSupport {

	private JsonNode messageView(com.nemaz.school.support.Persona who, UUID thread, UUID message) throws Exception {
		return get(conv(thread) + "/messages/" + message, who);
	}

	private boolean seen(com.nemaz.school.support.Persona who, UUID thread, UUID message) throws Exception {
		JsonNode view = messageView(who, thread, message);
		return view.has("seenAt") && !view.get("seenAt").isNull();
	}

	@Test
	void watermarkIsIdempotentAndNeverMovesBack() throws Exception {
		Msg m = school("read-mark");
		UUID thread = parentTeacherThread(m);
		UUID first = send(m.lead(), thread, "one");
		UUID second = send(m.lead(), thread, "two");
		send(m.lead(), thread, "three");
		assertThat(unread(m.g1(), thread)).isEqualTo(3);
		call(HttpMethod.POST, conv(thread) + "/read", m.g1(), body("upToMessageId", second), 204);
		assertThat(unread(m.g1(), thread)).isEqualTo(1);
		// reading an older message again does not bring messages back
		call(HttpMethod.POST, conv(thread) + "/read", m.g1(), body("upToMessageId", first), 204);
		assertThat(unread(m.g1(), thread)).isEqualTo(1);
		call(HttpMethod.POST, conv(thread) + "/read", m.g1(), null, 204);
		call(HttpMethod.POST, conv(thread) + "/read", m.g1(), null, 204);
		assertThat(unread(m.g1(), thread)).isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from message_reads where user_id = ?", Integer.class, m.g1().userId())).isEqualTo(3);
		// a message of another conversation is not a valid mark
		UUID other = conversationId(startDirect(m.g1(), m, m.co().userId(), m.s1(), 201));
		UUID foreign = send(m.co(), other, "elsewhere");
		assertThat(code(call(HttpMethod.POST, conv(thread) + "/read", m.g1(), body("upToMessageId", foreign), 404))).isEqualTo("messaging.message_not_found");
		assertThat(get("/api/v1/me/messaging/unread-count", m.g1()).get("unread").asInt()).isEqualTo(1);
	}

	@Test
	void seenReceiptsOnlyInDirectBetweenNonObservers() throws Exception {
		Msg m = school("read-seen");
		UUID thread = parentTeacherThread(m);
		UUID fromGuardian = send(m.g1(), thread, "did you get my note?");
		assertThat(seen(m.g1(), thread, fromGuardian)).isFalse();
		call(HttpMethod.POST, conv(thread) + "/read", m.lead(), null, 204);
		assertThat(seen(m.g1(), thread, fromGuardian)).isTrue();
		// a class conversation produces no receipts, however much is read
		UUID classThread = classConversation(m);
		UUID inClass = send(m.lead(), classThread, "for everybody");
		call(HttpMethod.POST, conv(classThread) + "/read", m.student1(), null, 204);
		call(HttpMethod.POST, conv(classThread) + "/read", m.student2(), null, 204);
		assertThat(this.jdbc.queryForObject("select count(*) from message_reads where message_id = ?", Integer.class, inClass)).isZero();
		assertThat(seen(m.lead(), classThread, inClass)).isFalse();
		// the parent group neither
		UUID parents = parentGroup(m);
		UUID inParents = send(m.lead(), parents, "to the parents");
		call(HttpMethod.POST, conv(parents) + "/read", m.g1(), null, 204);
		assertThat(this.jdbc.queryForObject("select count(*) from message_reads where message_id = ?", Integer.class, inParents)).isZero();
	}

	@Test
	void observerReadsNeverCreateReceipts() throws Exception {
		Msg m = school("read-observer");
		UUID thread = teacherStudentThread(m);
		UUID fromTeacher = send(m.lead(), thread, "please read chapter 4");
		call(HttpMethod.POST, conv(thread) + "/read", m.g1(), null, 204);
		call(HttpMethod.POST, conv(thread) + "/read", m.g1b(), null, 204);
		assertThat(this.jdbc.queryForObject("select count(*) from message_reads where message_id = ?", Integer.class, fromTeacher)).isZero();
		assertThat(seen(m.lead(), thread, fromTeacher)).isFalse();
		assertThat(unread(m.g1(), thread)).isZero();
		// the student's reading is the one that counts
		call(HttpMethod.POST, conv(thread) + "/read", m.student1(), null, 204);
		assertThat(seen(m.lead(), thread, fromTeacher)).isTrue();
		assertThat(this.jdbc.queryForList("select user_id from message_reads where message_id = ?", UUID.class, fromTeacher))
			.containsExactly(m.s1());
		// an observer also does not see anybody's receipts on the messages of others
		UUID fromStudent = send(m.student1(), thread, "done");
		call(HttpMethod.POST, conv(thread) + "/read", m.lead(), null, 204);
		assertThat(seen(m.g1(), thread, fromStudent)).isFalse();
		assertThat(seen(m.student1(), thread, fromStudent)).isTrue();
	}

	@Test
	void unreadCountsFollowTheRules() throws Exception {
		Msg m = school("read-unread");
		UUID classThread = classConversation(m);
		send(m.student1(), classThread, "mine");
		UUID second = send(m.student2(), classThread, "from Bethlehem");
		UUID third = send(m.student2(), classThread, "from Bethlehem again");
		send(m.lead(), classThread, "from the teacher");
		// own messages do not count
		assertThat(unread(m.student1(), classThread)).isEqualTo(3);
		assertThat(unread(m.student2(), classThread)).isEqualTo(2);
		// deleted and hidden messages do not count
		call(HttpMethod.DELETE, conv(classThread) + "/messages/" + second, m.student2(), null, 204);
		call(HttpMethod.POST, conv(classThread) + "/messages/" + third + "/hide", m.lead(), body("reason", "off topic"), 204);
		assertThat(unread(m.student1(), classThread)).isEqualTo(1);
		// the total over all conversations
		UUID thread = parentTeacherThread(m);
		send(m.g1(), thread, "to the teacher");
		assertThat(get("/api/v1/me/messaging/unread-count", m.lead()).get("unread").asInt()).isEqualTo(1 + 1);
		call(HttpMethod.POST, conv(thread) + "/read", m.lead(), null, 204);
		assertThat(get("/api/v1/me/messaging/unread-count", m.lead()).get("unread").asInt()).isEqualTo(1);
		// a conversation one cannot use does not count: the lead leaves the class
		this.jdbc.update("update class_teachers set status = 'FORMER', left_at = now() where teacher_id = ? and class_id = ?", m.lead().userId(), m.classId());
		assertThat(get("/api/v1/me/messaging/unread-count", m.lead()).get("unread").asInt()).isZero();
	}

	@Test
	void onlyTheSenderSeesReceipts() throws Exception {
		Msg m = school("read-sender");
		UUID thread = parentTeacherThread(m);
		UUID message = send(m.g1(), thread, "private to the two of us");
		call(HttpMethod.POST, conv(thread) + "/read", m.lead(), null, 204);
		assertThat(seen(m.g1(), thread, message)).isTrue();
		assertThat(seen(m.lead(), thread, message)).isFalse();
		assertThat(messagesOf(m.lead(), thread).toString()).doesNotContain("\"seenAt\":\"");
	}

}
