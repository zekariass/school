package com.nemaz.school.communications;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who an announcement reaches (the audience resolution matrix), who may address whom, and that reading follows the same
 * rule as the snapshot.
 */
@IntegrationTest
class AnnouncementAudienceTest extends CommunicationsTestSupport {

	@Test
	void aClassAudienceReachesTheActiveUnrestrictedGuardiansOfItsStudentsOncePerAdult() throws Exception {
		Comms c = comms("aud-class");
		UUID id = publishNow(c, c.admin(), "Class news", classAudience(c.class1A()));
		// gSib has two children in the class and is one recipient; restricted, pending and revoked links, the guardian
		// who switched notifications off, and the families of other classes get nothing
		assertThat(recipients(id)).isEqualTo(ids(c.gA1(), c.gSib()));
		assertThat(this.jdbc.queryForObject("select count(*) from announcement_recipients where announcement_id = ? and user_id = ?",
				Integer.class, id, c.gSib().userId())).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select via_student_id from announcement_recipients where announcement_id = ? and user_id = ?",
				UUID.class, id, c.gSib().userId())).isIn(c.a1(), c.a2());
		assertThat(this.jdbc.queryForObject("select recipient_count from announcements where id = ?", Integer.class, id))
			.isEqualTo(2);
	}

	@Test
	void gradeCampusAndSchoolAudiencesWidenTheSet() throws Exception {
		Comms c = comms("aud-wide");
		assertThat(recipients(publishNow(c, c.admin(), "Grade", gradeAudience(c.grade()))))
			.isEqualTo(ids(c.gA1(), c.gSib(), c.gB1()));
		assertThat(recipients(publishNow(c, c.admin(), "Campus 1", campusAudience(c.campus1()))))
			.isEqualTo(ids(c.gA1(), c.gSib(), c.gB1()));
		assertThat(recipients(publishNow(c, c.admin(), "Campus 2", campusAudience(c.campus2())))).isEqualTo(ids(c.gX()));
		assertThat(recipients(publishNow(c, c.admin(), "School", schoolAudience())))
			.isEqualTo(ids(c.gA1(), c.gSib(), c.gB1(), c.gX()));
		// two audiences of one announcement: one entry per adult
		assertThat(recipients(publishNow(c, c.admin(), "Two", classAudience(c.class1A()), classAudience(c.class1B()))))
			.isEqualTo(ids(c.gA1(), c.gSib(), c.gB1()));
	}

	@Test
	void studentsAreAddressedOnlyWhenTheSchoolAllowsIt() throws Exception {
		Comms c = comms("aud-students");
		JsonNode refused = call(HttpMethod.POST, c.api(), c.admin(),
				with(announcement("t", "b", classAudience(c.class1A(), "GUARDIAN", "STUDENT")), "mode", "PUBLISH"), 400);
		assertThat(code(refused)).isEqualTo("announcement.students_disabled");
		setting(c.schoolId(), "announcements.students_enabled", true);
		UUID id = publishNow(c, c.admin(), "To the class", classAudience(c.class1A(), "GUARDIAN", "STUDENT"));
		// the three students ACTIVE in the class (not the former one) and the two guardians
		assertThat(recipients(id)).containsExactlyInAnyOrder(c.a1(), c.a2(), c.a3(), c.gA1().userId(), c.gSib().userId());
		assertThat(this.jdbc.queryForObject("select recipient_role from announcement_recipients where announcement_id = ? and user_id = ?",
				String.class, id, c.a1())).isEqualTo("STUDENT");
		// the whole school: former class members are still school students
		UUID school = publishNow(c, c.admin(), "School", schoolAudience("STUDENT"));
		assertThat(recipients(school)).containsExactlyInAnyOrder(c.a1(), c.a2(), c.a3(), c.f1(), c.b1(), c.x1());
		// switching the setting off later hides it from the students again
		setting(c.schoolId(), "announcements.students_enabled", false);
		call(HttpMethod.GET, c.api() + "/" + id, c.student1(), null, 404);
	}

	@Test
	void staffAreAddressedByRoleAndOnlyByManagers() throws Exception {
		Comms c = comms("aud-staff");
		assertThat(recipients(publishNow(c, c.admin(), "Class staff", classAudience(c.class1A(), "STAFF"))))
			.isEqualTo(ids(c.lead1A(), c.co1A(), c.subject1A(), c.assistant1A()));
		assertThat(recipients(publishNow(c, c.admin(), "Grade staff", gradeAudience(c.grade(), "STAFF"))))
			.isEqualTo(ids(c.lead1A(), c.co1A(), c.subject1A(), c.assistant1A(), c.lead1B()));
		assertThat(recipients(publishNow(c, c.admin(), "Campus 2 staff", campusAudience(c.campus2(), "STAFF"))))
			.isEqualTo(ids(c.lead2A()));
		assertThat(recipients(publishNow(c, c.admin(), "Everyone", schoolAudience("STAFF", "GUARDIAN"))))
			.isEqualTo(ids(c.lead1A(), c.co1A(), c.subject1A(), c.assistant1A(), c.lead1B(), c.lead2A(), c.gA1(), c.gSib(),
					c.gB1(), c.gX()));
		// a teacher may not address staff
		JsonNode refused = call(HttpMethod.POST, c.api(), c.lead1A(), announcement("t", "b", classAudience(c.class1A(), "STAFF")),
				403);
		assertThat(code(refused)).isEqualTo("announcement.audience_not_allowed");
	}

	@Test
	void teachersAnnounceOnlyToTheirOwnClasses() throws Exception {
		Comms c = comms("aud-teachers");
		for (Persona teacher : List.of(c.lead1A(), c.co1A())) {
			UUID id = publishNow(c, teacher, "From " + teacher.name(), classAudience(c.class1A()));
			assertThat(recipients(id)).isEqualTo(ids(c.gA1(), c.gSib()));
		}
		// subject teachers and assistants teach the class but do not announce for it
		for (Persona teacher : List.of(c.subject1A(), c.assistant1A())) {
			assertThat(code(call(HttpMethod.POST, c.api(), teacher, announcement("t", "b", classAudience(c.class1A())), 403)))
				.isEqualTo("announcement.audience_not_allowed");
		}
		// another class: not even visible to this teacher
		assertThat(code(call(HttpMethod.POST, c.api(), c.lead1A(), announcement("t", "b", classAudience(c.class1B())), 404)))
			.isEqualTo("class.not_found");
		// school, grade and campus audiences are for managers
		for (Map<String, Object> wide : List.of(schoolAudience(), gradeAudience(c.grade()), campusAudience(c.campus1()))) {
			assertThat(code(call(HttpMethod.POST, c.api(), c.lead1A(), announcement("t", "b", wide), 403)))
				.isEqualTo("announcement.audience_not_allowed");
		}
		// the school can switch teacher announcements off
		setting(c.schoolId(), "announcements.teachers_can_announce", false);
		assertThat(code(call(HttpMethod.POST, c.api(), c.lead1A(), announcement("t", "b", classAudience(c.class1A())), 403)))
			.isEqualTo("announcement.audience_not_allowed");
		JsonNode capabilities = get(c.api() + "/capabilities", c.lead1A());
		assertThat(capabilities.get("canCompose").asBoolean()).isFalse();
		setting(c.schoolId(), "announcements.teachers_can_announce", true);
		capabilities = get(c.api() + "/capabilities", c.lead1A());
		assertThat(capabilities.get("canCompose").asBoolean()).isTrue();
		assertThat(capabilities.get("schoolWide").asBoolean()).isFalse();
		assertThat(capabilities.get("classes").size()).isEqualTo(1);
		assertThat(capabilities.get("timeZone").asText()).isNotBlank();
		assertThat(get(c.api() + "/capabilities", c.admin()).get("schoolWide").asBoolean()).isTrue();
	}

	@Test
	void explicitUsersAreLimitedToPeopleTheAuthorMayAlreadySee() throws Exception {
		Comms c = comms("aud-users");
		// a manager: anybody related to the school as guardian (eligible), staff or student
		UUID id = publishNow(c, c.admin(), "Chosen", userAudience(c.gB1().userId(), c.lead2A().userId(), c.gX().userId()));
		assertThat(recipients(id)).isEqualTo(ids(c.gB1(), c.lead2A(), c.gX()));
		// strangers, restricted and pending guardians, and people of another school cannot be named
		Persona stranger = this.personas.newUser("stranger");
		Comms other = comms("aud-users-other");
		for (UUID invalid : List.of(stranger.userId(), c.gRestricted().userId(), c.gPending().userId(), other.gA1().userId(),
				UUID.randomUUID())) {
			JsonNode refused = call(HttpMethod.POST, c.api(), c.admin(), announcement("t", "b", userAudience(invalid)), 400);
			assertThat(code(refused)).isEqualTo("announcement.audience_user_invalid");
		}
		// a teacher: only the guardians of their own classes' students
		assertThat(recipients(publishNow(c, c.lead1A(), "To one parent", userAudience(c.gA1().userId()))))
			.isEqualTo(ids(c.gA1()));
		assertThat(code(call(HttpMethod.POST, c.api(), c.lead1A(), announcement("t", "b", userAudience(c.gB1().userId())), 400)))
			.isEqualTo("announcement.audience_user_invalid");
		// the other school's administrator cannot see this school at all
		assertThat(code(call(HttpMethod.POST, c.api(), other.admin(), announcement("t", "b", userAudience(c.gA1().userId())), 404)))
			.isEqualTo("school.not_found");
	}

	@Test
	void theRecipientSnapshotIsNotRewrittenByLaterRosterChanges() throws Exception {
		Comms c = comms("aud-snapshot");
		UUID id = publishNow(c, c.admin(), "Class news", classAudience(c.class1A()));
		Set<UUID> before = recipients(id);
		// a guardian is linked to a2 and another loses the link; the snapshot stays as it was
		Persona late = this.personas.newUser("late");
		this.personas.linkGuardian(c.a2(), late.userId(), "ACTIVE", false);
		this.jdbc.update("update student_guardians set status = 'REVOKED', revoked_at = now() where guardian_id = ?",
				c.gA1().userId());
		assertThat(recipients(id)).isEqualTo(before);
		// republishing is not possible, and the report counts the snapshot
		assertThat(get(c.api() + "/" + id + "/report", c.admin()).get("recipients").asInt()).isEqualTo(2);
	}

	@Test
	void readingFollowsTheAudienceRuleIncludingLateJoinersAndLostLinks() throws Exception {
		Comms c = comms("aud-reads");
		UUID id = publishNow(c, c.admin(), "Class news", classAudience(c.class1A()));
		String url = c.api() + "/" + id;
		// recipients, the author and the school's managers read it
		for (Persona reader : List.of(c.gA1(), c.gSib(), c.admin())) {
			call(HttpMethod.GET, url, reader, null, 200);
		}
		// everybody else does not: restricted, pending and revoked links, a guardian without notifications, other classes,
		// staff the audience does not address, students while the school does not address them
		for (Persona outsider : List.of(c.gRestricted(), c.gPending(), c.gRevoked(), c.gA3(), c.gB1(), c.gX(), c.lead1A(),
				c.lead1B(), c.student1(), this.personas.newUser("nobody"))) {
			assertThat(code(call(HttpMethod.GET, url, outsider, null, 404))).isIn("announcement.not_found", "school.not_found");
		}
		Comms other = comms("aud-reads-other");
		assertThat(code(call(HttpMethod.GET, url, other.admin(), null, 404))).isEqualTo("school.not_found");
		// a guardian linked to a2 after publishing opens it through the school's list, was not notified and is no recipient
		Persona late = this.personas.newUser("late-guardian");
		this.personas.linkGuardian(c.a2(), late.userId(), "ACTIVE", false);
		assertThat(idsOf(get(c.api(), late))).contains(id.toString());
		assertThat(idsOf(get("/api/v1/me/announcements", late))).doesNotContain(id.toString());
		call(HttpMethod.GET, url, late, null, 200);
		assertThat(recipients(id)).doesNotContain(late.userId());
		// ... and so does a student who joined the class later, once the school addresses students
		setting(c.schoolId(), "announcements.students_enabled", true);
		UUID joiner = this.personas.newStudent(c.schoolId(), "Joiner");
		this.personas.addStudentToClass(c.schoolId(), c.class1A(), joiner);
		// the announcement addressed guardians only: the student still cannot read it
		call(HttpMethod.GET, url, this.personas.personaFor("joiner", joiner), null, 404);
		// a guardian who loses the link or is restricted loses access although they are in the snapshot
		this.jdbc.update("update student_guardians set custody_restricted = true where guardian_id = ?", c.gA1().userId());
		call(HttpMethod.GET, url, c.gA1(), null, 404);
		assertThat(idsOf(get("/api/v1/me/announcements", c.gA1()))).isEmpty();
		this.jdbc.update("update student_guardians set status = 'REVOKED', revoked_at = now() where guardian_id = ?",
				c.gSib().userId());
		call(HttpMethod.GET, url, c.gSib(), null, 404);
	}

	/**
	 * The recipient snapshot (audience to people) and reading (person to audience) are two queries of one rule: for every
	 * kind of audience, whoever is a recipient can open the announcement and nobody else (but its author and managers) can.
	 */
	@Test
	void theSnapshotAndTheReadingRuleAgreeForEveryKindOfAudience() throws Exception {
		Comms c = comms("aud-agree");
		setting(c.schoolId(), "announcements.students_enabled", true);
		List<Persona> people = List.of(c.lead1A(), c.co1A(), c.subject1A(), c.assistant1A(), c.lead1B(), c.lead2A(), c.gA1(),
				c.gSib(), c.gA3(), c.gPending(), c.gRestricted(), c.gRevoked(), c.gB1(), c.gX(), c.student1(),
				this.personas.personaFor("a2", c.a2()), this.personas.personaFor("a3", c.a3()),
				this.personas.personaFor("f1", c.f1()), this.personas.personaFor("b1", c.b1()),
				this.personas.personaFor("x1", c.x1()));
		List<Map<String, Object>> audiences = List.of(classAudience(c.class1A()),
				classAudience(c.class1A(), "GUARDIAN", "STUDENT", "STAFF"), classAudience(c.class1B(), "STUDENT"),
				gradeAudience(c.grade(), "GUARDIAN", "STUDENT", "STAFF"), campusAudience(c.campus1(), "GUARDIAN", "STAFF"),
				campusAudience(c.campus2(), "GUARDIAN", "STUDENT", "STAFF"), schoolAudience("GUARDIAN", "STUDENT", "STAFF"),
				schoolAudience("STAFF"));
		for (Map<String, Object> audience : audiences) {
			UUID id = publishNow(c, c.admin(), "Agree " + audience.get("type"), audience);
			Set<UUID> recipients = recipients(id);
			assertThat(recipients).as("recipients of " + audience).isNotEmpty();
			for (Persona person : people) {
				int status = statusOf(HttpMethod.GET, c.api() + "/" + id, person, null);
				assertThat(status).as(person.name() + " reading " + audience)
					.isEqualTo(recipients.contains(person.userId()) ? 200 : 404);
			}
		}
	}

}
