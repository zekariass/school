package com.nemaz.school.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.nemaz.school.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Rule book R2 (parent-teacher) and R3 (parent-parent): who may start which DIRECT conversation. */
@IntegrationTest
class StartConversationTest extends MessagingTestSupport {

	@Test
	void guardianStartsDirectWithTeacherOfTheChild() throws Exception {
		Msg m = school("start-gt");
		JsonNode created = startDirect(m.g1(), m, m.lead().userId(), m.s1(), 201);
		JsonNode summary = created.get("summary");
		assertThat(summary.get("type").asString()).isEqualTo("DIRECT");
		assertThat(summary.get("directKind").asString()).isEqualTo("GUARDIAN_TEACHER");
		assertThat(summary.get("myActingAs").asString()).isEqualTo("GUARDIAN");
		assertThat(summary.get("contextStudent").get("studentId").asString()).isEqualTo(m.s1().toString());
		assertThat(summary.get("counterpart").get("displayName").asString()).isEqualTo("start-gt-lead");
		assertThat(summary.get("counterpart").get("actingAs").asString()).isEqualTo("TEACHER");
		// the conversation is in the inbox of both, grouped by the child for the guardian
		assertThat(inboxIds(m.g1())).contains(conversationId(created));
		assertThat(inboxIds(m.lead())).contains(conversationId(created));
		assertThat(get("/api/v1/me/conversations?studentId=" + m.s1(), m.g1()).get("items")).hasSize(1);
		assertThat(get("/api/v1/me/conversations?studentId=" + m.s2(), m.g1()).get("items")).isEmpty();
		// a safeguarded floor does not apply: nobody observes an adult-adult thread
		assertThat(observers(conversationId(created))).isEmpty();
	}

	@Test
	void contextStudentIsRequired() throws Exception {
		Msg m = school("start-ctx");
		JsonNode problem = startDirect(m.g1(), m, m.lead().userId(), null, 400);
		assertThat(code(problem)).isEqualTo("messaging.context_student_required");
		assertThat(code(startDirect(m.lead(), m, m.g1().userId(), null, 400))).isEqualTo("messaging.context_student_required");
	}

	@Test
	void oneThreadPerGuardianTeacherAndChild() throws Exception {
		Msg m = school("start-unique");
		UUID first = conversationId(startDirect(m.g1(), m, m.lead().userId(), m.s1(), 201));
		assertThat(conversationId(startDirect(m.g1(), m, m.lead().userId(), m.s1(), 200))).isEqualTo(first);
		// the teacher reaches the same thread
		assertThat(conversationId(startDirect(m.lead(), m, m.g1().userId(), m.s1(), 200))).isEqualTo(first);
		// another teacher, another guardian: other threads
		UUID withCo = conversationId(startDirect(m.g1(), m, m.co().userId(), m.s1(), 201));
		UUID otherGuardian = conversationId(startDirect(m.g1b(), m, m.lead().userId(), m.s1(), 201));
		assertThat(java.util.List.of(first, withCo, otherGuardian)).doesNotHaveDuplicates();
	}

	@Test
	void teacherStartsWithGuardianOfOwnStudent() throws Exception {
		Msg m = school("start-tg");
		JsonNode created = startDirect(m.lead(), m, m.g1().userId(), m.s1(), 201);
		assertThat(created.get("summary").get("myActingAs").asString()).isEqualTo("TEACHER");
		// not about a child the teacher does not teach, and not with a guardian of another child
		assertThat(code(startDirect(m.lead(), m, m.otherGuardian().userId(), m.o1(), 403))).isEqualTo("messaging.not_allowed");
		assertThat(code(startDirect(m.lead(), m, m.g2().userId(), m.s1(), 403))).isEqualTo("messaging.not_allowed");
		// restricted, revoked and pending guardians cannot be messaged
		assertThat(code(startDirect(m.lead(), m, m.restricted().userId(), m.s1(), 403))).isEqualTo("messaging.not_allowed");
		assertThat(code(startDirect(m.lead(), m, m.revoked().userId(), m.s1(), 403))).isEqualTo("messaging.not_allowed");
		assertThat(code(startDirect(m.lead(), m, m.pending().userId(), m.s1(), 403))).isEqualTo("messaging.not_allowed");
	}

