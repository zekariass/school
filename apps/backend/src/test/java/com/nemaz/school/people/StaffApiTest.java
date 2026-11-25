package com.nemaz.school.people;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.academic.ClassMembershipChanged;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.support.Persona;
import com.nemaz.school.support.Personas;
import com.nemaz.school.support.SchoolApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Staff provisioning: reuse of accounts, duplicates, administrative roles, notification, deactivation. */
@IntegrationTest
@RecordApplicationEvents
class StaffApiTest extends SchoolApiTestSupport {

	@org.springframework.beans.factory.annotation.Autowired
	ApplicationEvents events;

	private UUID schoolId;

	private Persona admin;

	private String staff() {
		return school(this.schoolId) + "/staff";
	}

	@BeforeEach
	void school() {
		this.schoolId = this.personas.newSchool("Staff school");
		this.admin = this.personas.schoolAdmin(this.schoolId, "staff-admin");
	}

	private UUID userByPhone(String phone) {
		return this.jdbc.queryForObject("select id from users where phone_e164 = ?", UUID.class, phone);
	}

	@Test
	void addingATeacherCreatesAnAccountProfileMembershipNotificationAndWelcomeSms() throws Exception {
		String phone = Personas.newPhone();
		String national = "0" + phone.substring(4);
		JsonNode created = post(staff(), this.admin, Map.of("phone", national, "givenName", "Tigist", "familyName", "Alemu",
				"staffType", "TEACHER", "jobTitle", "Math teacher"), 201);
		UUID userId = userByPhone(phone);
		assertThat(created.get("userId").asString()).isEqualTo(userId.toString());
		assertThat(created.get("staffType").asString()).isEqualTo("TEACHER");
		assertThat(created.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(created.get("maskedPhone").asString()).contains("****").doesNotContain(phone);
		Map<String, Object> user = this.jdbc.queryForMap(
				"select account_type, status, phone_verified_at is not null as verified, preferred_language_code from users where id = ?",
				userId);
		assertThat(user).containsEntry("account_type", "STANDARD")
			.containsEntry("status", "ACTIVE")
			.containsEntry("verified", true)
			.containsEntry("preferred_language_code", "am");
		assertThat(this.jdbc.queryForObject("select count(*) from teacher_profiles where user_id = ?", Integer.class, userId))
			.isEqualTo(1);
		// the welcome SMS goes to the new user, in the school's language
		assertThat(this.messages.awaitMessage(phone)).contains("Staff school");
		// in-app notification, rendered in the user's language (Amharic by default for this school)
		Map<String, Object> notification = this.jdbc.queryForMap(
				"select title, body, school_id from notifications where user_id = ? and type_code = 'STAFF_ADDED'", userId);
		assertThat((String) notification.get("title")).isEqualTo("ወደ Staff school ተጨምረዋል");
		assertThat((String) notification.get("body")).contains("መምህር");
		assertThat(notification.get("school_id")).isEqualTo(this.schoolId);
		assertThat(this.events.stream(StaffAdded.class).toList()).singleElement()
			.satisfies((e) -> assertThat(e.createdUser()).isTrue());
		assertThat(this.jdbc.queryForList("select metadata::text from audit_logs where action = 'staff.add' and school_id = ?",
				String.class, this.schoolId)).singleElement().satisfies((m) -> assertThat(m).doesNotContain(phone));
	}

	@Test
	void theNotificationIsWrittenInTheLanguageOfTheNewStaffMember() throws Exception {
		String phone = Personas.newPhone();
		post(staff(), this.admin, Map.of("phone", phone, "givenName", "Dan", "staffType", "ADMINISTRATOR", "preferredLanguage",
				"en"), 201);
		Map<String, Object> notification = this.jdbc.queryForMap(
				"select title, body from notifications where user_id = ? and type_code = 'STAFF_ADDED'", userByPhone(phone));
		assertThat(notification.get("title")).isEqualTo("You were added to Staff school");
		assertThat(notification.get("body")).isEqualTo("You are now a administrator at Staff school.");
	}

	@Test
	void anExistingAccountIsReusedAndAnotherSchoolMayEmployTheSamePerson() throws Exception {
		Persona existing = this.personas.newUser("existing");
		String phone = this.jdbc.queryForObject("select phone_e164 from users where id = ?", String.class, existing.userId());
		int usersBefore = this.jdbc.queryForObject("select count(*) from users", Integer.class);
		JsonNode created = post(staff(), this.admin, Map.of("phone", phone, "givenName", "Whoever", "staffType", "TEACHER"), 201);
		assertThat(created.get("userId").asString()).isEqualTo(existing.userId().toString());
		assertThat(this.jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(usersBefore);
		// the existing person keeps their own name
		assertThat(this.jdbc.queryForObject("select display_name from users where id = ?", String.class, existing.userId()))
			.isEqualTo("existing");
		// no welcome SMS for somebody who already has an account
		assertThat(this.messages.nothingDelivered(phone, java.time.Duration.ofMillis(300))).isTrue();
		assertThat(this.jdbc.queryForObject("select count(*) from notifications where user_id = ? and type_code = 'STAFF_ADDED'",
				Integer.class, existing.userId())).isEqualTo(1);
		// staff of a second school
		UUID other = this.personas.newSchool("Second staff school");
		Persona otherAdmin = this.personas.schoolAdmin(other, "second-admin");
		post(school(other) + "/staff", otherAdmin, Map.of("phone", phone, "givenName", "Whoever", "staffType", "SUPPORT_STAFF"), 201);
		assertThat(this.jdbc.queryForObject("select count(*) from school_staff where user_id = ? and status = 'ACTIVE'",
				Integer.class, existing.userId())).isEqualTo(2);
	}

	@Test
	void addingTheSamePersonTwiceIsAConflictAndNeverCreatesADuplicateUser() throws Exception {
		String phone = Personas.newPhone();
		post(staff(), this.admin, Map.of("phone", phone, "givenName", "Once", "staffType", "TEACHER"), 201);
		JsonNode again = post(staff(), this.admin, Map.of("phone", "0" + phone.substring(4), "givenName", "Twice",
				"staffType", "TEACHER"), 409);
		assertThat(errorCode(again)).isEqualTo("staff.already_member");
		assertThat(this.jdbc.queryForObject("select count(*) from users where phone_e164 = ?", Integer.class, phone)).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select count(*) from school_staff where school_id = ?", Integer.class, this.schoolId))
			.isEqualTo(1);
		// the same phone and an email that belong to two people
		Persona byEmail = this.personas.newUser("by-email");
		String email = "staff-" + Ids.newId() + "@example.com";
		this.jdbc.update("update users set email = ?, email_verified_at = now() where id = ?", email, byEmail.userId());
		post(staff(), this.admin, Map.of("phone", phone, "email", email, "givenName", "Mix", "staffType", "TEACHER"), 409);
		post(staff(), this.admin, Map.of("givenName", "Nobody", "staffType", "TEACHER"), 400);
		post(staff(), this.admin, Map.of("phone", "123", "givenName", "Bad", "staffType", "TEACHER"), 400);
	}

	@Test
	void administratorsCanGetAnAdministrativeRoleButNeverMoreThanTheyHold() throws Exception {
		UUID campus = Ids.newId();
		this.jdbc.update("insert into campuses (id, school_id, name, code) values (?, ?, 'North', 'N')", campus, this.schoolId);
		String phone = Personas.newPhone();
		post(staff(), this.admin, Map.of("phone", phone, "givenName", "Head", "staffType", "ADMINISTRATOR", "adminRole",
				"SCHOOL_ADMIN"), 201);
		assertThat(this.jdbc.queryForObject("select count(*) from role_assignments where user_id = ? and role_code = 'SCHOOL_ADMIN' and school_id = ? and status = 'ACTIVE'",
				Integer.class, userByPhone(phone), this.schoolId)).isEqualTo(1);

		String campusPhone = Personas.newPhone();
		post(staff(), this.admin, Map.of("phone", campusPhone, "givenName", "Campus", "staffType", "ADMINISTRATOR", "adminRole",
				"CAMPUS_ADMIN", "campusId", campus), 201);
		assertThat(this.jdbc.queryForMap("select scope_type, campus_id, school_id from role_assignments where user_id = ?",
				userByPhone(campusPhone))).containsEntry("scope_type", "CAMPUS")
			.containsEntry("campus_id", campus)
			.containsEntry("school_id", this.schoolId);
		assertThat(this.jdbc.queryForObject("select campus_id from school_staff where user_id = ?", UUID.class,
				userByPhone(campusPhone))).isEqualTo(campus);

		// rules of the request
		post(staff(), this.admin, Map.of("phone", Personas.newPhone(), "givenName", "T", "staffType", "TEACHER", "adminRole",
				"SCHOOL_ADMIN"), 400);
		post(staff(), this.admin, Map.of("phone", Personas.newPhone(), "givenName", "C", "staffType", "ADMINISTRATOR",
				"adminRole", "CAMPUS_ADMIN"), 400);

		// someone with school.staff.manage but not the permissions of SCHOOL_ADMIN cannot hand that role out
		Persona limited = this.personas.withPlatformPermissions(Permissions.SCHOOL_STAFF_MANAGE, Permissions.SCHOOL_READ);
		String escalation = Personas.newPhone();
		JsonNode denied = post(staff(), limited, Map.of("phone", escalation, "givenName", "Esc", "staffType",
				"ADMINISTRATOR", "adminRole", "SCHOOL_ADMIN"), 403);
		assertThat(errorCode(denied)).isEqualTo("role.assignment_not_allowed");
		// the whole request rolled back: no account, no staff row
		assertThat(this.jdbc.queryForObject("select count(*) from users where phone_e164 = ?", Integer.class, escalation)).isZero();
		// the same person can be added as a plain administrator by that caller
		post(staff(), limited, Map.of("phone", escalation, "givenName", "Esc", "staffType", "ADMINISTRATOR"), 201);
	}

	@Test
	void listFiltersPagesAndHidesContactsFromColleagues() throws Exception {
		UUID t1 = this.personas.newTeacher(this.schoolId, "Almaz");
		UUID t2 = this.personas.newTeacher(this.schoolId, "Berhane");
		post(staff(), this.admin, Map.of("phone", Personas.newPhone(), "givenName", "Chaltu", "staffType", "SUPPORT_STAFF"), 201);
		JsonNode all = get(staff(), this.admin);
		assertThat(all.get("items")).hasSize(3);
		assertThat(all.get("items").valueStream().map((s) -> s.get("displayName").asString()).toList())
			.containsExactly("Almaz", "Berhane", "Chaltu");
		assertThat(get(staff() + "?staffType=TEACHER", this.admin).get("items")).hasSize(2);
		assertThat(get(staff() + "?q=berh", this.admin).get("items")).hasSize(1);
		JsonNode page1 = get(staff() + "?limit=2", this.admin);
		assertThat(page1.get("items")).hasSize(2);
		JsonNode page2 = get(staff() + "?limit=2&cursor=" + page1.get("nextCursor").asString(), this.admin);
		assertThat(page2.get("items")).hasSize(1);
		assertThat(page2.has("nextCursor")).isFalse();

		// a colleague sees ACTIVE staff without contact details
		Persona colleague = this.personas.personaFor("almaz", t1);
		JsonNode seen = get(staff(), colleague);
		assertThat(seen.get("items")).hasSize(3);
		assertThat(seen.get("items").get(0).has("maskedPhone")).isFalse();
		assertThat(seen.get("items").get(0).has("employeeNumber")).isFalse();
		get(staff() + "?status=FORMER", colleague);
		// students cannot list staff
		Persona student = this.personas.personaFor("kid", this.personas.newStudent(this.schoolId, "Kid"));
		call(HttpMethod.GET, staff(), student, null, 403);
		assertThat(t2).isNotNull();
	}

	@Test
	void updateAndDeactivateEndTeachingAndKeepAdministrativeRoles() throws Exception {
		String phone = Personas.newPhone();
		JsonNode created = post(staff(), this.admin, Map.of("phone", phone, "givenName", "Leaver", "staffType", "TEACHER"), 201);
		UUID staffId = uuid(created, "id");
		UUID teacherId = userByPhone(phone);
		JsonNode updated = call(HttpMethod.PATCH, staff() + "/" + staffId, this.admin, Map.of("jobTitle", "Head of maths"), 200);
		assertThat(updated.get("jobTitle").asString()).isEqualTo("Head of maths");
		UUID classId = this.personas.newClass(this.schoolId, "Teaching");
		this.personas.addTeacherToClass(this.schoolId, classId, teacherId, "LEAD", null);
		this.personas.grant(teacherId, "SCHOOL_STAFF", AccessScope.school(this.schoolId));
		this.events.clear();

		JsonNode left = post(staff() + "/" + staffId + "/deactivate", this.admin, null, 200);
		assertThat(left.get("status").asString()).isEqualTo("FORMER");
		assertThat(left.has("endedOn")).isTrue();
		assertThat(this.jdbc.queryForObject("select status from class_teachers where class_id = ? and teacher_id = ?",
				String.class, classId, teacherId)).isEqualTo("FORMER");
		assertThat(this.jdbc.queryForObject("select count(*) from role_assignments where user_id = ? and status = 'ACTIVE'",
				Integer.class, teacherId)).isEqualTo(1);
		assertThat(this.events.stream(StaffDeactivated.class).count()).isEqualTo(1);
		assertThat(this.events.stream(ClassMembershipChanged.class).count()).isEqualTo(1);
		assertThat(errorCode(post(staff() + "/" + staffId + "/deactivate", this.admin, null, 409))).isEqualTo("staff.not_active");
		// the former teacher cannot be put in a class; and may be added again afterwards
		post(school(this.schoolId) + "/classes/" + classId + "/teachers", this.admin,
				Map.of("teacherId", teacherId, "role", "ASSISTANT"), 409);
		post(staff(), this.admin, Map.of("phone", phone, "givenName", "Leaver", "staffType", "TEACHER"), 201);
		assertThat(this.jdbc.queryForObject("select count(*) from school_staff where user_id = ?", Integer.class, teacherId))
			.isEqualTo(2);
		await().untilAsserted(() -> assertThat(this.jdbc.queryForObject(
				"select count(*) from audit_logs where action = 'staff.deactivate' and school_id = ?", Integer.class,
				this.schoolId)).isEqualTo(1));
	}

	@Test
	void staffOfAnotherSchoolAreNotFound() throws Exception {
		UUID other = this.personas.newSchool("Neighbour");
		Persona otherAdmin = this.personas.schoolAdmin(other, "neighbour-admin");
		UUID theirs = uuid(post(school(other) + "/staff", otherAdmin,
				Map.of("phone", Personas.newPhone(), "givenName", "Theirs", "staffType", "TEACHER"), 201), "id");
		call(HttpMethod.GET, staff() + "/" + theirs, this.admin, null, 404);
		call(HttpMethod.PATCH, staff() + "/" + theirs, this.admin, Map.of("jobTitle", "x"), 404);
		post(staff() + "/" + theirs + "/deactivate", this.admin, null, 404);
		call(HttpMethod.GET, staff(), otherAdmin, null, 404);
		assertThat(List.of(theirs)).isNotEmpty();
	}

}
