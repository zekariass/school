package com.nemaz.school.guardians;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.academic.ClassAccess;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.support.Persona;
import com.nemaz.school.support.Personas.Fixture;
import com.nemaz.school.support.Personas.Name;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a link grants: the guardian rule of class view, the roster split, the GuardianAccess API, custody restriction,
 * revocation, verifier rules, restriction-note visibility and links created by the school.
 */
@IntegrationTest
class GuardianAccessTest extends GuardianTestSupport {

	@Autowired
	GuardianAccess guardians;

	@Autowired
	ClassAccess classAccess;

	private Persona persona(Name name) {
		return this.personas.get(name);
	}

	private static CurrentUser user(Persona p) {
		return new CurrentUser(p.userId(), null, null, List.of());
	}

	@Test
	void guardianPersonasSeeExactlyTheirChildsClassAndRecord() throws Exception {
		Fixture f = this.personas.fixture();
		UUID a1 = persona(Name.STUDENT_A1).userId();
		UUID a2 = persona(Name.STUDENT_A2).userId();
		String class8A = school(f.schoolA()) + "/classes/" + f.class8A();
		Persona guardian = persona(Name.GUARDIAN_A1);

		JsonNode classView = get(class8A, guardian);
		assertThat(classView.get("name").asString()).isEqualTo("8A");
		assertThat(classView.has("myMembership")).isFalse();
		assertThat(get(class8A + "/teachers", guardian)).isNotEmpty();
		assertThat(errorCode(call(HttpMethod.GET, class8A + "/students", guardian, null, 404))).isEqualTo("class.not_found");
		assertThat(get(school(f.schoolA()) + "/students/" + a1, guardian).get("accessLevel").asString()).isEqualTo("FULL");
		call(HttpMethod.GET, school(f.schoolA()) + "/students/" + a2, guardian, null, 404);
		call(HttpMethod.GET, school(f.schoolA()) + "/classes/" + f.class7B(), guardian, null, 404);
		// a classmate of the child would be BASIC for a student: a guardian gets nothing
		UUID classmate = this.personas.newStudent(f.schoolA(), "classmate");
		UUID shared = this.personas.newClass(f.schoolA(), "Shared");
		this.personas.addStudentToClass(f.schoolA(), shared, a1);
		this.personas.addStudentToClass(f.schoolA(), shared, classmate);
		call(HttpMethod.GET, school(f.schoolA()) + "/students/" + classmate, guardian, null, 404);
		assertThat(get(school(f.schoolA()) + "/students/" + classmate, persona(Name.STUDENT_A1)).get("accessLevel")
			.asString()).isEqualTo("BASIC");
		// unchanged for members: the student and the teacher still see the roster
		assertThat(get(class8A + "/students", persona(Name.STUDENT_A1))).isNotEmpty();
		assertThat(get(class8A + "/students", persona(Name.TEACHER_LEAD_A)).get(0).get("accessLevel").asString())
			.isEqualTo("FULL");
		assertThat(this.classAccess.canView(user(guardian), f.class8A())).isTrue();
		assertThat(this.classAccess.canViewRoster(user(guardian), f.class8A())).isFalse();
		assertThat(this.classAccess.canTeach(user(guardian), f.class8A())).isFalse();

		for (Name nobody : List.of(Name.GUARDIAN_PENDING_A1, Name.GUARDIAN_RESTRICTED_A1, Name.GUARDIAN_REVOKED_A1,
				Name.UNRELATED_ADULT)) {
			Persona p = persona(nobody);
			call(HttpMethod.GET, class8A, p, null, 404);
			call(HttpMethod.GET, school(f.schoolA()) + "/students/" + a1, p, null, 404);
			assertThat(get("/api/v1/me/children", p).get("children")).as(nobody.name()).isEmpty();
			assertThat(get("/api/v1/me", p).get("memberships")).as(nobody.name()).isEmpty();
			assertThat(this.guardians.isActiveGuardian(p.userId(), a1)).as(nobody.name()).isFalse();
		}
		assertThat(get("/api/v1/me/children", persona(Name.GUARDIAN_PENDING_A1)).get("pending")).hasSize(1);
		assertThat(get("/api/v1/me/children", persona(Name.GUARDIAN_RESTRICTED_A1)).get("pending")).isEmpty();
		assertThat(get("/api/v1/me", guardian).get("memberships").get(0).get("type").asString()).isEqualTo("GUARDIAN");

		// GuardianAccess
		assertThat(this.guardians.isActiveGuardian(guardian.userId(), a1)).isTrue();
		assertThat(this.guardians.can(guardian.userId(), a1, GuardianPermission.GIVE_CONSENT)).isTrue();
		assertThat(this.guardians.guardiansOf(a1)).extracting(GuardianAccess.GuardianGrant::guardianId)
			.containsExactly(guardian.userId());
		assertThat(this.guardians.childrenOf(guardian.userId())).extracting(GuardianAccess.GuardianGrant::studentId)
			.containsExactly(a1);
	}

