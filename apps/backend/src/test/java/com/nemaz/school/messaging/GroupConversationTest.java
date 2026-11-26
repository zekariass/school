package com.nemaz.school.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.messaging.application.RosterSync;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Rule book R6: groups, who creates and moderates them, student groups with approval. */
@IntegrationTest
class GroupConversationTest extends MessagingTestSupport {

	@Autowired
	RosterSync roster;

	private Set<UUID> activeMembers(UUID conversationId) {
		return Set.copyOf(this.jdbc.queryForList("select user_id from conversation_members where conversation_id = ? and left_at is null",
				UUID.class, conversationId));
	}

	private String roleOf(UUID conversationId, Persona who) {
		return this.jdbc.queryForObject("select member_role from conversation_members where conversation_id = ? and user_id = ? and left_at is null",
				String.class, conversationId, who.userId());
	}

	@Test
	void classConversationIsCreatedByLeadAndFollowsTheRoster() throws Exception {
		Msg m = school("grp-class");
		UUID thread = classConversation(m);
		assertThat(activeMembers(thread)).containsExactlyInAnyOrder(m.s1(), m.s2(), m.s3(), m.lead().userId(), m.co().userId(),
				m.subject().userId());
		assertThat(roleOf(thread, m.lead())).isEqualTo("OWNER");
		assertThat(roleOf(thread, m.co())).isEqualTo("MODERATOR");
		assertThat(roleOf(thread, m.subject())).isEqualTo("MEMBER");
		assertThat(roleOf(thread, m.student1())).isEqualTo("MEMBER");
		// everyone of the roster sees it in the inbox; an assistant, another class and guardians do not
		for (Persona who : new Persona[] { m.student1(), m.student2(), m.student3(), m.co(), m.subject() }) {
			assertThat(inboxIds(who)).contains(thread);
		}
		for (Persona who : new Persona[] { m.assistant(), m.otherTeacher(), m.otherStudent(), m.g1(), m.stranger() }) {
			assertThat(inboxIds(who)).doesNotContain(thread);
			assertThat(statusOf(HttpMethod.GET, conv(thread), who, null)).isEqualTo(404);
		}
		// one per class; others cannot create it
		assertThat(code(post(m.api() + "/groups", m.co(), body("kind", "CLASS_STUDENTS", "classId", m.classId()), 409))).isEqualTo("messaging.group_exists");
		assertThat(code(post(m.api() + "/groups", m.subject(), body("kind", "CLASS_PARENTS", "classId", m.classId()), 403))).isEqualTo("messaging.group_not_allowed");
		assertThat(code(post(m.api() + "/groups", m.student1(), body("kind", "CLASS_STUDENTS", "classId", m.classId()), 403))).isEqualTo("messaging.group_not_allowed");
		assertThat(code(post(m.api() + "/groups", m.otherTeacher(), body("kind", "CLASS_PARENTS", "classId", m.classId()), 404))).isEqualTo("class.not_found");
		assertThat(code(post(m.api() + "/groups", m.lead(), body("kind", "CLASS_PARENTS"), 400))).isEqualTo("messaging.class_required");
		// the class manager (a school administrator) may create the parent group without being a member of it
		JsonNode byAdmin = post(m.api() + "/groups", m.admin(), body("kind", "CLASS_PARENTS", "classId", m.classId()), 201);
		assertThat(activeMembers(UUID.fromString(byAdmin.get("id").asString()))).doesNotContain(m.admin().userId());
	}

	@Test
	void parentGroupHasGuardiansAndTeachersOnly() throws Exception {
		Msg m = school("grp-parents");
		UUID parents = parentGroup(m);
		assertThat(activeMembers(parents)).containsExactlyInAnyOrder(m.g1().userId(), m.g1b().userId(), m.g2().userId(), m.lead().userId(),
				m.co().userId(), m.subject().userId());
		// restricted, revoked and pending guardians, students and other classes' guardians are not in
		for (Persona out : new Persona[] { m.restricted(), m.revoked(), m.pending(), m.student1(), m.otherGuardian(), m.assistant() }) {
			assertThat(statusOf(HttpMethod.GET, conv(parents), out, null)).as(out.name()).isEqualTo(404);
		}
		send(m.g1(), parents, "Does anyone have the page numbers?");
		assertThat(bodies(messagesOf(m.g2(), parents))).containsExactly("Does anyone have the page numbers?");
		// switching parent-teacher messaging off closes it
		setting(m.schoolId(), "messaging.parent_teacher.enabled", false);
		assertThat(statusOf(HttpMethod.GET, conv(parents), m.g1(), null)).isEqualTo(404);
	}

