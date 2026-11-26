package com.nemaz.school.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Rule book R12: inbox, conversation messages and single messages; what each person may see. */
@IntegrationTest
class ConversationReadsTest extends MessagingTestSupport {

	private List<String> ids(JsonNode page) {
		List<String> ids = new ArrayList<>();
		page.get("items").forEach((i) -> ids.add(i.get("id").asString()));
		return ids;
	}

	@Test
	void inboxIsKeysetPagedWithUnreadCounts() throws Exception {
		Msg m = school("reads-inbox");
		UUID withLead = parentTeacherThread(m);
		UUID withCo = conversationId(startDirect(m.g1(), m, m.co().userId(), m.s1(), 201));
		UUID withSubject = conversationId(startDirect(m.g1(), m, m.subject().userId(), m.s1(), 201));
		UUID parents = parentGroup(m);
		send(m.lead(), withLead, "one");
		send(m.lead(), withLead, "two");
		send(m.co(), withCo, "hello");
		send(m.subject(), withSubject, "hi");
		send(m.lead(), parents, "to all parents");
		send(m.subject(), withSubject, "again");
		// newest activity first, unread counted per conversation
		JsonNode all = get("/api/v1/me/conversations", m.g1());
		assertThat(ids(all)).containsExactly(withSubject.toString(), parents.toString(), withCo.toString(), withLead.toString());
		assertThat(all.get("items").get(0).get("unreadCount").asInt()).isEqualTo(2);
		assertThat(all.get("items").get(3).get("unreadCount").asInt()).isEqualTo(2);
		assertThat(all.get("items").get(0).get("lastMessage").get("preview").asString()).isEqualTo("again");
		// keyset pages cover everything exactly once, also while a new message arrives
		JsonNode first = get("/api/v1/me/conversations?limit=2", m.g1());
		assertThat(first.get("items")).hasSize(2);
		String cursor = first.get("nextCursor").asString();
		send(m.lead(), withLead, "a newer message moves that conversation up");
		JsonNode second = get("/api/v1/me/conversations?limit=2&cursor=" + cursor, m.g1());
		List<String> paged = new ArrayList<>(ids(first));
		paged.addAll(ids(second));
		// the conversation that moved to the top while paging is not repeated further down
		assertThat(paged).doesNotHaveDuplicates().containsExactly(withSubject.toString(), parents.toString(), withCo.toString());
		assertThat(ids(get("/api/v1/me/conversations", m.g1())).getFirst()).isEqualTo(withLead.toString());
		assertThat(second.has("nextCursor") && !second.get("nextCursor").isNull()).isFalse();
		assertThat(code(call(HttpMethod.GET, "/api/v1/me/conversations?cursor=not-a-cursor", m.g1(), null, 400))).isEqualTo("invalid_cursor");
		// a person with nothing sees nothing
		assertThat(inbox(m.stranger()).get("items")).isEmpty();
		assertThat(inbox(m.g2()).get("items")).hasSize(1);
	}

	@Test
	void messagesPageNewestFirst() throws Exception {
		Msg m = school("reads-messages");
		UUID thread = parentTeacherThread(m);
		for (int i = 1; i <= 5; i++) {
			send((i % 2 == 0) ? m.lead() : m.g1(), thread, "m" + i);
		}
		JsonNode first = get(conv(thread) + "/messages?limit=2", m.g1());
		assertThat(bodies(first)).containsExactly("m5", "m4");
		JsonNode second = get(conv(thread) + "/messages?limit=2&cursor=" + first.get("nextCursor").asString(), m.g1());
		assertThat(bodies(second)).containsExactly("m3", "m2");
		JsonNode third = get(conv(thread) + "/messages?limit=2&cursor=" + second.get("nextCursor").asString(), m.g1());
		assertThat(bodies(third)).containsExactly("m1");
		assertThat(third.has("nextCursor") && !third.get("nextCursor").isNull()).isFalse();
		// the sender flag and the same page for the other side
		assertThat(first.get("items").get(0).get("mine").asBoolean()).isTrue();
		assertThat(get(conv(thread) + "/messages?limit=1", m.lead()).get("items").get(0).get("mine").asBoolean()).isFalse();
		assertThat(code(call(HttpMethod.GET, conv(thread) + "/messages?cursor=zzz", m.g1(), null, 400))).isEqualTo("invalid_cursor");
	}

	@Test
	void previewHasNoBodyForTombstones() throws Exception {
		Msg m = school("reads-preview");
		UUID classThread = classConversation(m);
		send(m.student1(), classThread, "x".repeat(300));
		JsonNode preview = inbox(m.student2()).get("items").get(0).get("lastMessage");
		assertThat(preview.get("preview").asString()).hasSize(120);
		UUID hidden = send(m.student1(), classThread, "will be hidden");
		call(HttpMethod.POST, conv(classThread) + "/messages/" + hidden + "/hide", m.lead(), body("reason", "unkind"), 204);
		JsonNode afterHide = inbox(m.student2()).get("items").get(0).get("lastMessage");
		assertThat(afterHide.get("status").asString()).isEqualTo("HIDDEN");
		assertThat(afterHide.has("preview") && !afterHide.get("preview").isNull()).isFalse();
		assertThat(inbox(m.student2()).toString()).doesNotContain("will be hidden");
		UUID deleted = send(m.student1(), classThread, "will be deleted");
		call(HttpMethod.DELETE, conv(classThread) + "/messages/" + deleted, m.student1(), null, 204);
		JsonNode afterDelete = inbox(m.student1()).get("items").get(0).get("lastMessage");
		assertThat(afterDelete.get("status").asString()).isEqualTo("DELETED");
		assertThat(inbox(m.lead()).toString()).doesNotContain("will be deleted").doesNotContain("will be hidden");
	}

