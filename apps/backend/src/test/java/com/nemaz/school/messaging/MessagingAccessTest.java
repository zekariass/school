package com.nemaz.school.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/**
 * Rule book R1: access is checked on every request, so changes to links, rosters, accounts and schools take effect at once,
 * before any event is processed, and a membership row alone never grants anything.
 */
@IntegrationTest
class MessagingAccessTest extends MessagingTestSupport {

	@Autowired
	CacheManager caches;

	private void assertGone(UUID thread, Persona... people) throws Exception {
		for (Persona who : people) {
			assertThat(statusOf(HttpMethod.GET, conv(thread), who, null)).as(who + " conversation").isEqualTo(404);
			assertThat(statusOf(HttpMethod.GET, conv(thread) + "/messages", who, null)).as(who + " messages").isEqualTo(404);
			assertThat(statusOf(HttpMethod.GET, conv(thread) + "/members", who, null)).as(who + " members").isEqualTo(404);
			assertThat(statusOf(HttpMethod.POST, conv(thread) + "/messages", who,
					body("clientMessageId", UUID.randomUUID(), "body", "still here?"))).as(who + " send").isEqualTo(404);
			assertThat(statusOf(HttpMethod.POST, conv(thread) + "/read", who, body())).as(who + " read").isEqualTo(404);
			assertThat(inboxIds(who)).as(who + " inbox").doesNotContain(thread);
		}
	}

	@Test
	void restrictedGuardianLosesAccessImmediately() throws Exception {
		Msg m = school("acc-restricted");
		UUID thread = parentTeacherThread(m);
		send(m.g1(), thread, "hello");
		send(m.lead(), thread, "hello back");
		assertThat(unread(m.g1(), thread)).isEqualTo(1);
		restrict(m.g1Link());
		assertGone(thread, m.g1());
		// the thread is dead for the teacher too: R1 needs both people
		assertGone(thread, m.lead());
		// when the restriction is lifted the history is back
		unrestrict(m.g1Link());
		assertThat(bodies(messagesOf(m.g1(), thread))).containsExactly("hello back", "hello");
	}

	@Test
	void revokedGuardianLosesAccessImmediately() throws Exception {
		Msg m = school("acc-revoked");
		UUID thread = parentTeacherThread(m);
		send(m.g1(), thread, "hello");
		revoke(m.g1Link());
		assertGone(thread, m.g1(), m.lead());
	}

	@Test
	void guardianWithoutMessageFlagHasNoAccess() throws Exception {
		Msg m = school("acc-flag");
		UUID thread = parentTeacherThread(m);
		this.jdbc.update("update student_guardians set can_message_staff = false where id = ?", m.g1Link());
		assertGone(thread, m.g1(), m.lead());
		assertThat(code(startDirect(m.g1(), m, m.co().userId(), m.s1(), 403))).isEqualTo("messaging.not_allowed");
		assertThat(statusOf(HttpMethod.GET, m.api() + "/contacts?studentId=" + m.s1(), m.g1(), null)).isEqualTo(403);
	}