	@Test
	void creationRolesComeFromTheSetting() throws Exception {
		Msg m = school("grp-roles");
		setting(m.schoolId(), "messaging.group_create.roles", List.of("SCHOOL_ADMIN"));
		assertThat(code(post(m.api() + "/groups", m.lead(), body("kind", "CLASS_STUDENTS", "classId", m.classId()), 403))).isEqualTo("messaging.group_not_allowed");
		assertThat(code(post(m.api() + "/groups", m.lead(), body("kind", "STAFF", "title", "Staff room"), 403))).isEqualTo("messaging.group_not_allowed");
		post(m.api() + "/groups", m.admin(), body("kind", "CLASS_STUDENTS", "classId", m.classId()), 201);
		setting(m.schoolId(), "messaging.group_create.roles", List.of("TEACHER"));
		post(m.api() + "/groups", m.lead(), body("kind", "STAFF", "title", "Staff room", "memberUserIds", List.of(m.co().userId())), 201);
		// non-teaching staff are not in either role; an assistant is a teacher of the school
		assertThat(code(post(m.api() + "/groups", m.staff(), body("kind", "STAFF", "title", "Nope"), 403))).isEqualTo("messaging.group_not_allowed");
		setting(m.schoolId(), "messaging.group_create.roles", List.of());
		assertThat(code(post(m.api() + "/groups", m.admin(), body("kind", "STAFF", "title", "Nope"), 403))).isEqualTo("messaging.group_not_allowed");
		assertThat(code(post(m.api() + "/groups", m.lead(), body("kind", "STAFF", "title", "Nope"), 403))).isEqualTo("messaging.group_not_allowed");
	}

	@Test
	void studentsCannotCreateGroupsByDefault() throws Exception {
		Msg m = school("grp-students");
		for (String kind : new String[] { "STAFF", "SCHOOL_STAFF" }) {
			assertThat(code(post(m.api() + "/groups", m.student1(), body("kind", kind, "title", "x"), 403))).isEqualTo("messaging.group_not_allowed");
		}
		assertThat(code(post(m.api() + "/groups", m.student1(), body("kind", "STUDENT_GROUP", "classId", m.classId(), "title", "Study club",
				"memberUserIds", List.of(m.s2())), 403))).isEqualTo("messaging.group_not_allowed");
		assertThat(code(post(m.api() + "/groups", m.g1(), body("kind", "STAFF", "title", "x"), 403))).isEqualTo("messaging.group_not_allowed");
	}

