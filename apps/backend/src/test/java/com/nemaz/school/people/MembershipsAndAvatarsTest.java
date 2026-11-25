package com.nemaz.school.people;

import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.MediaFixtures;
import com.nemaz.school.support.MediaTestSupport;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /me} memberships, and who may see a school member's avatar. */
@IntegrationTest
class MembershipsAndAvatarsTest extends MediaTestSupport {

	private JsonNode me(Persona who) throws Exception {
		return this.json.readTree(this.mvc.perform(get("/api/v1/me").with(who.bearer()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString());
	}

	@Test
	void meListsActiveMembershipsAsTeacherStaffAndStudentAcrossSchools() throws Exception {
		UUID a = this.personas.newSchool("Membership A");
		UUID b = this.personas.newSchool("Membership B");
		UUID user = this.personas.newTeacher(a, "multi");
		// also support staff of school B and a past membership in a third school
		this.jdbc.update("insert into school_staff (school_id, user_id, staff_type, status) values (?, ?, 'SUPPORT_STAFF', 'ACTIVE')", b, user);
		UUID c = this.personas.newSchool("Membership C");
		this.jdbc.update("insert into school_staff (school_id, user_id, staff_type, status) values (?, ?, 'TEACHER', 'FORMER')", c, user);
		JsonNode memberships = me(this.personas.personaFor("multi", user)).get("memberships");
		assertThat(memberships).hasSize(2);
		JsonNode teacher = memberships.get(0);
		assertThat(teacher.get("type").asString()).isEqualTo("TEACHER");
		assertThat(teacher.get("schoolId").asString()).isEqualTo(a.toString());
		assertThat(teacher.get("schoolName").asString()).isEqualTo("Membership A");
		assertThat(teacher.get("schoolSlug").asString()).isNotBlank();
		assertThat(teacher.get("staffId").asString()).isNotBlank();
		assertThat(teacher.has("studentId")).isFalse();
		assertThat(memberships.get(1).get("type").asString()).isEqualTo("STAFF");

		// a withdrawn student has none; a school administrator without a staff row has none either
		UUID student = this.personas.newStudent(a, "gone");
		assertThat(me(this.personas.personaFor("gone", student)).get("memberships")).hasSize(1);
		this.jdbc.update("update school_students set status = 'WITHDRAWN', left_on = current_date where student_id = ?", student);
		assertThat(me(this.personas.personaFor("gone", student)).get("memberships")).isEmpty();
		assertThat(me(this.personas.schoolAdmin(a, "admin-no-staff-row")).get("memberships")).isEmpty();
	}

	@Test
	void aTeachersAvatarIsVisibleToTheirSchoolsMembersAndAStudentsToClassmatesAndStaff() throws Exception {
		UUID school = this.personas.newSchool("Avatar school");
		UUID classId = this.personas.newClass(school, "1A");
		UUID otherClass = this.personas.newClass(school, "1B");
		UUID teacherId = this.personas.newTeacher(school, "Avatar teacher");
		Persona teacher = this.personas.personaFor("teacher", teacherId);
		UUID s1 = this.personas.newStudent(school, "Avatar kid");
		UUID s2 = this.personas.newStudent(school, "Classmate");
		UUID s3 = this.personas.newStudent(school, "Other class kid");
		this.personas.addStudentToClass(school, classId, s1);
		this.personas.addStudentToClass(school, classId, s2);
		this.personas.addStudentToClass(school, otherClass, s3);
		UUID colleagueId = this.personas.newTeacher(school, "Colleague");
		Persona kid = this.personas.personaFor("kid", s1);
		Persona classmate = this.personas.personaFor("classmate", s2);
		Persona otherKid = this.personas.personaFor("other-kid", s3);
		Persona colleague = this.personas.personaFor("colleague", colleagueId);
		Persona foreign = this.personas.personaFor("foreign", this.personas.newStudent(this.personas.newSchool("Elsewhere"), "foreigner"));

		String teacherAvatar = uploadReady(teacher, "AVATAR", "t.jpg", "image/jpeg", MediaFixtures.jpeg(300, 300), null)
			.get("id").asString();
		this.mvc.perform(put("/api/v1/me/avatar").with(teacher.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content(this.json.writeValueAsString(Map.of("mediaId", teacherAvatar)))).andExpect(status().isOk());
		// staff and students of the same school see a teacher
		for (Persona viewer : new Persona[] { kid, classmate, otherKid, colleague }) {
			getMedia(viewer, teacherAvatar).andExpect(status().isOk());
		}
		getMedia(foreign, teacherAvatar).andExpect(status().isNotFound());
		getMedia(this.personas.plainUser(), teacherAvatar).andExpect(status().isNotFound());

		String kidAvatar = uploadReady(kid, "AVATAR", "k.jpg", "image/jpeg", MediaFixtures.jpeg(300, 300), null).get("id").asString();
		this.mvc.perform(put("/api/v1/me/avatar").with(kid.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content(this.json.writeValueAsString(Map.of("mediaId", kidAvatar)))).andExpect(status().isOk());
		// a child's picture: classmates, staff of the school; not pupils of other classes or other schools
		getMedia(classmate, kidAvatar).andExpect(status().isOk());
		getMedia(teacher, kidAvatar).andExpect(status().isOk());
		getMedia(colleague, kidAvatar).andExpect(status().isOk());
		getMedia(otherKid, kidAvatar).andExpect(status().isNotFound());
		getMedia(foreign, kidAvatar).andExpect(status().isNotFound());
		getMedia(this.personas.plainUser(), kidAvatar).andExpect(status().isNotFound());
		getMedia(this.personas.platformAdmin(), kidAvatar).andExpect(status().isOk());
	}

}
