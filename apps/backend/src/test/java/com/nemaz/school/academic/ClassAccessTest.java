package com.nemaz.school.academic;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.academic.ClassMembershipView.Kind;
import com.nemaz.school.academic.ClassMembershipView.TeacherRole;
import com.nemaz.school.authorization.RoleAssignments;
import com.nemaz.school.authorization.RoleCodes;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.security.AccessControl;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.support.Personas;
import com.nemaz.school.support.Personas.Fixture;
import com.nemaz.school.support.Personas.Name;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reusable class-access rules (Database_Design §5): students and teachers by ACTIVE roster rows, administrators
 * by {@code class.manage} over the class's school or campus, nobody else, and one SQL predicate for lists that agrees
 * with the single-class checks.
 */
@IntegrationTest
class ClassAccessTest {

	@Autowired
	ClassAccess access;

	@Autowired
	RoleAssignments assignments;

	@Autowired
	AccessControl accessControl;

	@Autowired
	JdbcClient jdbcClient;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Personas personas;

	private CurrentUser user(UUID userId) {
		return new CurrentUser(userId, Ids.newId(), null, this.assignments.activeGrants(userId));
	}

	private CurrentUser user(Name name) {
		return user(this.personas.get(name).userId());
	}

	@Test
	void studentsSeeOnlyTheClassesTheyAreActiveIn() {
		Fixture f = this.personas.fixture();
		CurrentUser a1 = user(Name.STUDENT_A1);
		assertThat(this.access.canView(a1, f.class8A())).isTrue();
		assertThat(this.access.canView(a1, f.class7B())).isFalse();
		assertThat(this.access.canView(a1, f.class9B())).isFalse();
		assertThat(this.access.canTeach(a1, f.class8A())).isFalse();
		assertThat(this.access.canManage(a1, f.class8A())).isFalse();
		assertThat(this.access.membershipOf(a1, f.class8A())).get()
			.satisfies((m) -> assertThat(m.kind()).isEqualTo(Kind.STUDENT));
		assertThat(this.access.membershipOf(a1, f.class7B())).isEmpty();
	}

	@Test
	void aFormerMemberAndARemovedMemberLoseAccess() {
		Fixture f = this.personas.fixture();
		assertThat(this.access.canView(user(Name.STUDENT_FORMER_A), f.class8A())).isFalse();
		UUID removed = this.personas.newStudent(f.schoolA(), "removed");
		UUID classId = this.personas.newClass(f.schoolA(), "access-removed");
		this.personas.addStudentToClass(f.schoolA(), classId, removed);
		assertThat(this.access.canView(user(removed), classId)).isTrue();
		this.jdbc.update("update class_students set status = 'REMOVED', left_at = now(), left_reason = 'REMOVED' where student_id = ?",
				removed);
		assertThat(this.access.canView(user(removed), classId)).isFalse();
	}

	@Test
	void teachersByRoleLeadSubjectAndOutsiders() {
		Fixture f = this.personas.fixture();
		CurrentUser lead = user(Name.TEACHER_LEAD_A);
		CurrentUser subject = user(Name.TEACHER_SUBJECT_A);
		CurrentUser other = user(Name.TEACHER_OTHER_A);
		assertThat(this.access.canView(lead, f.class8A())).isTrue();
		assertThat(this.access.canTeach(lead, f.class8A())).isTrue();
		assertThat(this.access.canLead(lead, f.class8A())).isTrue();
		assertThat(this.access.canManage(lead, f.class8A())).isFalse();
		assertThat(this.access.membershipOf(lead, f.class8A())).get().satisfies((m) -> {
			assertThat(m.kind()).isEqualTo(Kind.TEACHER);
			assertThat(m.teacherRole()).isEqualTo(TeacherRole.LEAD);
		});
		// the subject teacher sees and teaches the class, but does not lead it
		assertThat(this.access.canView(subject, f.class8A())).isTrue();
		assertThat(this.access.canTeach(subject, f.class8A())).isTrue();
		assertThat(this.access.canLead(subject, f.class8A())).isFalse();
		assertThat(this.access.membershipOf(subject, f.class8A())).get()
			.satisfies((m) -> assertThat(m.subjectId()).isEqualTo(f.subjectA()));
		// a teacher of the school who is not in the class
		assertThat(this.access.canView(other, f.class8A())).isFalse();
		assertThat(this.access.canTeach(other, f.class8A())).isFalse();
		assertThat(this.access.canView(other, f.class7B())).isTrue();
	}