	@Test
	void studentGroupNeedsApproval() throws Exception {
		Msg m = school("grp-approval");
		setting(m.schoolId(), "messaging.student.group_create", "STUDENTS_WITH_APPROVAL");
		JsonNode proposal = post(m.api() + "/groups", m.student1(), body("kind", "STUDENT_GROUP", "classId", m.classId(), "title", "Maths club",
				"memberUserIds", List.of(m.s2(), m.s3())), 201);
		UUID group = UUID.fromString(proposal.get("id").asString());
		assertThat(proposal.get("status").asString()).isEqualTo("PENDING_APPROVAL");
		assertThat(proposal.get("proposedByMe").asBoolean()).isTrue();
		// nobody can use it yet
		for (Persona who : new Persona[] { m.student1(), m.student2(), m.lead() }) {
			assertThat(statusOf(HttpMethod.GET, conv(group), who, null)).isEqualTo(404);
		}
		assertThat(code(send(m.student1(), group, "first!", 404))).isEqualTo("messaging.conversation_not_found");
		// the class's lead and co-teachers are asked, nobody else
		awaitNotification(m.lead(), "GROUP_APPROVAL_REQUESTED");
		awaitNotification(m.co(), "GROUP_APPROVAL_REQUESTED");
		assertThat(notificationsOf(m.subject(), "GROUP_APPROVAL_REQUESTED")).isEmpty();
		assertThat(notificationsOf(m.assistant(), "GROUP_APPROVAL_REQUESTED")).isEmpty();
		assertThat(get(m.api() + "/groups?status=PENDING_APPROVAL", m.lead())).hasSize(1);
		assertThat(get(m.api() + "/groups?status=PENDING_APPROVAL", m.student1())).hasSize(1);
		assertThat(get(m.api() + "/groups?status=PENDING_APPROVAL", m.student2())).isEmpty();
		// who may decide
		assertThat(statusOf(HttpMethod.POST, conv(group) + "/approve", m.subject(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.POST, conv(group) + "/approve", m.student1(), null)).isEqualTo(403);
		assertThat(statusOf(HttpMethod.POST, conv(group) + "/approve", m.stranger(), null)).isEqualTo(404);
		JsonNode approved = call(HttpMethod.POST, conv(group) + "/approve", m.lead(), null, 200);
		assertThat(approved.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(activeMembers(group)).containsExactlyInAnyOrder(m.s1(), m.s2(), m.s3(), m.lead().userId(), m.co().userId());
		assertThat(roleOf(group, m.lead())).isEqualTo("OWNER");
		assertThat(roleOf(group, m.co())).isEqualTo("MODERATOR");
		send(m.student2(), group, "count me in");
		assertThat(bodies(messagesOf(m.lead(), group))).containsExactly("count me in");
		assertThat(statusOf(HttpMethod.POST, conv(group) + "/approve", m.lead(), null)).isEqualTo(409);
		// a student cannot propose a group for a class they are not in, nor with classmates of another class
		assertThat(code(post(m.api() + "/groups", m.student1(), body("kind", "STUDENT_GROUP", "classId", m.otherClassId(), "title", "x"), 404)))
			.isEqualTo("class.not_found");
		assertThat(code(post(m.api() + "/groups", m.student1(), body("kind", "STUDENT_GROUP", "classId", m.classId(), "title", "x",
				"memberUserIds", List.of(m.o1())), 400))).isEqualTo("messaging.member_not_eligible");
		// a proposal can be declined
		JsonNode second = post(m.api() + "/groups", m.student2(), body("kind", "STUDENT_GROUP", "classId", m.classId(), "title", "Secret",
				"memberUserIds", List.of(m.s1())), 201);
		UUID declined = UUID.fromString(second.get("id").asString());
		assertThat(call(HttpMethod.POST, conv(declined) + "/reject", m.co(), null, 200).get("status").asString()).isEqualTo("REJECTED");
		assertThat(statusOf(HttpMethod.GET, conv(declined), m.student2(), null)).isEqualTo(404);
	}

	@Test
	void groupsWithStudentsNeedAtLeastTwoStudents() throws Exception {
		Msg m = school("grp-two");
		// a teacher and one student would be a private conversation without the safeguarding floor
		assertThat(code(post(m.api() + "/groups", m.lead(), body("kind", "STUDENT_GROUP", "classId", m.classId(), "title", "Tutoring",
				"memberUserIds", List.of(m.s1())), 400))).isEqualTo("messaging.group_too_small");
		JsonNode two = post(m.api() + "/groups", m.lead(), body("kind", "STUDENT_GROUP", "classId", m.classId(), "title", "Tutoring",
				"memberUserIds", List.of(m.s1(), m.s2())), 201);
		UUID group = UUID.fromString(two.get("id").asString());
		assertThat(statusOf(HttpMethod.GET, conv(group), m.student1(), null)).isEqualTo(200);
		// and it cannot be reduced to one by removing a student
		UUID second = memberIdOf(m.lead(), group, m.student2());
		assertThat(code(call(HttpMethod.POST, conv(group) + "/members/" + second + "/remove", m.lead(), body("reason", "moved"), 409))).isEqualTo("messaging.group_too_small");
		// a roster change that leaves one student closes it
		leaveClass(m.classId(), m.s2());
		assertThat(statusOf(HttpMethod.GET, conv(group), m.lead(), null)).isEqualTo(404);
		// the same holds for a class with a single student
		UUID lonely = this.personas.newClass(m.schoolId(), "Lonely");
		UUID teacher = this.personas.newTeacher(m.schoolId(), "grp-two-solo");
		this.personas.addTeacherToClass(m.schoolId(), lonely, teacher, "LEAD", null);
		UUID only = this.personas.newStudent(m.schoolId(), "Only");
		this.personas.addStudentToClass(m.schoolId(), lonely, only);
		Persona soloTeacher = this.personas.personaFor("grp-two-solo", teacher);
		JsonNode created = post(m.api() + "/groups", soloTeacher, body("kind", "CLASS_STUDENTS", "classId", lonely), 201);
		assertThat(statusOf(HttpMethod.GET, conv(UUID.fromString(created.get("id").asString())), soloTeacher, null)).isEqualTo(404);
	}

	@Test
	void moderatorsManageMembersAndSettings() throws Exception {
		Msg m = school("grp-mod");
		JsonNode created = post(m.api() + "/groups", m.lead(), body("kind", "STAFF", "title", "Staff room", "memberUserIds",
				List.of(m.co().userId(), m.subject().userId())), 201);
		UUID group = UUID.fromString(created.get("id").asString());
		assertThat(activeMembers(group)).containsExactlyInAnyOrder(m.lead().userId(), m.co().userId(), m.subject().userId());
		// plain members cannot manage; moderators and school.messaging.moderate holders can
		assertThat(statusOf(HttpMethod.POST, conv(group) + "/members", m.co(), body("userIds", List.of(m.staff().userId())))).isEqualTo(403);
		assertThat(statusOf(HttpMethod.PATCH, conv(group), m.co(), body("title", "Mine"))).isEqualTo(403);
		call(HttpMethod.POST, conv(group) + "/members", m.lead(), body("userIds", List.of(m.staff().userId())), 200);
		assertThat(activeMembers(group)).contains(m.staff().userId());
		// not everybody can be a member of a staff group
		assertThat(code(call(HttpMethod.POST, conv(group) + "/members", m.admin(), body("userIds", List.of(m.s1())), 400))).isEqualTo("messaging.member_not_eligible");
		assertThat(code(call(HttpMethod.POST, conv(group) + "/members", m.admin(), body("userIds", List.of(m.g1().userId())), 400))).isEqualTo("messaging.member_not_eligible");
		assertThat(code(call(HttpMethod.POST, conv(group) + "/members", m.lead(), body("userIds", List.of(m.stranger().userId())), 400))).isEqualTo("messaging.member_not_eligible");
		assertThat(activeMembers(group)).doesNotContain(m.s1(), m.g1().userId());
		// the administrator moderates from outside: changes settings, locks, archives
		JsonNode updated = call(HttpMethod.PATCH, conv(group), m.admin(), body("title", "Teachers", "postingPolicy", "MODERATORS_ONLY"), 200);
		assertThat(updated.get("title").asString()).isEqualTo("Teachers");
		assertThat(updated.get("postingPolicy").asString()).isEqualTo("MODERATORS_ONLY");
		assertThat(code(send(m.co(), group, "me too", 403))).isEqualTo("messaging.posting_restricted");
		call(HttpMethod.PATCH, conv(group), m.lead(), body("status", "ARCHIVED"), 200);
		assertThat(code(send(m.lead(), group, "archived", 409))).isEqualTo("messaging.conversation_closed");
		assertThat(statusOf(HttpMethod.PATCH, conv(group), m.stranger(), body("title", "x"))).isEqualTo(404);
		assertThat(code(call(HttpMethod.PATCH, conv(group), m.lead(), body("status", "WEIRD"), 400))).isEqualTo("messaging.status_invalid");
		// a school administrator of another school has no say
		assertThat(statusOf(HttpMethod.PATCH, conv(group), m.otherAdmin(), body("title", "x"))).isEqualTo(404);
		// DIRECT conversations have no moderators
		UUID direct = parentTeacherThread(m);
		assertThat(statusOf(HttpMethod.PATCH, conv(direct), m.admin(), body("title", "x"))).isEqualTo(404);
		assertThat(statusOf(HttpMethod.PATCH, conv(direct), m.lead(), body("title", "x"))).isEqualTo(403);
	}

	@Test
	void nonMembersCannotReadAGroup() throws Exception {
		Msg m = school("grp-read");
		JsonNode created = post(m.api() + "/groups", m.lead(), body("kind", "STAFF", "title", "Staff room", "memberUserIds", List.of(m.co().userId())), 201);
		UUID group = UUID.fromString(created.get("id").asString());
		UUID message = send(m.co(), group, "private to staff");
		for (Persona out : new Persona[] { m.admin(), m.subject(), m.staff(), m.stranger(), m.otherAdmin(), m.student1() }) {
			assertThat(statusOf(HttpMethod.GET, conv(group), out, null)).as(out.name()).isEqualTo(404);
			assertThat(statusOf(HttpMethod.GET, conv(group) + "/messages", out, null)).as(out.name()).isEqualTo(404);
			assertThat(statusOf(HttpMethod.GET, conv(group) + "/messages/" + message, out, null)).as(out.name()).isEqualTo(404);
			assertThat(statusOf(HttpMethod.GET, conv(group) + "/members", out, null)).as(out.name()).isEqualTo(404);
		}
		// the administrator can hide a message by id without being able to read it
		call(HttpMethod.POST, conv(group) + "/messages/" + message + "/hide", m.admin(), body("reason", "reported to me"), 204);
		assertThat(statusOf(HttpMethod.GET, conv(group), m.admin(), null)).isEqualTo(404);
		assertThat(get(conv(group) + "/messages/" + message, m.co()).get("status").asString()).isEqualTo("HIDDEN");
		// the group list of the administrator shows metadata, never messages
		JsonNode list = get(m.api() + "/groups", m.admin());
		assertThat(list.toString()).contains("Staff room").doesNotContain("private to staff");
	}

	@Test
	void removalNeedsAReasonAndIsAudited() throws Exception {
		Msg m = school("grp-remove");
		JsonNode created = post(m.api() + "/groups", m.lead(), body("kind", "STAFF", "title", "Staff room", "memberUserIds",
				List.of(m.co().userId(), m.subject().userId())), 201);
		UUID group = UUID.fromString(created.get("id").asString());
		UUID coMember = memberIdOf(m.lead(), group, m.co());
		assertThat(code(call(HttpMethod.POST, conv(group) + "/members/" + coMember + "/remove", m.lead(), body("reason", " "), 400))).isEqualTo("messaging.reason_required");
		assertThat(statusOf(HttpMethod.POST, conv(group) + "/members/" + coMember + "/remove", m.subject(), body("reason", "x"))).isEqualTo(403);
		call(HttpMethod.POST, conv(group) + "/members/" + coMember + "/remove", m.lead(), body("reason", "left the staff team"), 200);
		assertThat(statusOf(HttpMethod.GET, conv(group), m.co(), null)).isEqualTo(404);
		var rows = this.jdbc.queryForList("select actor_user_id, metadata::text as metadata from audit_logs where action = 'messaging.member.remove' and target_id = ?", group);
		assertThat(rows).hasSize(1);
		assertThat(rows.getFirst().get("actor_user_id")).isEqualTo(m.lead().userId());
		assertThat((String) rows.getFirst().get("metadata")).contains("left the staff team").contains(m.co().userId().toString());
		assertThat(code(call(HttpMethod.POST, conv(group) + "/members/" + coMember + "/remove", m.lead(), body("reason", "again"), 404))).isEqualTo("messaging.member_not_found");
		// a member may leave
		call(HttpMethod.POST, conv(group) + "/leave", m.subject(), null, 204);
		assertThat(statusOf(HttpMethod.GET, conv(group), m.subject(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.POST, conv(parentTeacherThread(m)) + "/leave", m.g1(), null)).isEqualTo(403);
	}

	@Test
	void removedMemberIsNotAddedBackByTheRoster() throws Exception {
		Msg m = school("grp-sticky");
		UUID thread = classConversation(m);
		UUID chala = memberIdOf(m.lead(), thread, m.student3());
		call(HttpMethod.POST, conv(thread) + "/members/" + chala + "/remove", m.lead(), body("reason", "disruptive"), 200);
		assertThat(statusOf(HttpMethod.GET, conv(thread), m.student3(), null)).isEqualTo(404);
		this.roster.reconcileClass(m.classId());
		this.roster.reconcileClass(m.classId());
		assertThat(activeMembers(thread)).doesNotContain(m.s3());
		assertThat(statusOf(HttpMethod.POST, conv(thread) + "/members", m.lead(), body("userIds", List.of(m.s3())))).isEqualTo(409);
		assertThat(statusOf(HttpMethod.GET, conv(thread), m.student3(), null)).isEqualTo(404);
		// the others are untouched
		assertThat(activeMembers(thread)).contains(m.s1(), m.s2());
	}

	@Test
	void schoolConversationIsModeratorOnly() throws Exception {
		Msg m = school("grp-school");
		// an administrator who is also a member of staff can take part in what they create
		this.jdbc.update("insert into school_staff (id, school_id, user_id, staff_type, status) values (?, ?, ?, 'ADMINISTRATOR', 'ACTIVE')",
				Ids.newId(), m.schoolId(), m.admin().userId());
		assertThat(code(post(m.api() + "/groups", m.lead(), body("kind", "SCHOOL_STAFF", "title", "All staff"), 403))).isEqualTo("messaging.group_not_allowed");
		JsonNode created = post(m.api() + "/groups", m.admin(), body("kind", "SCHOOL_STAFF", "title", "All staff", "memberUserIds",
				List.of(m.lead().userId(), m.co().userId(), m.staff().userId())), 201);
		UUID group = UUID.fromString(created.get("id").asString());
		assertThat(created.get("postingPolicy").asString()).isEqualTo("MODERATORS_ONLY");
		assertThat(code(send(m.lead(), group, "may I?", 403))).isEqualTo("messaging.posting_restricted");
		send(m.admin(), group, "Staff meeting at 3");
		assertThat(bodies(messagesOf(m.staff(), group))).containsExactly("Staff meeting at 3");
		// students and guardians cannot be added to it
		assertThat(code(call(HttpMethod.POST, conv(group) + "/members", m.admin(), body("userIds", List.of(m.s1())), 400))).isEqualTo("messaging.member_not_eligible");
	}

	@Test
	void mixedGroupsShowGuardiansAsLabelsOnly() throws Exception {
		Msg m = school("grp-labels");
		UUID classThread = classConversation(m);
		UUID parents = parentGroup(m);
		// the class conversation has no guardians at all: students and teachers
		JsonNode asStudent = get(conv(classThread) + "/members", m.student1());
		for (JsonNode member : asStudent.get("members")) {
			assertThat(member.get("actingAs").asString()).isIn("TEACHER", "STUDENT");
		}
		assertThat(asStudent.toString()).doesNotContain("grp-labels-guardian").doesNotContain("userId");
		// in the parent group a guardian is named to themself, a label to the teacher, nothing to other guardians
		String phone = this.jdbc.queryForObject("select phone_e164 from users where id = ?", String.class, m.g1().userId());
		String asTeacher = get(conv(parents) + "/members", m.lead()).toString();
		JsonNode asOtherGuardian = get(conv(parents) + "/members", m.g2());
		String asSelf = get(conv(parents) + "/members", m.g1()).toString();
		assertThat(asTeacher).doesNotContain("grp-labels-guardian").doesNotContain(phone).contains("MOTHER").contains("Abel");
		for (JsonNode member : asOtherGuardian.get("members")) {
			if ("GUARDIAN".equals(member.get("actingAs").asString()) && !member.get("self").asBoolean()) {
				assertThat(member.toString()).doesNotContain("grp-labels-guardian").doesNotContain("MOTHER").doesNotContain("Abel").doesNotContain("Bethlehem");
			}
		}
		assertThat(asOtherGuardian.toString()).doesNotContain("grp-labels-guardian1").doesNotContain(phone);
		assertThat(asSelf).contains("grp-labels-guardian1");
		// a message of a guardian carries the same label
		UUID message = send(m.g1(), parents, "hello parents");
		assertThat(get(conv(parents) + "/messages/" + message, m.lead()).get("sender").toString()).doesNotContain("grp-labels-guardian1");
		assertThat(get(conv(parents) + "/messages/" + message, m.g2()).get("sender").toString()).doesNotContain("grp-labels-guardian1");
		assertThat(new ArrayList<>(List.of(1))).hasSize(1);
	}

}
