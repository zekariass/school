package com.nemaz.school.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.nemaz.school.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Rule book R4: what students may and may not do; a student has no directory of people. */
@IntegrationTest
class StudentMessagingTest extends MessagingTestSupport {

	@Test
	void studentCannotStartDirectByDefault() throws Exception {
		Msg m = school("stu-default");
		for (UUID target : new UUID[] { m.lead().userId(), m.co().userId(), m.s2(), m.g1().userId() }) {
			assertThat(code(startDirect(m.student1(), m, target, null, 403))).isEqualTo("messaging.not_allowed");
		}
		// switching the teacher-student setting on does not let the student start it (TEACHER_ONLY is the default initiator)
		allowTeacherStudentDirect(m.schoolId(), "TEACHER_ONLY");
		assertThat(code(startDirect(m.student1(), m, m.lead().userId(), null, 403))).isEqualTo("messaging.not_allowed");
	}

	@Test
	void teacherToStudentNeedsTheSetting() throws Exception {
		Msg m = school("stu-teacher");
		assertThat(code(startDirect(m.lead(), m, m.s1(), null, 403))).isEqualTo("messaging.not_allowed");
		allowTeacherStudentDirect(m.schoolId(), "TEACHER_ONLY");
		JsonNode created = startDirect(m.lead(), m, m.s1(), null, 201);
		assertThat(created.get("summary").get("directKind").asString()).isEqualTo("TEACHER_STUDENT");
		// only a teacher of the student: not another class's teacher, an assistant, staff, an administrator or a guardian
		for (var who : new com.nemaz.school.support.Persona[] { m.otherTeacher(), m.assistant(), m.staff(), m.admin(), m.g1() }) {
			assertThat(code(startDirect(who, m, m.s1(), null, 403))).isEqualTo("messaging.not_allowed");
		}
		// a student of the school who is not in the teacher's class
		assertThat(code(startDirect(m.lead(), m, m.o1(), null, 403))).isEqualTo("messaging.not_allowed");
	}

	@Test
	void initiatorSettingDecidesWhoStarts() throws Exception {
		Msg m = school("stu-initiator");
		allowTeacherStudentDirect(m.schoolId(), "EITHER");
		JsonNode byStudent = startDirect(m.student1(), m, m.lead().userId(), null, 201);
		assertThat(byStudent.get("summary").get("myActingAs").asString()).isEqualTo("STUDENT");
		// the same thread from the teacher's side
		assertThat(conversationId(startDirect(m.lead(), m, m.s1(), null, 200))).isEqualTo(conversationId(byStudent));
		// the student can only reach their own teachers
		assertThat(code(startDirect(m.student1(), m, m.otherTeacher().userId(), null, 403))).isEqualTo("messaging.not_allowed");
		assertThat(code(startDirect(m.student1(), m, m.assistant().userId(), null, 403))).isEqualTo("messaging.not_allowed");
		assertThat(code(startDirect(m.student1(), m, m.staff().userId(), null, 403))).isEqualTo("messaging.not_allowed");
		assertThat(code(startDirect(m.student1(), m, m.admin().userId(), null, 403))).isEqualTo("messaging.not_allowed");
		// the floor applies to a student-started conversation too
		assertThat(observers(conversationId(byStudent))).containsExactlyInAnyOrder(m.g1().userId(), m.g1b().userId());
	}