	@Test
	void subjectAndCoTeachersAreAllowedAssistantsAreNot() throws Exception {
		Msg m = school("start-roles");
		startDirect(m.g1(), m, m.co().userId(), m.s1(), 201);
		startDirect(m.g1(), m, m.subject().userId(), m.s1(), 201);
		assertThat(code(startDirect(m.g1(), m, m.assistant().userId(), m.s1(), 403))).isEqualTo("messaging.not_allowed");
		assertThat(code(startDirect(m.assistant(), m, m.g1().userId(), m.s1(), 403))).isEqualTo("messaging.not_allowed");
	}

	@Test
	void teacherOfAnotherClassIsRefused() throws Exception {
		Msg m = school("start-other");
		assertThat(code(startDirect(m.g1(), m, m.otherTeacher().userId(), m.s1(), 403))).isEqualTo("messaging.not_allowed");
		assertThat(code(startDirect(m.otherTeacher(), m, m.g1().userId(), m.s1(), 403))).isEqualTo("messaging.not_allowed");
		// a guardian of another child cannot start a thread about Abel
		assertThat(code(startDirect(m.g2(), m, m.lead().userId(), m.s1(), 403))).isEqualTo("messaging.not_allowed");
		// a stranger neither
		assertThat(code(startDirect(m.stranger(), m, m.lead().userId(), m.s1(), 404))).isEqualTo("school.not_found");
	}

	@Test
	void parentTeacherSettingOffRefuses() throws Exception {
		Msg m = school("start-off");
		setting(m.schoolId(), "messaging.parent_teacher.enabled", false);
		assertThat(code(startDirect(m.g1(), m, m.lead().userId(), m.s1(), 403))).isEqualTo("messaging.not_allowed");
		assertThat(code(startDirect(m.lead(), m, m.g1().userId(), m.s1(), 403))).isEqualTo("messaging.not_allowed");
		// nothing was created
		assertThat(this.jdbc.queryForObject("select count(*) from conversations where school_id = ?", Integer.class, m.schoolId()))
			.isZero();
	}

	@Test
	void teacherSeesOnlyTheGuardiansRoleLabel() throws Exception {
		Msg m = school("start-label");
		UUID thread = parentTeacherThread(m);
		String guardianPhone = this.jdbc.queryForObject("select phone_e164 from users where id = ?", String.class, m.g1().userId());
		JsonNode members = get(conv(thread) + "/members", m.lead());
		JsonNode guardian = null;
		for (JsonNode member : members.get("members")) {
			if ("GUARDIAN".equals(member.get("actingAs").asString())) {
				guardian = member;
			}
		}
		assertThat(guardian).isNotNull();
		assertThat(guardian.has("displayName") && !guardian.get("displayName").isNull()).isFalse();
		assertThat(guardian.get("guardian").get("relationshipType").asString()).isEqualTo("MOTHER");
		assertThat(guardian.get("guardian").get("studentGivenName").asString()).isEqualTo("Abel");
		for (JsonNode payload : new JsonNode[] { members, get(conv(thread), m.lead()), inbox(m.lead()) }) {
			assertThat(payload.toString()).doesNotContain("start-label-guardian1").doesNotContain(guardianPhone)
				.doesNotContain("dateOfBirth").doesNotContain("phone").doesNotContain("email");
		}
		// the guardian sees the teacher by name and herself with her own name
		JsonNode asGuardian = get(conv(thread) + "/members", m.g1());
		assertThat(asGuardian.toString()).contains("start-label-lead").contains("start-label-guardian1");
	}