	@Test
	void studentWhoLeftTheSchoolLosesAccess() throws Exception {
		Msg m = school("acc-left-school");
		UUID classThread = classConversation(m);
		UUID thread = teacherStudentThread(m);
		send(m.lead(), thread, "hello Abel");
		withdraw(m.schoolId(), m.s1());
		assertGone(thread, m.student1(), m.lead(), m.g1(), m.g1b());
		assertThat(statusOf(HttpMethod.GET, conv(classThread), m.student1(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, conv(classThread), m.student2(), null)).isEqualTo(200);
	}

	@Test
	void studentWhoLeftTheClassLosesTheClassConversation() throws Exception {
		Msg m = school("acc-left-class");
		UUID classThread = classConversation(m);
		send(m.student1(), classThread, "hi class");
		leaveClass(m.classId(), m.s1());
		// the membership row is still there (no event ran), yet there is no access
		assertThat(this.jdbc.queryForObject("select count(*) from conversation_members where conversation_id = ? and user_id = ? and left_at is null",
				Integer.class, classThread, m.s1())).isEqualTo(1);
		assertGone(classThread, m.student1());
		assertThat(bodies(messagesOf(m.student2(), classThread))).containsExactly("hi class");
	}

	@Test
	void removedTeacherLosesAccess() throws Exception {
		Msg m = school("acc-teacher");
		UUID thread = parentTeacherThread(m);
		UUID classThread = classConversation(m);
		this.jdbc.update("update class_teachers set status = 'FORMER', left_at = now() where teacher_id = ? and class_id = ?",
				m.lead().userId(), m.classId());
		assertGone(thread, m.lead(), m.g1());
		assertThat(statusOf(HttpMethod.GET, conv(classThread), m.lead(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, conv(classThread), m.co(), null)).isEqualTo(200);
		// a staff row that ended does the same
		UUID coThread = conversationId(startDirect(m.g1(), m, m.co().userId(), m.s1(), 201));
		this.jdbc.update("update school_staff set status = 'FORMER' where user_id = ? and school_id = ?", m.co().userId(), m.schoolId());
		assertGone(coThread, m.co(), m.g1());
	}

	@Test
	void suspendedSchoolBlocksEverything() throws Exception {
		Msg m = school("acc-suspended");
		UUID thread = parentTeacherThread(m);
		UUID classThread = classConversation(m);
		this.jdbc.update("update schools set status = 'SUSPENDED' where id = ?", m.schoolId());
		assertGone(thread, m.g1(), m.lead());
		assertThat(statusOf(HttpMethod.GET, conv(classThread), m.student1(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, conv(classThread), m.lead(), null)).isEqualTo(404);
		assertThat(inboxIds(m.student1())).isEmpty();
		this.jdbc.update("update schools set status = 'ACTIVE' where id = ?", m.schoolId());
		assertThat(statusOf(HttpMethod.GET, conv(thread), m.g1(), null)).isEqualTo(200);
	}

	@Test
	void deactivatedAccountLosesAccess() throws Exception {
		Msg m = school("acc-deactivated");
		UUID thread = parentTeacherThread(m);
		this.jdbc.update("update users set status = 'SUSPENDED' where id = ?", m.g1().userId());
		assertThat(statusOf(HttpMethod.GET, conv(thread), m.g1(), null)).isIn(401, 404);
		// the other person cannot use the conversation either
		assertGone(thread, m.lead());
		this.jdbc.update("update users set status = 'ACTIVE' where id = ?", m.g1().userId());
		assertThat(statusOf(HttpMethod.GET, conv(thread), m.lead(), null)).isEqualTo(200);
	}

	@Test
	void staleMembershipRowNeverGrantsAccess() throws Exception {
		Msg m = school("acc-stale");
		UUID classThread = classConversation(m);
		send(m.lead(), classThread, "class only");
		// rows for people who have no business there, as a missed event or a bug might leave them
		for (Object[] row : new Object[][] { { m.stranger(), "TEACHER" }, { m.g2(), "GUARDIAN" }, { m.otherStudent(), "STUDENT" },
				{ m.otherTeacher(), "TEACHER" }, { m.assistant(), "TEACHER" }, { m.admin(), "STAFF" } }) {
			Persona who = (Persona) row[0];
			this.jdbc.update("""
					insert into conversation_members (id, conversation_id, school_id, user_id, member_role, acting_as)
					values (uuidv7(), ?, ?, ?, 'MODERATOR', ?)""", classThread, m.schoolId(), who.userId(), row[1]);
			assertGone(classThread, who);
		}
		// and they are not in anybody's member list
		assertThat(get(conv(classThread) + "/members", m.lead()).get("members").size()).isGreaterThan(0);
	}

	@Test
	void studentMessagingSwitchedOffBlocksStudents() throws Exception {
		Msg m = school("acc-students-off");
		UUID classThread = classConversation(m);
		send(m.student1(), classThread, "before");
		setting(m.schoolId(), "messaging.student.enabled", false);
		assertGone(classThread, m.student1(), m.student2());
		assertThat(statusOf(HttpMethod.GET, conv(classThread), m.lead(), null)).isEqualTo(200);
		assertThat(code(startDirect(m.lead(), m, m.s1(), null, 403))).isEqualTo("messaging.not_allowed");
	}

	@Test
	void studentMinimumAgeIsComputedFromTheDateOfBirth() throws Exception {
		Msg m = school("acc-age");
		UUID classThread = classConversation(m);
		setting(m.schoolId(), "messaging.student.min_age", 10);
		// an unknown date of birth does not pass a limit
		assertGone(classThread, m.student1());
		this.personas.setDateOfBirth(m.s1(), LocalDate.now().minusYears(12));
		this.personas.setDateOfBirth(m.s2(), LocalDate.now().minusYears(8));
		assertThat(statusOf(HttpMethod.GET, conv(classThread), m.student1(), null)).isEqualTo(200);
		assertThat(statusOf(HttpMethod.GET, conv(classThread), m.student2(), null)).isEqualTo(404);
		// the date of birth is never in any payload
		String dob = LocalDate.now().minusYears(12).toString();
		String everything = get(conv(classThread), m.student1()).toString() + get(conv(classThread) + "/members", m.student1())
			+ get(conv(classThread) + "/members", m.lead()) + inbox(m.student1()) + messagesOf(m.student1(), classThread);
		assertThat(everything).doesNotContain(dob).doesNotContain("dateOfBirth").doesNotContain("birth");
		setting(m.schoolId(), "messaging.student.min_age", 13);
		assertGone(classThread, m.student1());
	}

	@Test
	void featureFlagOffAnswers403() throws Exception {
		Msg m = school("acc-flag-off");
		UUID thread = parentTeacherThread(m);
		this.jdbc.update("insert into feature_overrides (feature_key, scope_type, school_id, enabled) values ('messaging', 'SCHOOL', ?, false)",
				m.schoolId());
		try {
			this.caches.getCache("school-configuration").clear();
			assertThat(code(call(HttpMethod.GET, conv(thread), m.g1(), null, 403))).isEqualTo("messaging.feature_disabled");
			assertThat(code(call(HttpMethod.GET, conv(thread) + "/messages", m.lead(), null, 403))).isEqualTo("messaging.feature_disabled");
			assertThat(code(call(HttpMethod.GET, m.api() + "/capabilities", m.g1(), null, 403))).isEqualTo("messaging.feature_disabled");
			assertThat(code(startDirect(m.g1(), m, m.co().userId(), m.s1(), 403))).isEqualTo("messaging.feature_disabled");
			assertThat(inboxIds(m.g1())).isEmpty();
			// a stranger is told the conversation does not exist, not that the feature is off
			assertThat(statusOf(HttpMethod.GET, conv(thread), m.stranger(), null)).isEqualTo(404);
			// the audited review of safeguarding stays available
			assertThat(statusOf(HttpMethod.GET, m.api() + "/review/conversations", m.admin(), null)).isEqualTo(200);
		}
		finally {
			this.jdbc.update("delete from feature_overrides where school_id = ?", m.schoolId());
			this.caches.getCache("school-configuration").clear();
		}
		assertThat(statusOf(HttpMethod.GET, conv(thread), m.g1(), null)).isEqualTo(200);
	}

	@Test
	void deadDirectConversationIsUnusableForBothSides() throws Exception {
		Msg m = school("acc-dead");
		UUID thread = teacherStudentThread(m);
		send(m.lead(), thread, "alive");
		setting(m.schoolId(), "messaging.student.teacher_direct.enabled", false);
		assertGone(thread, m.lead(), m.student1(), m.g1(), m.g1b());
		// a dead safeguarded conversation has no eligible observer: reviewers can still read it
		JsonNode list = get(m.api() + "/review/conversations", m.admin());
		assertThat(list.get("items")).hasSize(1);
		JsonNode page = post(m.api() + "/review/conversations/" + thread + "/read", m.admin(),
				body("reason", "dead thread, checking contents"), 200);
		assertThat(page.get("messages")).hasSize(1);
		// switched on again it works as before
		setting(m.schoolId(), "messaging.student.teacher_direct.enabled", true);
		assertThat(bodies(messagesOf(m.student1(), thread))).containsExactly("alive");
	}

	@Test
	void assistantTeacherIsNotAParticipant() throws Exception {
		Msg m = school("acc-assistant");
		UUID classThread = classConversation(m);
		UUID parents = parentGroup(m);
		assertThat(this.jdbc.queryForObject("select count(*) from conversation_members where conversation_id = ? and user_id = ?",
				Integer.class, classThread, m.assistant().userId())).isZero();
		assertThat(statusOf(HttpMethod.GET, conv(classThread), m.assistant(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, conv(parents), m.assistant(), null)).isEqualTo(404);
		// an assistant is school staff but cannot lead a class conversation
		assertThat(code(post(m.api() + "/groups", m.assistant(), body("kind", "CLASS_PARENTS", "classId", m.classId()), 403)))
			.isEqualTo("messaging.group_not_allowed");
	}

	@Test
	void refusalsDoNotRevealTheReason() throws Exception {
		Msg m = school("acc-generic");
		setting(m.schoolId(), "messaging.parent_parent.enabled", false);
		String blockedReason = code(startDirect(m.g1(), m, m.g2().userId(), m.s1(), 403)); // setting off
		String unrelated = code(startDirect(m.g1(), m, m.stranger().userId(), m.s1(), 403)); // not a teacher
		// a restricted guardian is a stranger to the school: the same 404 as any outsider, no word about the restriction
		assertThat(code(startDirect(m.restricted(), m, m.lead().userId(), m.s1(), 404))).isEqualTo(code(startDirect(m.stranger(), m, m.lead().userId(), m.s1(), 404)))
			.isEqualTo("school.not_found");
		String assistant = code(startDirect(m.g1(), m, m.assistant().userId(), m.s1(), 403)); // not a participant role
		String unknown = code(startDirect(m.g1(), m, UUID.randomUUID(), m.s1(), 403)); // unknown person
		assertThat(java.util.stream.Stream.of(blockedReason, unrelated, assistant, unknown).distinct().toList())
			.containsExactly("messaging.not_allowed");
		// the problem document carries nothing else about why
		JsonNode problem = startDirect(m.g1(), m, m.assistant().userId(), m.s1(), 403);
		assertThat(problem.toString()).doesNotContain("restrict").doesNotContain("custody").doesNotContain("block");
		// a conversation of somebody else looks like one that does not exist
		UUID thread = parentTeacherThread(m);
		JsonNode missing = call(HttpMethod.GET, conv(UUID.randomUUID()), m.g1(), null, 404);
		JsonNode notYours = call(HttpMethod.GET, conv(thread), m.stranger(), null, 404);
		assertThat(code(missing)).isEqualTo(code(notYours)).isEqualTo("messaging.conversation_not_found");
	}

}