	@Test
	void studentToStudentNeedsSettingAndSameClass() throws Exception {
		Msg m = school("stu-stu");
		assertThat(code(startDirect(m.student1(), m, m.s2(), null, 403))).isEqualTo("messaging.not_allowed");
		setting(m.schoolId(), "messaging.student.student_direct.enabled", true);
		JsonNode created = startDirect(m.student1(), m, m.s2(), null, 201);
		assertThat(created.get("summary").get("directKind").asString()).isEqualTo("STUDENT_STUDENT");
		assertThat(conversationId(startDirect(m.student2(), m, m.s1(), null, 200))).isEqualTo(conversationId(created));
		// no adult, so no observers (the floor is about adult-student conversations)
		assertThat(observers(conversationId(created))).isEmpty();
		// a student of another class cannot be reached
		assertThat(code(startDirect(m.student1(), m, m.o1(), null, 403))).isEqualTo("messaging.not_allowed");
		// the students can write to each other
		send(m.student1(), conversationId(created), "hello Bethlehem");
		assertThat(bodies(messagesOf(m.student2(), conversationId(created)))).containsExactly("hello Bethlehem");
		// when one leaves the class the thread is closed for both
		leaveClass(m.classId(), m.s2());
		assertThat(statusOf(HttpMethod.GET, conv(conversationId(created)), m.student1(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, conv(conversationId(created)), m.student2(), null)).isEqualTo(404);
	}

	@Test
	void studentCannotMessageGuardiansStaffOrStrangers() throws Exception {
		Msg m = school("stu-adults");
		allowTeacherStudentDirect(m.schoolId(), "EITHER");
		setting(m.schoolId(), "messaging.student.student_direct.enabled", true);
		setting(m.schoolId(), "messaging.parent_parent.enabled", true);
		for (var target : new com.nemaz.school.support.Persona[] { m.g1(), m.g2(), m.restricted(), m.staff(), m.admin(),
				m.stranger(), m.otherAdmin(), m.otherGuardian() }) {
			assertThat(code(startDirect(m.student1(), m, target.userId(), m.s1(), 403))).isEqualTo("messaging.not_allowed");
		}
		// and nobody else may start a conversation with a student unless they are a teacher of the student
		assertThat(code(startDirect(m.g1(), m, m.s1(), m.s1(), 403))).isEqualTo("messaging.not_allowed");
		assertThat(code(startDirect(m.stranger(), m, m.s1(), null, 404))).isEqualTo("school.not_found");
	}

	@Test
	void studentCannotEnumerateUsers() throws Exception {
		Msg m = school("stu-enum");
		allowTeacherStudentDirect(m.schoolId(), "EITHER");
		// the contacts list is for adults of a child: students have none
		assertThat(code(call(HttpMethod.GET, m.api() + "/contacts?studentId=" + m.s1(), m.student1(), null, 403)))
			.isEqualTo("messaging.not_allowed");
		assertThat(statusOf(HttpMethod.GET, m.api() + "/contacts?studentId=" + m.s2(), m.student1(), null)).isEqualTo(403);
		// a guardian cannot list the teachers of somebody else's child; a student cannot list through a group list either
		assertThat(statusOf(HttpMethod.GET, m.api() + "/contacts?studentId=" + m.s2(), m.g1(), null)).isEqualTo(403);
		assertThat(get(m.api() + "/groups", m.student1())).isEmpty();
		// no messaging endpoint is a search or a directory
		JsonNode docs = get("/v3/api-docs", m.admin());
		docs.get("paths").properties().forEach((e) -> {
			String path = e.getKey();
			if (path.contains("messaging") || path.contains("conversations") || path.contains("/me/blocks")) {
				assertThat(path).doesNotContain("search").doesNotContain("directory").doesNotContain("/users");
			}
		});
		// payloads a student reads identify people by member id only: no user id, no personal data
		UUID thread = conversationId(startDirect(m.student1(), m, m.lead().userId(), null, 201));
		String json = get(conv(thread) + "/members", m.student1()).toString() + get(conv(thread), m.student1())
			+ inbox(m.student1());
		assertThat(json).doesNotContain("userId").doesNotContain("dateOfBirth").doesNotContain("gender").doesNotContain("username")
			.doesNotContain("phone").doesNotContain("email");
		assertThat(json).doesNotContain(m.lead().userId().toString()).doesNotContain(m.g1().userId().toString());
	}

	@Test
	void studentImagesAreOffByDefault() throws Exception {
		Msg m = school("stu-media");
		UUID thread = classConversation(m);
		assertThat(requestUpload(m.student1(), "MESSAGE", "p.jpg", "image/jpeg", 1000, conversationContext(thread)).andReturn()
			.getResponse()
			.getStatus()).isEqualTo(403);
		UUID teacherImage = image(m.lead(), thread);
		assertThat(teacherImage).isNotNull();
		setting(m.schoolId(), "messaging.student.media_enabled", true);
		UUID studentImage = image(m.student1(), thread);
		JsonNode sent = post(conv(thread) + "/messages", m.student1(),
				body("clientMessageId", UUID.randomUUID(), "mediaIds", java.util.List.of(studentImage)), 201);
		assertThat(sent.get("message").get("attachments")).hasSize(1);
		setting(m.schoolId(), "messaging.student.media_enabled", false);
		assertThat(code(post(conv(thread) + "/messages", m.student1(),
				body("clientMessageId", UUID.randomUUID(), "mediaIds", java.util.List.of(image(m.lead(), thread))), 403)))
			.isEqualTo("messaging.student_media_disabled");
	}

}