	@Test
	void flagsCustodyRestrictionAndRevocationApplyToTheNextRequest() throws Exception {
		Setup s = setup("Access change school");
		UUID adult = this.personas.newUser("parent").userId();
		UUID link = this.personas.linkGuardian(s.studentId(), adult, "ACTIVE", false);
		Persona guardian = as(adult);
		String classUrl = school(s.schoolId()) + "/classes/" + s.classId();
		String linkUrl = school(s.schoolId()) + "/guardian-links/" + link;
		get(classUrl, guardian);

		// without can_view_academics: no class, but still the child and its record
		this.jdbc.update("update student_guardians set can_view_academics = false where id = ?", link);
		call(HttpMethod.GET, classUrl, guardian, null, 404);
		assertThat(get("/api/v1/me/children", guardian).get("children").get(0).get("schools").get(0).get("classes"))
			.isEmpty();
		call(HttpMethod.PATCH, linkUrl, s.admin(), Map.of("permissions", Map.of("canViewAcademics", true)), 200);
		get(classUrl, guardian);

		// a custody restriction grants nothing at all, whatever the flags
		JsonNode restricted = call(HttpMethod.PATCH, linkUrl, s.admin(),
				Map.of("custodyRestricted", true, "restrictionNote", "Court order 12/2026"), 200);
		assertThat(restricted.get("restrictionNote").asString()).isEqualTo("Court order 12/2026");
		call(HttpMethod.GET, classUrl, guardian, null, 404);
		call(HttpMethod.GET, school(s.schoolId()) + "/students/" + s.studentId(), guardian, null, 404);
		assertThat(get("/api/v1/me/children", guardian).get("children")).isEmpty();
		assertThat(this.guardians.guardiansOf(s.studentId())).isEmpty();
		assertThat(this.guardians.can(adult, s.studentId(), GuardianPermission.GIVE_CONSENT)).isFalse();

		// the note is for administrators only; teachers see names, relationship and status
		JsonNode forTeacher = get(school(s.schoolId()) + "/students/" + s.studentId() + "/guardians", s.lead()).get(0);
		assertThat(forTeacher.has("restrictionNote")).isFalse();
		assertThat(forTeacher.has("guardianPhone")).isFalse();
		assertThat(forTeacher.get("guardianName").asString()).isEqualTo("parent");
		JsonNode forAdmin = get(school(s.schoolId()) + "/students/" + s.studentId() + "/guardians", s.admin()).get(0);
		assertThat(forAdmin.get("restrictionNote").asString()).isEqualTo("Court order 12/2026");
		assertThat(this.jdbc.queryForList("select coalesce(metadata::text, '') || coalesce(changes::text, '') from audit_logs"
				+ " where target_id = ?", String.class, link)).isNotEmpty()
			.noneMatch((row) -> row.contains("Court order"));
		call(HttpMethod.PATCH, linkUrl, s.lead(), Map.of("custodyRestricted", false), 403);

		call(HttpMethod.PATCH, linkUrl, s.admin(), Map.of("custodyRestricted", false), 200);
		get(classUrl, guardian);
		post(linkUrl + "/revoke", s.admin(), null, 200);
		call(HttpMethod.GET, classUrl, guardian, null, 404);
		assertThat(get("/api/v1/me", guardian).get("memberships")).isEmpty();
		call(HttpMethod.PATCH, linkUrl, s.admin(), Map.of("primary", true), 409);
	}

