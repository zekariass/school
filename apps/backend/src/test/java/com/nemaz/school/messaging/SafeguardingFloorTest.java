package com.nemaz.school.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.messaging.application.Safeguarding;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Rule book R5: the safeguarding floor of adult-student DIRECT conversations cannot be switched off. */
@IntegrationTest
class SafeguardingFloorTest extends MessagingTestSupport {

	@Autowired
	Safeguarding safeguarding;

	@Test
	void observersAreAddedAndNotified() throws Exception {
		Msg m = school("floor-obs");
		UUID thread = teacherStudentThread(m);
		// the student's ACTIVE, unrestricted guardians observe; restricted, revoked and pending ones do not
		assertThat(observers(thread)).containsExactlyInAnyOrder(m.g1().userId(), m.g1b().userId());
		assertThat(adminVisible(thread)).isFalse();
		assertThat(notificationsOf(m.g1(), "MESSAGE_OBSERVER_ADDED")).hasSize(1);
		assertThat(notificationsOf(m.g1b(), "MESSAGE_OBSERVER_ADDED")).hasSize(1);
		assertThat(notificationsOf(m.restricted(), "MESSAGE_OBSERVER_ADDED")).isEmpty();
		assertThat(notificationsOf(m.revoked(), "MESSAGE_OBSERVER_ADDED")).isEmpty();
		assertThat(notificationsOf(m.g2(), "MESSAGE_OBSERVER_ADDED")).isEmpty();
		// the notice is generic: no child, no teacher, no text
		Object body = notificationsOf(m.g1(), "MESSAGE_OBSERVER_ADDED").getFirst();
		assertThat(body.toString()).doesNotContain("Abel").doesNotContain("floor-obs-lead");
		// the observer sees the conversation, as an observer, and others see that observers exist
		JsonNode asObserver = get(conv(thread), m.g1());
		assertThat(asObserver.get("summary").get("myRole").asString()).isEqualTo("OBSERVER");
		assertThat(asObserver.get("summary").get("contextStudent").get("studentId").asString()).isEqualTo(m.s1().toString());
		assertThat(get(conv(thread) + "/members", m.lead()).get("observerCount").asInt()).isEqualTo(2);
		// an unrelated guardian cannot see it
		assertThat(statusOf(HttpMethod.GET, conv(thread), m.g2(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, conv(thread), m.restricted(), null)).isEqualTo(404);
	}

	@Test
	void studentIsToldGuardiansCanSee() throws Exception {
		Msg m = school("floor-told");
		UUID thread = teacherStudentThread(m);
		assertThat(get(conv(thread), m.student1()).get("summary").get("guardiansCanSee").asBoolean()).isTrue();
		assertThat(get(conv(thread), m.lead()).get("summary").get("guardiansCanSee").asBoolean()).isTrue();
		// a student with no eligible guardian is told the opposite
		UUID alone = conversationId(startDirect(m.lead(), m, m.s3(), null, 201));
		assertThat(get(conv(alone), m.student3()).get("summary").get("guardiansCanSee").asBoolean()).isFalse();
		// a conversation that is not about a student says nothing
		UUID parentThread = parentTeacherThread(m);
		assertThat(get(conv(parentThread), m.g1()).get("summary").has("guardiansCanSee")
				&& !get(conv(parentThread), m.g1()).get("summary").get("guardiansCanSee").isNull()).isFalse();
	}

	@Test
	void noGuardianMakesTheConversationAdminVisible() throws Exception {
		Msg m = school("floor-none");
		allowTeacherStudentDirect(m.schoolId(), "TEACHER_ONLY");
		UUID thread = conversationId(startDirect(m.lead(), m, m.s3(), null, 201));
		assertThat(observers(thread)).isEmpty();
		assertThat(adminVisible(thread)).isTrue();
		// reviewers (here the school's administrator) see it in the review list, through the audited endpoint only
		JsonNode list = get(m.api() + "/review/conversations", m.admin());
		assertThat(list.get("items")).hasSize(1);
		assertThat(list.get("items").get(0).get("adminVisible").asBoolean()).isTrue();
		// the administrator is not a member and cannot read it as one
		assertThat(statusOf(HttpMethod.GET, conv(thread) + "/messages", m.admin(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, conv(thread), m.admin(), null)).isEqualTo(404);
	}

	@Test
	void adminsAreNeverAddedAsMembers() throws Exception {
		Msg m = school("floor-admin");
		allowTeacherStudentDirect(m.schoolId(), "TEACHER_ONLY");
		UUID thread = conversationId(startDirect(m.lead(), m, m.s3(), null, 201));
		List<UUID> members = this.jdbc.queryForList("select user_id from conversation_members where conversation_id = ?", UUID.class,
				thread);
		assertThat(members).containsExactlyInAnyOrder(m.lead().userId(), m.s3());
		assertThat(members).doesNotContain(m.admin().userId(), m.staff().userId());
		// reading it as a reviewer changes nothing about the members
		post(m.api() + "/review/conversations/" + thread + "/read", m.admin(), body("reason", "weekly safeguarding check"), 200);
		assertThat(this.jdbc.queryForObject("select count(*) from conversation_members where conversation_id = ?", Integer.class, thread))
			.isEqualTo(2);
	}

	@Test
	void teacherWhoIsTheOnlyGuardianCannotObserveHerself() throws Exception {
		Msg m = school("floor-self");
		allowTeacherStudentDirect(m.schoolId(), "TEACHER_ONLY");
		// the co-teacher is the only guardian of Chala
		this.personas.linkGuardian(m.s3(), m.co().userId(), "ACTIVE", false);
		UUID own = conversationId(startDirect(m.co(), m, m.s3(), null, 201));
		assertThat(observers(own)).isEmpty();
		assertThat(adminVisible(own)).isTrue();
		// another teacher's conversation with Chala is observed by her guardian, the co-teacher
		UUID other = conversationId(startDirect(m.lead(), m, m.s3(), null, 201));
		assertThat(observers(other)).containsExactly(m.co().userId());
		assertThat(adminVisible(other)).isFalse();
	}

	@Test
	void restrictedGuardianStopsObservingImmediately() throws Exception {
		Msg m = school("floor-restrict");
		UUID thread = teacherStudentThread(m);
		send(m.lead(), thread, "see me after class");
		restrict(m.g1Link());
		// at once, before any cleanup ran
		assertThat(statusOf(HttpMethod.GET, conv(thread), m.g1(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, conv(thread) + "/messages", m.g1(), null)).isEqualTo(404);
		assertThat(inboxIds(m.g1())).doesNotContain(thread);
		// the other guardian still observes; the conversation works for its participants
		assertThat(statusOf(HttpMethod.GET, conv(thread), m.g1b(), null)).isEqualTo(200);
		send(m.student1(), thread, "ok");
		// the cleanup ends the observer row and keeps the other one
		this.safeguarding.reconcile(thread);
		assertThat(observers(thread)).containsExactly(m.g1b().userId());
		assertThat(adminVisible(thread)).isFalse();
	}

	@Test
	void lastObserverLostMakesItAdminVisible() throws Exception {
		Msg m = school("floor-last");
		UUID thread = teacherStudentThread(m);
		revoke(linkOf(m.g1(), m.s1()));
		restrict(linkOf(m.g1b(), m.s1()));
		// live, before the cleanup: no eligible observer, so the review list has it
		JsonNode list = get(m.api() + "/review/conversations", m.admin());
		assertThat(list.get("items")).hasSize(1);
		assertThat(list.get("items").get(0).get("adminVisible").asBoolean()).isTrue();
		assertThat(statusOf(HttpMethod.GET, conv(thread), m.g1(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, conv(thread), m.g1b(), null)).isEqualTo(404);
		this.safeguarding.reconcile(thread);
		assertThat(observers(thread)).isEmpty();
		assertThat(adminVisible(thread)).isTrue();
	}

	@Test
	void guardianActivatedLaterBecomesObserver() throws Exception {
		Msg m = school("floor-later");
		allowTeacherStudentDirect(m.schoolId(), "TEACHER_ONLY");
		UUID thread = conversationId(startDirect(m.lead(), m, m.s3(), null, 201));
		assertThat(adminVisible(thread)).isTrue();
		Persona newGuardian = this.personas.newUser("floor-later-new");
		this.personas.linkGuardian(m.s3(), newGuardian.userId(), "ACTIVE", false);
		assertThat(statusOf(HttpMethod.GET, conv(thread), newGuardian, null)).isEqualTo(404);
		this.safeguarding.reconcileStudent(m.s3());
		this.safeguarding.reconcileStudent(m.s3());
		assertThat(observers(thread)).containsExactly(newGuardian.userId());
		assertThat(adminVisible(thread)).isFalse();
		assertThat(notificationsOf(newGuardian, "MESSAGE_OBSERVER_ADDED")).hasSize(1);
		assertThat(statusOf(HttpMethod.GET, conv(thread), newGuardian, null)).isEqualTo(200);
	}

	@Test
	void creationIsAuditedWithoutText() throws Exception {
		Msg m = school("floor-audit");
		UUID thread = teacherStudentThread(m);
		send(m.lead(), thread, "CANARY-FLOOR-TEXT");
		var rows = this.jdbc.queryForList("""
				select actor_user_id, school_id, metadata::text as metadata from audit_logs
				where action = 'messaging.conversation.create' and target_id = ?""", thread);
		assertThat(rows).hasSize(1);
		String metadata = (String) rows.getFirst().get("metadata");
		assertThat(rows.getFirst().get("actor_user_id")).isEqualTo(m.lead().userId());
		assertThat(metadata).contains("TEACHER_STUDENT").contains(m.s1().toString()).contains("\"safeguarded\": true");
		assertThat(metadata).doesNotContain("CANARY");
		// the observers added are audited too (ids and counts)
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'messaging.safeguarding.reconcile' and target_id = ?",
				Integer.class, thread)).isEqualTo(1);
	}

	@Test
	void observersAreReadOnly() throws Exception {
		Msg m = school("floor-readonly");
		UUID thread = teacherStudentThread(m);
		UUID message = send(m.lead(), thread, "homework");
		assertThat(bodies(messagesOf(m.g1(), thread))).containsExactly("homework");
		assertThat(code(post(conv(thread) + "/messages", m.g1(), body("clientMessageId", UUID.randomUUID(), "body", "hi"), 403)))
			.isEqualTo("messaging.observer_read_only");
		assertThat(code(call(HttpMethod.PUT, conv(thread) + "/messages/" + message + "/reaction", m.g1(),
				body("emoji", "👍"), 403))).isEqualTo("messaging.observer_read_only");
		assertThat(code(call(HttpMethod.DELETE, conv(thread) + "/messages/" + message, m.g1(), null, 403)))
			.isEqualTo("messaging.observer_read_only");
		assertThat(code(call(HttpMethod.PATCH, conv(thread) + "/messages/" + message, m.g1(), body("body", "x"), 403)))
			.isEqualTo("messaging.observer_read_only");
		// observers cannot block (they are not a side), cannot start sub-moderation and cannot be removed
		UUID leadMember = memberIdOf(m.g1(), thread, m.lead());
		assertThat(code(post("/api/v1/me/blocks", m.g1(), body("conversationId", thread, "memberId", leadMember), 403)))
			.isEqualTo("messaging.not_allowed");
		assertThat(statusOf(HttpMethod.POST, conv(thread) + "/members/" + leadMember + "/remove", m.g1(), body("reason", "x"))).isEqualTo(403);
		// but they may report
		post(conv(thread) + "/messages/" + message + "/report", m.g1(), body("reason", "SAFETY"), 204);
	}

	@Test
	void everySettingsCombinationKeepsTheFloor() throws Exception {
		boolean[] bools = { true, false };
		String[] initiators = { "TEACHER_ONLY", "EITHER" };
		Integer[] ages = { null, 10 };
		int created = 0;
		int refused = 0;
		List<String> problems = new ArrayList<>();
		for (boolean teacherDirect : bools) {
			for (String initiator : initiators) {
				for (boolean studentsEnabled : bools) {
					for (boolean parentTeacher : bools) {
						for (boolean parentParent : bools) {
							for (boolean studentDirect : bools) {
								for (Integer age : ages) {
									Msg m = school("floor-combo");
									setting(m.schoolId(), "messaging.student.teacher_direct.enabled", teacherDirect);
									setting(m.schoolId(), "messaging.student.teacher_direct.initiator", initiator);
									setting(m.schoolId(), "messaging.student.enabled", studentsEnabled);
									setting(m.schoolId(), "messaging.parent_teacher.enabled", parentTeacher);
									setting(m.schoolId(), "messaging.parent_parent.enabled", parentParent);
									setting(m.schoolId(), "messaging.student.student_direct.enabled", studentDirect);
									if (age != null) {
										setting(m.schoolId(), "messaging.student.min_age", age);
									}
									boolean expected = teacherDirect && studentsEnabled && age == null;
									String label = "td=" + teacherDirect + " init=" + initiator + " stu=" + studentsEnabled + " pt="
											+ parentTeacher + " pp=" + parentParent + " sd=" + studentDirect + " age=" + age;
									// a student tries first (only with EITHER), then the teacher
									int studentStatus = statusOf(HttpMethod.POST, m.api() + "/conversations/direct", m.student1(),
											body("recipientUserId", m.lead().userId()));
									int teacherStatus = statusOf(HttpMethod.POST, m.api() + "/conversations/direct", m.lead(),
											body("recipientUserId", m.s1()));
									boolean studentMayStart = expected && "EITHER".equals(initiator);
									if (studentMayStart != (studentStatus == 201) || (!studentMayStart && studentStatus != 403)) {
										problems.add(label + ": student got " + studentStatus);
									}
									boolean teacherOk = teacherStatus == 201 || teacherStatus == 200;
									if (expected != teacherOk || (!expected && teacherStatus != 403)) {
										problems.add(label + ": teacher got " + teacherStatus);
									}
									// every adult-student conversation that exists has the floor: observers or admin-visible, never neither
									var rows = this.jdbc.queryForList("""
											select id, admin_visible, safeguarded from conversations where school_id = ? and direct_kind = 'TEACHER_STUDENT'""",
											m.schoolId());
									for (var row : rows) {
										UUID id = (UUID) row.get("id");
										int observerCount = observers(id).size();
										if (!Boolean.TRUE.equals(row.get("safeguarded")) || observerCount != 2 || Boolean.TRUE.equals(row.get("admin_visible"))) {
											problems.add(label + ": conversation without the floor " + row);
										}
										created++;
									}
									if (rows.isEmpty()) {
										refused++;
									}
									// no other path produced an unobserved adult-student conversation
									Integer others = this.jdbc.queryForObject("""
											select count(*) from conversations c where c.school_id = ? and c.conversation_type = 'DIRECT'
											  and c.direct_kind <> 'TEACHER_STUDENT'""", Integer.class, m.schoolId());
									if (others != 0) {
										problems.add(label + ": unexpected direct conversation");
									}
								}
							}
						}
					}
				}
			}
		}
		assertThat(problems).isEmpty();
		assertThat(created).isGreaterThan(0);
		assertThat(refused).isGreaterThan(0);
	}

}