	@Test
	void staffToStaffAndAdminDirectsAreNotSupported() throws Exception {
		Msg m = school("start-staff");
		assertThat(code(startDirect(m.lead(), m, m.co().userId(), m.s1(), 403))).isEqualTo("messaging.not_allowed");
		assertThat(code(startDirect(m.lead(), m, m.staff().userId(), m.s1(), 403))).isEqualTo("messaging.not_allowed");
		assertThat(code(startDirect(m.staff(), m, m.g1().userId(), m.s1(), 403))).isEqualTo("messaging.not_allowed");
		assertThat(code(startDirect(m.admin(), m, m.g1().userId(), m.s1(), 403))).isEqualTo("messaging.not_allowed");
		assertThat(code(startDirect(m.admin(), m, m.s1(), null, 403))).isEqualTo("messaging.not_allowed");
		assertThat(code(startDirect(m.g1(), m, m.staff().userId(), m.s1(), 403))).isEqualTo("messaging.not_allowed");
		assertThat(code(startDirect(m.g1(), m, m.g1().userId(), m.s1(), 403))).isEqualTo("messaging.not_allowed");
	}

	@Test
	void parentParentIsOffByDefault() throws Exception {
		Msg m = school("start-pp-off");
		assertThat(code(startDirect(m.g1(), m, m.g2().userId(), m.s1(), 403))).isEqualTo("messaging.not_allowed");
		setting(m.schoolId(), "messaging.parent_parent.enabled", true);
		JsonNode created = startDirect(m.g1(), m, m.g2().userId(), m.s1(), 201);
		assertThat(created.get("summary").get("directKind").asString()).isEqualTo("GUARDIAN_GUARDIAN");
		// switching it off again closes the thread for both at once
		setting(m.schoolId(), "messaging.parent_parent.enabled", false);
		assertThat(statusOf(HttpMethod.GET, conv(conversationId(created)), m.g1(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, conv(conversationId(created)), m.g2(), null)).isEqualTo(404);
	}

	@Test
	void parentParentNeedsChildrenInTheSameClass() throws Exception {
		Msg m = school("start-pp-class");
		setting(m.schoolId(), "messaging.parent_parent.enabled", true);
		assertThat(code(startDirect(m.g1(), m, m.otherGuardian().userId(), m.s1(), 403))).isEqualTo("messaging.not_allowed");
		assertThat(code(startDirect(m.otherGuardian(), m, m.g1().userId(), m.o1(), 403))).isEqualTo("messaging.not_allowed");
		// a guardian with a restricted link cannot take part
		assertThat(code(startDirect(m.g2(), m, m.restricted().userId(), m.s2(), 403))).isEqualTo("messaging.not_allowed");
		// two guardians of the same child may talk
		startDirect(m.g1(), m, m.g1b().userId(), m.s1(), 201);
	}

	@Test
	void parentParentNeverRevealsContactDetails() throws Exception {
		Msg m = school("start-pp-privacy");
		setting(m.schoolId(), "messaging.parent_parent.enabled", true);
		UUID thread = conversationId(startDirect(m.g1(), m, m.g2().userId(), m.s1(), 201));
		String phone = this.jdbc.queryForObject("select phone_e164 from users where id = ?", String.class, m.g2().userId());
		JsonNode members = get(conv(thread) + "/members", m.g1());
		for (JsonNode member : members.get("members")) {
			if (!member.get("self").asBoolean()) {
				assertThat(member.has("displayName") && !member.get("displayName").isNull()).isFalse();
				assertThat(member.get("guardian").has("relationshipType") && !member.get("guardian").get("relationshipType").isNull())
					.isFalse();
				assertThat(member.get("guardian").has("studentGivenName") && !member.get("guardian").get("studentGivenName").isNull())
					.isFalse();
			}
		}
		assertThat(get(conv(thread), m.g1()).toString()).doesNotContain(phone).doesNotContain("start-pp-privacy-guardian2");
		// the contacts list for parent-parent shows labels only
		JsonNode contacts = get(m.api() + "/contacts?studentId=" + m.s1() + "&audience=PARENTS", m.g1());
		assertThat(contacts.toString()).doesNotContain(phone).doesNotContain("start-pp-privacy-guardian");
		assertThat(contacts).isNotEmpty();
	}

}
