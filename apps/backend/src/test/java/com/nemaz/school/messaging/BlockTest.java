package com.nemaz.school.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Rule book R7: blocks. */
@IntegrationTest
class BlockTest extends MessagingTestSupport {

	private JsonNode block(Persona who, UUID thread, UUID memberId, int status) throws Exception {
		return post("/api/v1/me/blocks", who, body("conversationId", thread, "memberId", memberId), status);
	}

	@Test
	void blockedUserCannotStartOrContinueDirect() throws Exception {
		Msg m = school("block-direct");
		UUID thread = parentTeacherThread(m);
		send(m.lead(), thread, "hello");
		JsonNode created = block(m.g1(), thread, memberIdOf(m.g1(), thread, m.lead()), 201);
		// the blocked teacher gets the generic refusal for sending and for starting again
		assertThat(code(send(m.lead(), thread, "are you there?", 403))).isEqualTo("messaging.not_allowed");
		assertThat(code(startDirect(m.lead(), m, m.g1().userId(), m.s1(), 403))).isEqualTo("messaging.not_allowed");
		// the blocker is told, and can lift it
		assertThat(code(send(m.g1(), thread, "hello?", 403))).isEqualTo("messaging.you_blocked");
		assertThat(code(startDirect(m.g1(), m, m.lead().userId(), m.s1(), 403))).isEqualTo("messaging.you_blocked");
		call(HttpMethod.DELETE, "/api/v1/me/blocks/" + created.get("id").asString(), m.g1(), null, 204);
		send(m.lead(), thread, "welcome back");
		assertThat(get("/api/v1/me/blocks", m.g1())).isEmpty();
	}

	@Test
	void refusalIsTheSameGenericCode() throws Exception {
		Msg m = school("block-generic");
		UUID thread = parentTeacherThread(m);
		block(m.g1(), thread, memberIdOf(m.g1(), thread, m.lead()), 201);
		JsonNode blocked = send(m.lead(), thread, "hi", 403);
		setting(m.schoolId(), "messaging.parent_teacher.enabled", false);
		JsonNode settingOff = startDirect(m.lead(), m, m.g2().userId(), m.s2(), 403);
		assertThat(code(blocked)).isEqualTo(code(settingOff)).isEqualTo("messaging.not_allowed");
		assertThat(blocked.get("detail")).isEqualTo(settingOff.get("detail"));
		assertThat(blocked.toString()).doesNotContain("block");
	}

	@Test
	void blockerKnowsTheyBlocked() throws Exception {
		Msg m = school("block-blocker");
		UUID thread = parentTeacherThread(m);
		block(m.lead(), thread, memberIdOf(m.lead(), thread, m.g1()), 201);
		assertThat(code(send(m.lead(), thread, "x", 403))).isEqualTo("messaging.you_blocked");
		assertThat(code(send(m.g1(), thread, "x", 403))).isEqualTo("messaging.not_allowed");
		// blocking twice is harmless
		JsonNode again = block(m.lead(), thread, memberIdOf(m.lead(), thread, m.g1()), 201);
		assertThat(get("/api/v1/me/blocks", m.lead())).hasSize(1);
		assertThat(again.get("id").asString()).isEqualTo(get("/api/v1/me/blocks", m.lead()).get(0).get("id").asString());
		assertThat(code(block(m.lead(), thread, memberIdOf(m.lead(), thread, m.lead()), 400))).isEqualTo("messaging.cannot_block_self");
	}