	@Test
	void onlyLeadTeachersVerifyAndTheSettingLeavesItToAdministrators() throws Exception {
		Setup s = setup("Verifier school");
		UUID coTeacher = this.personas.newTeacher(s.schoolId(), "co");
		this.personas.addTeacherToClass(s.schoolId(), s.classId(), coTeacher, "CO_TEACHER", null);
		UUID first = this.personas.linkGuardian(s.studentId(), this.personas.newUser("p1").userId(), "PENDING", false);
		String approve = school(s.schoolId()) + "/guardian-links/" + first + "/approve";
		assertThat(errorCode(post(approve, as(coTeacher), null, 403))).isEqualTo("guardian.access_denied");
		call(HttpMethod.GET, school(s.schoolId()) + "/guardian-links", as(coTeacher), null, 403);
		post(approve, s.lead(), null, 200);
		assertThat(errorCode(post(approve, s.lead(), null, 409))).isEqualTo("guardian_link.not_pending");

		setting(s.schoolId(), "guardians.teachers_can_verify", false);
		UUID second = this.personas.linkGuardian(s.studentId(), this.personas.newUser("p2").userId(), "PENDING", false);
		post(school(s.schoolId()) + "/guardian-links/" + second + "/approve", s.lead(), null, 403);
		call(HttpMethod.GET, school(s.schoolId()) + "/guardian-links", s.lead(), null, 403);
		JsonNode rejected = post(school(s.schoolId()) + "/guardian-links/" + second + "/reject", s.admin(),
				Map.of("note", "Not a parent"), 200);
		assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
		UUID rejectedAdult = UUID.fromString(rejected.get("guardianId").asString());
		assertThat(this.jdbc.queryForObject(
				"select title from notifications where user_id = ? and type_code = 'GUARDIAN_LINK_REJECTED'", String.class,
				rejectedAdult)).isEqualTo("Link request not approved");
		// another school's administrator cannot see the link
		Persona otherAdmin = this.personas.schoolAdmin(this.personas.newSchool("Other verifier school"), "other-admin");
		post(school(s.schoolId()) + "/guardian-links/" + first + "/revoke", otherAdmin, null, 404);
	}

	@Test
	void theSchoolLinksAnAdultItKnowsAndOnlyOnePrimaryGuardian() throws Exception {
		Setup s = setup("Admin link school");
		String phone = newPhone();
		JsonNode link = post(school(s.schoolId()) + "/students/" + s.studentId() + "/guardians", s.admin(),
				Map.of("phone", "0" + phone.substring(4), "name", "Hirut Tesfaye", "relationshipType", "MOTHER"), 201);
		assertThat(link.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(link.get("verificationMethod").asString()).isEqualTo("SCHOOL_VERIFIED");
		assertThat(link.get("requestedVia").asString()).isEqualTo("ADMIN");
		assertThat(this.messages.awaitMessage(phone)).contains("Admin link school");
		UUID adult = userByPhone(phone);
		assertThat(get("/api/v1/me/children", as(adult)).get("children")).hasSize(1);
		// the same adult again: the same link
		JsonNode again = post(school(s.schoolId()) + "/students/" + s.studentId() + "/guardians", s.admin(),
				Map.of("phone", phone, "name", "Hirut", "relationshipType", "MOTHER"), 201);
		assertThat(again.get("id").asString()).isEqualTo(link.get("id").asString());

		UUID other = this.personas.linkGuardian(s.studentId(), this.personas.newUser("father").userId(), "ACTIVE", false);
		call(HttpMethod.PATCH, school(s.schoolId()) + "/guardian-links/" + link.get("id").asString(), s.admin(),
				Map.of("primary", true), 200);
		call(HttpMethod.PATCH, school(s.schoolId()) + "/guardian-links/" + other, s.admin(), Map.of("primary", true), 200);
		assertThat(this.jdbc.queryForList("select id from student_guardians where student_id = ? and is_primary", UUID.class,
				s.studentId())).containsExactly(other);
		// a teacher cannot create links
		post(school(s.schoolId()) + "/students/" + s.studentId() + "/guardians", s.lead(),
				Map.of("phone", newPhone(), "name", "X", "relationshipType", "OTHER"), 403);
	}

	@Test
	void guardiansSeeTheirChildrensAvatarsOnly() throws Exception {
		Setup s = setup("Avatar school");
		UUID adult = this.personas.newUser("avatar-parent").userId();
		this.personas.linkGuardian(s.studentId(), adult, "ACTIVE", false);
		UUID otherChild = this.personas.newStudent(s.schoolId(), "Other child");
		UUID mine = avatar(s.studentId());
		UUID theirs = avatar(otherChild);
		get("/api/v1/media/" + mine, as(adult));
		call(HttpMethod.GET, "/api/v1/media/" + theirs, as(adult), null, 404);
	}

	/** A READY avatar of the user (row only: reads are authorized from the row). */
	private UUID avatar(UUID userId) {
		UUID id = com.nemaz.school.common.id.Ids.newId();
		this.jdbc.update("""
				insert into media (id, owner_id, purpose, context_type, context_id, bucket, object_key, mime_type, media_kind,
				                   size_bytes, status, width, height, created_at)
				values (?, ?, 'AVATAR', 'USER', ?, 'school-media-test', ?, 'image/jpeg', 'IMAGE', 1000, 'READY', 10, 10, ?)""",
				id, userId, userId, "users/" + userId + "/avatar/" + id + "/original", Timestamp.from(Instant.now()));
		return id;
	}

}