	@Test
	void guardianInboxCarriesTheChild() throws Exception {
		Msg m = school("reads-child");
		Persona parent = this.personas.newUser("reads-child-parent");
		this.personas.linkGuardian(m.s1(), parent.userId(), "ACTIVE", false);
		this.personas.linkGuardian(m.s2(), parent.userId(), "ACTIVE", false);
		UUID aboutAbel = conversationId(startDirect(parent, m, m.lead().userId(), m.s1(), 201));
		UUID aboutBethlehem = conversationId(startDirect(parent, m, m.co().userId(), m.s2(), 201));
		JsonNode all = get("/api/v1/me/conversations", parent);
		for (JsonNode item : all.get("items")) {
			String child = item.get("onBehalfOfStudentId").asString();
			assertThat(item.get("contextStudent").get("studentId").asString()).isEqualTo(child);
			assertThat(item.get("contextStudent").get("displayName").asString()).isEqualTo(child.equals(m.s1().toString()) ? "Abel" : "Bethlehem");
		}
		assertThat(ids(get("/api/v1/me/conversations?studentId=" + m.s1(), parent))).containsExactly(aboutAbel.toString());
		assertThat(ids(get("/api/v1/me/conversations?studentId=" + m.s2(), parent))).containsExactly(aboutBethlehem.toString());
		assertThat(ids(get("/api/v1/me/conversations?studentId=" + UUID.randomUUID(), parent))).isEmpty();
		// the teacher sees the same child as the thread's subject
		assertThat(get("/api/v1/me/conversations", m.lead()).get("items").get(0).get("contextStudent").get("displayName").asString()).isEqualTo("Abel");
		// a link that ends removes that child's thread only
		revoke(linkOf(parent, m.s1()));
		assertThat(ids(inbox(parent))).containsExactly(aboutBethlehem.toString());
	}

	@Test
	void nonMembersGet404MembersWithoutPermissionGet403() throws Exception {
		Msg m = school("reads-403");
		UUID classThread = classConversation(m);
		UUID thread = teacherStudentThread(m);
		UUID message = send(m.lead(), thread, "hello");
		for (Persona out : new Persona[] { m.stranger(), m.g2(), m.otherStudent(), m.otherAdmin(), m.admin() }) {
			assertThat(statusOf(HttpMethod.GET, conv(thread), out, null)).as(out.name()).isEqualTo(404);
			assertThat(statusOf(HttpMethod.GET, conv(thread) + "/messages/" + message, out, null)).as(out.name()).isEqualTo(404);
			assertThat(statusOf(HttpMethod.POST, conv(thread) + "/messages/" + message + "/report", out, body("reason", "SPAM"))).as(out.name()).isEqualTo(404);
		}
		// members without the permission: 403 with a reason they may know
		assertThat(statusOf(HttpMethod.PATCH, conv(classThread), m.student1(), body("title", "x"))).isEqualTo(403);
		assertThat(code(post(conv(thread) + "/messages", m.g1(), body("clientMessageId", UUID.randomUUID(), "body", "x"), 403))).isEqualTo("messaging.observer_read_only");
		assertThat(statusOf(HttpMethod.POST, conv(classThread) + "/messages/" + send(m.student1(), classThread, "mine") + "/hide", m.student2(), body("reason", "no"))).isEqualTo(403);
	}

	@Test
	void studentPayloadsHaveNoPersonalData() throws Exception {
		Msg m = school("reads-student");
		for (UUID id : new UUID[] { m.s1(), m.s2(), m.s3(), m.lead().userId() }) {
			this.personas.setDateOfBirth(id, LocalDate.of(2013, 4, 17));
			this.jdbc.update("update users set gender = 'F', username = ? where id = ?", "user" + id.toString().replace("-", ""), id);
		}
		UUID classThread = classConversation(m);
		UUID thread = teacherStudentThread(m);
		send(m.student2(), classThread, "hi");
		send(m.lead(), thread, "hello Abel");
		String everything = get(conv(classThread) + "/members", m.student1()).toString() + get(conv(classThread), m.student1())
			+ messagesOf(m.student1(), classThread) + inbox(m.student1()) + get(conv(thread) + "/members", m.student1())
			+ get(conv(thread), m.student1()) + messagesOf(m.student1(), thread) + get(conv(thread) + "/members", m.lead()) + get(m.api() + "/capabilities", m.student1());
		assertThat(everything).doesNotContain("2013").doesNotContain("dateOfBirth").doesNotContain("birth").doesNotContain("gender")
			.doesNotContain("username").doesNotContain("userId").doesNotContain("phone").doesNotContain("email");
		assertThat(everything).doesNotContain("userF").doesNotContain("reads-student-guardian");
		// reviewers see names of students and teachers, still no personal fields
		allowTeacherStudentDirect(m.schoolId(), "TEACHER_ONLY");
		UUID alone = conversationId(startDirect(m.lead(), m, m.s2(), null, 201));
		assertThat(get(m.api() + "/review/conversations", m.admin()).toString()).doesNotContain("2013").doesNotContain("birth").doesNotContain("gender");
		assertThat(alone).isNotNull();
	}

}