	@Test
	void blockedUsersMessagesAreHiddenFromTheBlockerInGroups() throws Exception {
		Msg m = school("block-group");
		UUID classThread = classConversation(m);
		UUID fromBlocked = send(m.student2(), classThread, "unkind words");
		UUID fromOther = send(m.student3(), classThread, "kind words");
		block(m.student1(), classThread, memberIdOf(m.student1(), classThread, m.student2()), 201);
		// hidden from the blocker: the list, the single message, the preview and the unread count
		assertThat(bodies(messagesOf(m.student1(), classThread))).containsExactly("kind words");
		assertThat(statusOf(HttpMethod.GET, conv(classThread) + "/messages/" + fromBlocked, m.student1(), null)).isEqualTo(404);
		assertThat(get(conv(classThread) + "/messages/" + fromOther, m.student1()).get("body").asString()).isEqualTo("kind words");
		assertThat(unread(m.student1(), classThread)).isEqualTo(1);
		send(m.student2(), classThread, "even more");
		assertThat(unread(m.student1(), classThread)).isEqualTo(1);
		assertThat(inbox(m.student1()).get("items").get(0).get("lastMessage").get("preview").asString()).isEqualTo("kind words");
		assertThat(inbox(m.student1()).toString()).doesNotContain("unkind").doesNotContain("even more");
		// everybody else still sees them; the blocked person still sees the blocker
		assertThat(bodies(messagesOf(m.lead(), classThread))).contains("unkind words", "even more");
		assertThat(bodies(messagesOf(m.student3(), classThread))).contains("unkind words");
		send(m.student1(), classThread, "hi all");
		assertThat(bodies(messagesOf(m.student2(), classThread))).contains("hi all");
		// the images of a blocked sender are hidden too
		setting(m.schoolId(), "messaging.student.media_enabled", true);
		UUID media = image(m.student2(), classThread);
		post(conv(classThread) + "/messages", m.student2(), body("clientMessageId", UUID.randomUUID(), "mediaIds", List.of(media)), 201);
		assertThat(statusOf(HttpMethod.GET, "/api/v1/media/" + media + "/url?variant=SMALL", m.student1(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, "/api/v1/media/" + media + "/url?variant=SMALL", m.student3(), null)).isEqualTo(200);
	}

	@Test
	void studentCannotBlockTheirObservers() throws Exception {
		Msg m = school("block-observers");
		UUID thread = teacherStudentThread(m);
		UUID observerMember = this.jdbc.queryForObject(
				"select id from conversation_members where conversation_id = ? and user_id = ?", UUID.class, thread, m.g1().userId());
		assertThat(code(block(m.student1(), thread, observerMember, 403))).isEqualTo("messaging.cannot_block");
		// a guardian in a group with the student's other conversations: also refused (they are the student's guardian)
		assertThat(this.jdbc.queryForObject("select count(*) from user_blocks where blocker_id = ?", Integer.class, m.s1())).isZero();
		// the teacher can be blocked by the student: the conversation then closes for the teacher, never for the observers' reading
		UUID teacherMember = memberIdOf(m.student1(), thread, m.lead());
		block(m.student1(), thread, teacherMember, 201);
		assertThat(code(send(m.lead(), thread, "x", 403))).isEqualTo("messaging.not_allowed");
		assertThat(statusOf(HttpMethod.GET, conv(thread) + "/messages", m.g1(), null)).isEqualTo(200);
	}

	@Test
	void observersSeeEverythingWhateverTheyBlocked() throws Exception {
		Msg m = school("block-observer-view");
		UUID parentThread = parentTeacherThread(m);
		// the guardian blocked the teacher in the parent-teacher thread
		block(m.g1(), parentThread, memberIdOf(m.g1(), parentThread, m.lead()), 201);
		UUID thread = teacherStudentThread(m);
		UUID message = send(m.lead(), thread, "from the teacher the guardian blocked");
		assertThat(bodies(messagesOf(m.g1(), thread))).containsExactly("from the teacher the guardian blocked");
		assertThat(get(conv(thread) + "/messages/" + message, m.g1()).get("body").asString()).startsWith("from the teacher");
		assertThat(inbox(m.g1()).toString()).contains("from the teacher the guardian blocked");
	}

	@Test
	void blocksDoNotAffectAnnouncements() throws Exception {
		Msg m = school("block-announce");
		UUID thread = parentTeacherThread(m);
		block(m.g1(), thread, memberIdOf(m.g1(), thread, m.lead()), 201);
		JsonNode announcement = post("/api/v1/schools/" + m.schoolId() + "/announcements", m.lead(),
				body("title", "School closed on Friday", "body", "Please keep your child at home.", "mode", "PUBLISH", "audiences",
						List.of(body("type", "CLASS", "classId", m.classId(), "roles", List.of("GUARDIAN")))), 201);
		JsonNode inbox = get("/api/v1/me/announcements", m.g1());
		assertThat(inbox.toString()).contains(announcement.get("id").asString()).contains("School closed on Friday");
	}

	@Test
	void studentBlockOfAnAdultIsAuditedAndListedForReviewers() throws Exception {
		Msg m = school("block-review");
		UUID classThread = classConversation(m);
		JsonNode created = block(m.student1(), classThread, memberIdOf(m.student1(), classThread, m.lead()), 201);
		var audit = this.jdbc.queryForList("""
				select metadata::text as metadata from audit_logs where action = 'messaging.block.create' and school_id = ?""", m.schoolId());
		assertThat(audit).hasSize(1);
		String metadata = (String) audit.getFirst().get("metadata");
		assertThat(metadata).contains("\"studentBlockedAdult\": true").contains(m.s1().toString()).contains(m.lead().userId().toString());
		JsonNode list = get(m.api() + "/review/blocks", m.admin());
		assertThat(list).hasSize(1);
		assertThat(list.get(0).get("blockerId").asString()).isEqualTo(m.s1().toString());
		assertThat(list.get(0).get("blockedId").asString()).isEqualTo(m.lead().userId().toString());
		// a block between two students is not listed
		block(m.student2(), classThread, memberIdOf(m.student2(), classThread, m.student3()), 201);
		assertThat(get(m.api() + "/review/blocks", m.admin())).hasSize(1);
		// nobody else may list them
		assertThat(statusOf(HttpMethod.GET, m.api() + "/review/blocks", m.lead(), null)).isEqualTo(403);
		assertThat(statusOf(HttpMethod.GET, m.api() + "/review/blocks", m.g1(), null)).isEqualTo(404);
		assertThat(created.get("id")).isNotNull();
	}

	@Test
	void blocksUseMemberIdsNotUserIds() throws Exception {
		Msg m = school("block-ids");
		UUID classThread = classConversation(m);
		JsonNode created = block(m.student1(), classThread, memberIdOf(m.student1(), classThread, m.student2()), 201);
		String everything = created.toString() + get("/api/v1/me/blocks", m.student1());
		assertThat(everything).doesNotContain(m.s2().toString()).doesNotContain("userId");
		// the member id must belong to the conversation
		UUID foreignMember = this.jdbc.queryForObject("select id from conversation_members where user_id = ? limit 1", UUID.class, m.lead().userId());
		UUID other = conversationId(startDirect(m.g1(), m, m.co().userId(), m.s1(), 201));
		assertThat(statusOf(HttpMethod.POST, "/api/v1/me/blocks", m.student1(), body("conversationId", other, "memberId", foreignMember)))
			.isEqualTo(404);
		assertThat(code(block(m.student1(), classThread, UUID.randomUUID(), 404))).isEqualTo("messaging.member_not_found");
		// another person's block id means nothing
		assertThat(statusOf(HttpMethod.DELETE, "/api/v1/me/blocks/" + created.get("id").asString(), m.student2(), null)).isEqualTo(404);
	}

}
