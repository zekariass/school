package com.nemaz.school.support;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.nemaz.school.authorization.RoleCodes;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.identity.application.TokenService;
import com.nemaz.school.identity.domain.AuthMethod;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The authorization test harness (Implementation Plan M1 step 6): a fixture tenancy tree and
 * one user per administrative persona, each with a real signed access token.
 *
 * <pre>
 * ET (enabled)                       KE (draft)
 *  └─ organization ORG                └─ school KE (independent)
 *      ├─ school A ── campus A
 *      └─ school B
 * </pre>
 *
 * Personas ({@link Name}): platform admin, country admins of ET and KE, the organization's
 * admin, admins of schools A and B, the admin of campus A, a signed-in user without roles and
 * a suspended user; and, since M3a, school members (no role assignments, R-02): in school A the LEAD
 * ({@code TEACHER_LEAD_A}) and a SUBJECT_TEACHER ({@code TEACHER_SUBJECT_A}) of class 8A, a teacher
 * who leads 7B only ({@code TEACHER_OTHER_A}), students ACTIVE in 8A ({@code STUDENT_A1}) and 7B
 * ({@code STUDENT_A2}), a student who was in 8A and left it ({@code STUDENT_FORMER_A}); in school B
 * a student of class 9B ({@code STUDENT_B1}); since M5 guardians (STANDARD accounts without roles or memberships): an
 * ACTIVE guardian of student A1 ({@code GUARDIAN_A1}, every permission flag on), a PENDING one
 * ({@code GUARDIAN_PENDING_A1}), a custody-restricted ACTIVE one ({@code GUARDIAN_RESTRICTED_A1}), a REVOKED one
 * ({@code GUARDIAN_REVOKED_A1}), an ACTIVE guardian of student A2 in 7B ({@code GUARDIAN_OF_A2}) and an adult without
 * any link ({@code UNRELATED_ADULT}). The fixture is created once per test JVM (lazily, with unique slugs and
 * phones) and never changed by the harness afterwards; tests must not modify these rows.
 *
 * <p>
 * <b>Extending (later milestones):</b> add a constant to {@link Name} and a line in
 * {@link #createPersonas()}, or create ad-hoc users with {@link #withRole}, {@link #newUser}
 * or {@link #withPlatformPermissions} (a test-only role holding exactly the given
 * permissions platform-wide). Requests: {@code mvc.perform(get(url).with(persona.bearer()))}.
 * The data-driven endpoint matrix lives in {@code authorization.AuthorizationMatrixTest}.
 */
public class Personas {

	public enum Name {

		PLATFORM_ADMIN, COUNTRY_ADMIN_ET, COUNTRY_ADMIN_KE, ORG_ADMIN, SCHOOL_ADMIN_A, SCHOOL_ADMIN_B, CAMPUS_ADMIN_A,
		PLAIN_USER, SUSPENDED_USER, TEACHER_LEAD_A, TEACHER_SUBJECT_A, TEACHER_OTHER_A, STUDENT_A1, STUDENT_A2,
		STUDENT_FORMER_A, STUDENT_B1, GUARDIAN_A1, GUARDIAN_PENDING_A1, GUARDIAN_RESTRICTED_A1, GUARDIAN_REVOKED_A1,
		GUARDIAN_OF_A2, UNRELATED_ADULT

	}

	/**
	 * Ids of the fixture tenancy tree and, in schools A and B, the academic fixture: {@code yearA} (current),
	 * {@code gradeA} ("Grade 8"), {@code subjectA} (Mathematics), classes {@code class8A}, {@code class7B}
	 * (school A) and {@code class9B} (school B).
	 */
	public record Fixture(UUID organization, UUID schoolA, UUID schoolB, UUID schoolKe, UUID campusA, UUID yearA,
			UUID gradeA, UUID subjectA, UUID class8A, UUID class7B, UUID class9B) {
	}

	private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 1_000_000L);

	private static volatile Personas current;

	private final JdbcTemplate jdbc;

	private final TokenService tokens;

	private final MutableClock clock;

	private final TransactionTemplate tx;

	private final Map<Name, Persona> personas = new EnumMap<>(Name.class);

	private final Map<String, Persona> permissionPersonas = new ConcurrentHashMap<>();

	private Fixture fixture;

	Personas(JdbcTemplate jdbc, TokenService tokens, MutableClock clock, TransactionTemplate tx) {
		this.jdbc = jdbc;
		this.tokens = tokens;
		this.clock = clock;
		this.tx = tx;
		current = this;
	}

	/** The harness of the running test context (for static helpers). */
	public static Personas current() {
		if (current == null) {
			throw new IllegalStateException("No @IntegrationTest context has started");
		}
		return current;
	}

	public synchronized Fixture fixture() {
		if (this.fixture == null) {
			this.fixture = createFixture();
			createPersonas();
		}
		return this.fixture;
	}

	public synchronized Persona get(Name name) {
		fixture();
		return this.personas.get(name);
	}

	public Persona platformAdmin() {
		return get(Name.PLATFORM_ADMIN);
	}

	public Persona plainUser() {
		return get(Name.PLAIN_USER);
	}

	/** A new ACTIVE user (verified phone) holding {@code roleCode} at {@code scope}. */
	public Persona withRole(String label, String roleCode, AccessScope scope) {
		UUID userId = createUser(label, "ACTIVE");
		grant(userId, roleCode, scope);
		return persona(label, userId);
	}

	/** A new ACTIVE user without roles. */
	public Persona newUser(String label) {
		return persona(label, createUser(label, "ACTIVE"));
	}

	/** A user whose test-only role holds exactly {@code permissions} at platform scope (memoized per set). */
	public Persona withPlatformPermissions(String... permissions) {
		TreeSet<String> sorted = new TreeSet<>(Arrays.asList(permissions));
		String key = String.join(",", sorted);
		return this.permissionPersonas.computeIfAbsent(key, (k) -> {
			if (sorted.isEmpty()) {
				return newUser("no-permissions");
			}
			String role = "TEST_" + Integer.toHexString(k.hashCode()).toUpperCase() + "_" + SEQUENCE.incrementAndGet();
			this.jdbc.update("""
					insert into roles (code, name_i18n, scope_level, is_system, description)
					values (?, '{"en": "Test role"}'::jsonb, 'PLATFORM', false, ?)""", role, "Test role: " + k);
			sorted.forEach((p) -> this.jdbc.update("insert into role_permissions (role_code, permission_code) values (?, ?)",
					role, p));
			return withRole("perm:" + k, role, AccessScope.platform());
		});
	}

	/** Inserts an ACTIVE assignment directly (fixtures; the API path is tested separately). */
	public void grant(UUID userId, String roleCode, AccessScope scope) {
		this.jdbc.update("""
				insert into role_assignments (user_id, role_code, scope_type, country_code, organization_id, school_id,
				                              campus_id, status, granted_at)
				values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', now())""", userId, roleCode, scope.level().name(),
				scope.countryCode(), scope.organizationId(), scope.schoolId(), scope.campusId());
	}

	/** Creates a user directly: verified ET phone, given status. Returns the id. */
	public UUID createUser(String label, String status) {
		UUID id = Ids.newId();
		this.jdbc.update("""
				insert into users (id, given_name, display_name, phone_e164, phone_verified_at, status)
				values (?, ?, ?, ?, now(), ?)""", id, label, label, newPhone(), status);
		return id;
	}

	/** A unique, valid Ethiopian mobile number. */
	public static String newPhone() {
		return String.format("+2519%08d", SEQUENCE.incrementAndGet() % 100_000_000L);
	}

	/** A unique slug. */
	public static String newSlug(String prefix) {
		return prefix + "-" + SEQUENCE.incrementAndGet();
	}

	/** A persona (real token) for an existing user. */
	public Persona personaFor(String label, UUID userId) {
		return persona(label, userId);
	}

	private Persona persona(String name, UUID userId) {
		return new Persona(name, userId, () -> this.tx.execute(
				(status) -> this.tokens.startSession(userId, AuthMethod.OTP).accessToken()), this.clock);
	}

	private Fixture createFixture() {
		UUID organization = Ids.newId();
		this.jdbc.update("insert into organizations (id, country_code, name, slug) values (?, 'ET', 'Persona Org', ?)",
				organization, newSlug("persona-org"));
		UUID schoolA = school("ET", organization, "Persona School A");
		UUID schoolB = school("ET", organization, "Persona School B");
		UUID schoolKe = school("KE", null, "Persona School KE");
		UUID campusA = Ids.newId();
		this.jdbc.update("insert into campuses (id, school_id, name, code) values (?, ?, 'Campus A', 'A')", campusA,
				schoolA);
		UUID yearA = year(schoolA);
		UUID gradeA = Ids.newId();
		this.jdbc.update("insert into grade_levels (id, school_id, name, code, sequence) values (?, ?, 'Grade 8', '8', 8)",
				gradeA, schoolA);
		UUID subjectA = Ids.newId();
		this.jdbc.update("insert into subjects (id, school_id, name, code) values (?, ?, 'Mathematics', 'MATH')", subjectA,
				schoolA);
		UUID class8A = schoolClass(schoolA, yearA, gradeA, "8A");
		UUID class7B = schoolClass(schoolA, yearA, null, "7B");
		UUID class9B = schoolClass(schoolB, year(schoolB), null, "9B");
		return new Fixture(organization, schoolA, schoolB, schoolKe, campusA, yearA, gradeA, subjectA, class8A, class7B,
				class9B);
	}

	private UUID year(UUID schoolId) {
		UUID id = Ids.newId();
		this.jdbc.update("""
				insert into academic_years (id, school_id, name, starts_on, ends_on, status, is_current)
				values (?, ?, 'Persona year', date '2025-09-01', date '2026-07-31', 'ACTIVE', true)""", id, schoolId);
		return id;
	}

	private UUID schoolClass(UUID schoolId, UUID yearId, UUID gradeId, String name) {
		UUID id = Ids.newId();
		this.jdbc.update("""
				insert into classes (id, school_id, academic_year_id, grade_level_id, name) values (?, ?, ?, ?, ?)""", id,
				schoolId, yearId, gradeId, name);
		return id;
	}

	/** A new independent ACTIVE school in Ethiopia (default language am) with a current academic year; returns its id. */
	public UUID newSchool(String label) {
		UUID id = school("ET", null, label);
		year(id);
		return id;
	}

	/** A new school administrator of the school. */
	public Persona schoolAdmin(UUID schoolId, String label) {
		return withRole(label, RoleCodes.SCHOOL_ADMIN, AccessScope.school(schoolId));
	}

	/** The current academic year of the school. */
	public UUID currentYear(UUID schoolId) {
		return this.jdbc.queryForObject("select id from academic_years where school_id = ? and is_current", UUID.class,
				schoolId);
	}

	/** A class of the school in its current year; returns its id (no members). */
	public UUID newClass(UUID schoolId, String name) {
		return schoolClass(schoolId, currentYear(schoolId), null, name);
	}

	/** An ACTIVE teacher of the school (staff row and profile, no role assignment). */
	public UUID newTeacher(UUID schoolId, String label) {
		UUID id = createUser(label, "ACTIVE");
		this.jdbc.update("insert into teacher_profiles (user_id) values (?)", id);
		this.jdbc.update("""
				insert into school_staff (id, school_id, user_id, staff_type, status, started_on)
				values (?, ?, ?, 'TEACHER', 'ACTIVE', current_date)""", Ids.newId(), schoolId, id);
		return id;
	}

	/** An ACTIVE non-teaching staff member of the school ({@code staffType} ADMINISTRATOR or SUPPORT_STAFF; no role assignment). */
	public UUID newStaff(UUID schoolId, String label, String staffType) {
		UUID id = createUser(label, "ACTIVE");
		this.jdbc.update("""
				insert into school_staff (id, school_id, user_id, staff_type, status, started_on)
				values (?, ?, ?, ?, 'ACTIVE', current_date)""", Ids.newId(), schoolId, id, staffType);
		return id;
	}

	/** Sets a user's date of birth (fixtures; the API never returns it). */
	public void setDateOfBirth(UUID userId, java.time.LocalDate dateOfBirth) {
		this.jdbc.update("update users set date_of_birth = ? where id = ?", java.sql.Date.valueOf(dateOfBirth), userId);
	}

	/** An ACTIVE managed student of the school (no phone or email, no class). */
	public UUID newStudent(UUID schoolId, String label) {
		UUID id = Ids.newId();
		this.jdbc.update("""
				insert into users (id, account_type, given_name, display_name, status) values (?, 'MANAGED', ?, ?, 'ACTIVE')""",
				id, label, label);
		this.jdbc.update("insert into student_profiles (user_id) values (?)", id);
		this.jdbc.update("""
				insert into school_students (id, school_id, student_id, status, admission_on)
				values (?, ?, ?, 'ACTIVE', current_date)""", Ids.newId(), schoolId, id);
		return id;
	}

	/** Puts a teacher or student into a class with the given status (fixtures; the API path is tested separately). */
	public void addTeacherToClass(UUID schoolId, UUID classId, UUID teacherId, String role, UUID subjectId) {
		this.jdbc.update("""
				insert into class_teachers (id, class_id, school_id, teacher_id, role, subject_id, status)
				values (?, ?, ?, ?, ?, ?, 'ACTIVE')""", Ids.newId(), classId, schoolId, teacherId, role, subjectId);
	}

	public void addStudentToClass(UUID schoolId, UUID classId, UUID studentId) {
		this.jdbc.update("""
				insert into class_students (id, class_id, school_id, student_id, status) values (?, ?, ?, ?, 'ACTIVE')""",
				Ids.newId(), classId, schoolId, studentId);
	}

	/**
	 * A new independent ACTIVE school in {@code country} (any status) with a current academic year: language, zone, calendar
	 * and currency are the country's defaults, read from the {@code countries} row like the platform does.
	 */
	public UUID newSchoolIn(String country, String label) {
		UUID id = school(country, null, label);
		year(id);
		return id;
	}

	private UUID school(String country, UUID organization, String name) {
		UUID id = Ids.newId();
		Map<String, Object> c = this.jdbc.queryForMap("""
				select default_language_code, default_timezone, default_calendar_code, default_currency_code
				from countries where code = ?""", country);
		this.jdbc.update("""
				insert into schools (id, country_code, organization_id, name, slug, default_language_code, timezone,
				                     default_calendar_code, currency_code, status)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')""", id, country, organization, name, newSlug("persona-school"),
				c.get("default_language_code"), c.get("default_timezone"), c.get("default_calendar_code"),
				c.get("default_currency_code"));
		this.jdbc.update("insert into school_languages (school_id, language_code, is_default) values (?, ?, true)", id,
				c.get("default_language_code"));
		return id;
	}

	private void createPersonas() {
		Fixture f = this.fixture;
		add(Name.PLATFORM_ADMIN, RoleCodes.PLATFORM_ADMIN, AccessScope.platform());
		add(Name.COUNTRY_ADMIN_ET, RoleCodes.COUNTRY_ADMIN, AccessScope.country("ET"));
		add(Name.COUNTRY_ADMIN_KE, RoleCodes.COUNTRY_ADMIN, AccessScope.country("KE"));
		add(Name.ORG_ADMIN, RoleCodes.ORGANIZATION_ADMIN, AccessScope.organization(f.organization()));
		add(Name.SCHOOL_ADMIN_A, RoleCodes.SCHOOL_ADMIN, AccessScope.school(f.schoolA()));
		add(Name.SCHOOL_ADMIN_B, RoleCodes.SCHOOL_ADMIN, AccessScope.school(f.schoolB()));
		add(Name.CAMPUS_ADMIN_A, RoleCodes.CAMPUS_ADMIN, AccessScope.campus(f.schoolA(), f.campusA()));
		UUID leadA = newTeacher(f.schoolA(), "teacher-lead-a");
		addTeacherToClass(f.schoolA(), f.class8A(), leadA, "LEAD", null);
		this.personas.put(Name.TEACHER_LEAD_A, persona("teacher-lead-a", leadA));
		UUID subjectTeacher = newTeacher(f.schoolA(), "teacher-subject-a");
		addTeacherToClass(f.schoolA(), f.class8A(), subjectTeacher, "SUBJECT_TEACHER", f.subjectA());
		this.personas.put(Name.TEACHER_SUBJECT_A, persona("teacher-subject-a", subjectTeacher));
		UUID otherTeacher = newTeacher(f.schoolA(), "teacher-other-a");
		addTeacherToClass(f.schoolA(), f.class7B(), otherTeacher, "LEAD", null);
		this.personas.put(Name.TEACHER_OTHER_A, persona("teacher-other-a", otherTeacher));
		UUID a1 = newStudent(f.schoolA(), "student-a1");
		addStudentToClass(f.schoolA(), f.class8A(), a1);
		this.personas.put(Name.STUDENT_A1, persona("student-a1", a1));
		UUID a2 = newStudent(f.schoolA(), "student-a2");
		addStudentToClass(f.schoolA(), f.class7B(), a2);
		this.personas.put(Name.STUDENT_A2, persona("student-a2", a2));
		UUID former = newStudent(f.schoolA(), "student-former-a");
		this.jdbc.update("""
				insert into class_students (id, class_id, school_id, student_id, status, left_at, left_reason)
				values (?, ?, ?, ?, 'FORMER', now(), 'TRANSFERRED_CLASS')""", Ids.newId(), f.class8A(), f.schoolA(), former);
		this.personas.put(Name.STUDENT_FORMER_A, persona("student-former-a", former));
		UUID b1 = newStudent(f.schoolB(), "student-b1");
		addStudentToClass(f.schoolB(), f.class9B(), b1);
		this.personas.put(Name.STUDENT_B1, persona("student-b1", b1));
		this.personas.put(Name.GUARDIAN_A1, guardian("guardian-a1", a1, "ACTIVE", false));
		this.personas.put(Name.GUARDIAN_PENDING_A1, guardian("guardian-pending-a1", a1, "PENDING", false));
		this.personas.put(Name.GUARDIAN_RESTRICTED_A1, guardian("guardian-restricted-a1", a1, "ACTIVE", true));
		this.personas.put(Name.GUARDIAN_REVOKED_A1, guardian("guardian-revoked-a1", a1, "REVOKED", false));
		this.personas.put(Name.GUARDIAN_OF_A2, guardian("guardian-of-a2", a2, "ACTIVE", false));
		this.personas.put(Name.UNRELATED_ADULT, newUser("unrelated-adult"));
		this.personas.put(Name.PLAIN_USER, newUser("plain-user"));
		UUID suspended = createUser("suspended-user", "SUSPENDED");
		this.personas.put(Name.SUSPENDED_USER, persona("suspended-user", suspended));
	}

	private Persona guardian(String label, UUID studentId, String status, boolean restricted) {
		UUID id = createUser(label, "ACTIVE");
		linkGuardian(studentId, id, status, restricted);
		return persona(label, id);
	}

	/**
	 * Links an adult to a student directly (fixtures; the invitation and verification paths are tested separately): a
	 * guardian profile and a {@code student_guardians} row in {@code status} with every permission flag on. Returns the
	 * link id.
	 */
	public UUID linkGuardian(UUID studentId, UUID guardianId, String status, boolean custodyRestricted) {
		this.jdbc.update("insert into guardian_profiles (user_id) values (?) on conflict do nothing", guardianId);
		UUID id = Ids.newId();
		this.jdbc.update("""
				insert into student_guardians (id, student_id, guardian_id, relationship_type, status, requested_via,
				                               verification_method, verified_at, custody_restricted, restriction_note,
				                               revoked_at)
				values (?, ?, ?, 'MOTHER', ?, 'ADMIN', case when ? = 'ACTIVE' then 'SCHOOL_VERIFIED' end,
				        case when ? = 'ACTIVE' then now() end, ?, case when ? then 'Court order (fixture)' end,
				        case when ? = 'REVOKED' then now() end)""", id, studentId, guardianId, status, status, status,
				custodyRestricted, custodyRestricted, status);
		return id;
	}

	private void add(Name name, String roleCode, AccessScope scope) {
		this.personas.put(name, withRole(name.name().toLowerCase(), roleCode, scope));
	}

	// --- M13: moderation and safeguarding personas ------------------------------------------------------------------

	/** A safeguarding lead of the school: an ACTIVE staff member holding the SAFEGUARDING_LEAD role at the school. */
	public Persona safeguardingLead(UUID schoolId, String label) {
		UUID id = newStaff(schoolId, label, "SUPPORT_STAFF");
		grant(id, RoleCodes.SAFEGUARDING_LEAD, AccessScope.school(schoolId));
		return persona(label, id);
	}

	/**
	 * Makes sure the school has a safeguarding lead (a school cannot switch on student messaging or contributions without one,
	 * docs/Safeguarding_Rules.md decision 3); returns true when it had to create one. Used by the test helpers that change those
	 * settings.
	 */
	public boolean ensureSafeguardingLead(UUID schoolId) {
		Integer leads = this.jdbc.queryForObject("""
				select count(*) from role_assignments where role_code = 'SAFEGUARDING_LEAD' and scope_type = 'SCHOOL'
				  and school_id = ? and status = 'ACTIVE'""", Integer.class, schoolId);
		if (leads != null && leads > 0) {
			return false;
		}
		safeguardingLead(schoolId, "auto-lead-" + SEQUENCE.incrementAndGet());
		return true;
	}

	/** True when {@code key} switching to {@code value} needs a safeguarding lead (see {@code SafeguardingSettingsGuard}). */
	public static boolean needsLead(String key, Object value) {
		boolean guarded = key.equals("feed.student_contributions.enabled") || key.equals("messaging.student.enabled")
				|| key.equals("messaging.student.teacher_direct.enabled") || key.equals("messaging.student.student_direct.enabled")
				|| key.equals("messaging.student.media_enabled");
		return (guarded && Boolean.TRUE.equals(value)) || (key.equals("messaging.student.group_create") && "STUDENTS_WITH_APPROVAL".equals(value));
	}

	/** A platform moderator (PLATFORM_MODERATOR). */
	public Persona platformModerator(String label) {
		return withRole(label, RoleCodes.PLATFORM_MODERATOR, AccessScope.platform());
	}

	/** A platform safeguarding lead (PLATFORM_SAFEGUARDING_LEAD). */
	public Persona platformLead(String label) {
		return withRole(label, RoleCodes.PLATFORM_SAFEGUARDING_LEAD, AccessScope.platform());
	}

	/** A support agent (SUPPORT_AGENT). */
	public Persona supportAgent(String label) {
		return withRole(label, RoleCodes.SUPPORT_AGENT, AccessScope.platform());
	}

	/** Stamp for tests that need "now" from the shared clock in SQL. */
	public Timestamp now() {
		return Timestamp.from(this.clock.instant());
	}

}