	@Test
	void administratorsByScopeOfClassManage() {
		Fixture f = this.personas.fixture();
		for (Name name : List.of(Name.PLATFORM_ADMIN, Name.SCHOOL_ADMIN_A, Name.ORG_ADMIN, Name.COUNTRY_ADMIN_ET)) {
			assertThat(this.access.canManage(user(name), f.class8A())).as(name.name()).isTrue();
			assertThat(this.access.canView(user(name), f.class8A())).as(name.name()).isTrue();
			assertThat(this.access.canTeach(user(name), f.class8A())).as(name.name()).isTrue();
		}
		for (Name name : List.of(Name.SCHOOL_ADMIN_B, Name.COUNTRY_ADMIN_KE, Name.PLAIN_USER)) {
			assertThat(this.access.canView(user(name), f.class8A())).as(name.name()).isFalse();
			assertThat(this.access.canManage(user(name), f.class8A())).as(name.name()).isFalse();
		}
		// school B's administrator sees school B's class, not school A's
		assertThat(this.access.canView(user(Name.SCHOOL_ADMIN_B), f.class9B())).isTrue();
		assertThat(this.access.canView(user(Name.SCHOOL_ADMIN_A), f.class9B())).isFalse();
	}

	@Test
	void aCampusAdministratorManagesOnlyClassesOfItsCampus() {
		Fixture f = this.personas.fixture();
		UUID campusClass = Ids.newId();
		this.jdbc.update("insert into classes (id, school_id, campus_id, academic_year_id, name) values (?, ?, ?, ?, 'Campus class')",
				campusClass, f.schoolA(), f.campusA(), f.yearA());
		CurrentUser campusAdmin = user(Name.CAMPUS_ADMIN_A);
		assertThat(this.access.canManage(campusAdmin, campusClass)).isTrue();
		assertThat(this.access.canView(campusAdmin, campusClass)).isTrue();
		// a class without campus needs a school-wide grant
		assertThat(this.access.canManage(campusAdmin, f.class8A())).isFalse();
		assertThat(this.access.canView(campusAdmin, f.class8A())).isFalse();
		// another campus of the same school
		UUID otherCampus = Ids.newId();
		this.jdbc.update("insert into campuses (id, school_id, name, code) values (?, ?, 'Campus Z', 'Z')", otherCampus,
				f.schoolA());
		UUID otherCampusClass = Ids.newId();
		this.jdbc.update("insert into classes (id, school_id, campus_id, academic_year_id, name) values (?, ?, ?, ?, 'Z class')",
				otherCampusClass, f.schoolA(), otherCampus, f.yearA());
		assertThat(this.access.canView(campusAdmin, otherCampusClass)).isFalse();
	}

	@Test
	void unknownClassesAndMissingUsersAreDenied() {
		assertThat(this.access.canView(user(Name.PLATFORM_ADMIN), Ids.newId())).isFalse();
		assertThat(this.access.canManage(user(Name.PLATFORM_ADMIN), Ids.newId())).isFalse();
		assertThat(this.access.canView(null, this.personas.fixture().class8A())).isFalse();
		assertThat(this.access.membershipOf(user(Name.PLAIN_USER), this.personas.fixture().class8A())).isEmpty();
	}

	@Test
	void theListPredicateAgreesWithTheSingleClassChecks() {
		Fixture f = this.personas.fixture();
		List<UUID> classes = this.jdbc.queryForList("select id from classes", UUID.class);
		for (Name name : List.of(Name.STUDENT_A1, Name.STUDENT_A2, Name.STUDENT_FORMER_A, Name.STUDENT_B1,
				Name.TEACHER_LEAD_A, Name.TEACHER_SUBJECT_A, Name.TEACHER_OTHER_A, Name.SCHOOL_ADMIN_A, Name.SCHOOL_ADMIN_B,
				Name.CAMPUS_ADMIN_A, Name.ORG_ADMIN, Name.COUNTRY_ADMIN_KE, Name.PLAIN_USER, Name.PLATFORM_ADMIN)) {
			CurrentUser user = user(name);
			Set<UUID> visible = this.access.accessibleClassIds(user);
			for (UUID id : classes) {
				assertThat(visible.contains(id)).as(name + " " + id).isEqualTo(this.access.canView(user, id));
			}
		}
		assertThat(this.access.accessibleClassIds(user(Name.STUDENT_B1))).contains(f.class9B()).doesNotContain(f.class8A());
	}

	@Test
	void studentsAndTeachersListOnlyTheirOwnClassesWhenFreshlyCreated() {
		Fixture f = this.personas.fixture();
		UUID student = this.personas.newStudent(f.schoolA(), "list-student");
		UUID teacher = this.personas.newTeacher(f.schoolA(), "list-teacher");
		UUID mine = this.personas.newClass(f.schoolA(), "mine");
		UUID notMine = this.personas.newClass(f.schoolA(), "not mine");
		this.personas.addStudentToClass(f.schoolA(), mine, student);
		this.personas.addTeacherToClass(f.schoolA(), notMine, teacher, "ASSISTANT", null);
		assertThat(this.access.accessibleClassIds(user(student))).containsExactly(mine);
		assertThat(this.access.accessibleClassIds(user(teacher))).containsExactly(notMine);
	}

	@Test
	void anArchivedClassStaysViewableByItsMembers() {
		Fixture f = this.personas.fixture();
		UUID student = this.personas.newStudent(f.schoolA(), "archive-student");
		UUID classId = this.personas.newClass(f.schoolA(), "to-archive");
		this.personas.addStudentToClass(f.schoolA(), classId, student);
		this.jdbc.update("update classes set status = 'ARCHIVED', archived_at = now() where id = ?", classId);
		assertThat(this.access.canView(user(student), classId)).isTrue();
		assertThat(this.access.find(classId)).get().satisfies((c) -> assertThat(c.archived()).isTrue());
	}

	@Test
	void otherModulesCanWidenViewAccessWithAClassAccessRule() {
		// The guardian rule of M5 plugs in exactly like this one: a bean contributing a SQL predicate.
		Fixture f = this.personas.fixture();
		UUID outsider = this.personas.newUser("rule-outsider").userId();
		ClassAccessRule rule = (user, alias) -> user.userId().equals(outsider)
				? Optional.of(new SqlFragment(alias + ".id = :rule_class", Map.of("rule_class", f.class8A())))
				: Optional.empty();
		ClassAccess withRule = newAccess(List.of(rule));
		assertThat(withRule.canView(user(outsider), f.class8A())).isTrue();
		assertThat(withRule.canView(user(outsider), f.class7B())).isFalse();
		assertThat(withRule.accessibleClassIds(user(outsider))).containsExactly(f.class8A());
		// rules widen view access only
		assertThat(withRule.canTeach(user(outsider), f.class8A())).isFalse();
		assertThat(withRule.canManage(user(outsider), f.class8A())).isFalse();
		// the default instance is unaffected
		assertThat(this.access.canView(user(outsider), f.class8A())).isFalse();
	}

	private ClassAccess newAccess(List<ClassAccessRule> rules) {
		try {
			var ctor = Class.forName("com.nemaz.school.academic.application.DefaultClassAccess")
				.getDeclaredConstructor(JdbcClient.class, AccessControl.class, List.class);
			ctor.setAccessible(true);
			return (ClassAccess) ctor.newInstance(this.jdbcClient, this.accessControl, rules);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Test
	void schoolAdministratorRoleIsGrantedAtSchoolScope() {
		// guards the fixture assumption of the tests above
		Fixture f = this.personas.fixture();
		assertThat(this.assignments.activeGrants(this.personas.get(Name.SCHOOL_ADMIN_A).userId()))
			.anySatisfy((g) -> {
				assertThat(g.roleCode()).isEqualTo(RoleCodes.SCHOOL_ADMIN);
				assertThat(g.scope()).isEqualTo(AccessScope.school(f.schoolA()));
			});
	}

}
