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
 * a suspended user. The fixture is created once per test JVM (lazily, with unique slugs and
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
		PLAIN_USER, SUSPENDED_USER

	}

	/** Ids of the fixture tenancy tree. */
	public record Fixture(UUID organization, UUID schoolA, UUID schoolB, UUID schoolKe, UUID campusA) {
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
		return new Fixture(organization, schoolA, schoolB, schoolKe, campusA);
	}

	private UUID school(String country, UUID organization, String name) {
		UUID id = Ids.newId();
		boolean ethiopia = "ET".equals(country);
		this.jdbc.update("""
				insert into schools (id, country_code, organization_id, name, slug, default_language_code, timezone,
				                     default_calendar_code, currency_code, status)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')""", id, country, organization, name, newSlug("persona-school"),
				ethiopia ? "am" : "en", ethiopia ? "Africa/Addis_Ababa" : "Africa/Nairobi",
				ethiopia ? "ETHIOPIAN" : "GREGORIAN", ethiopia ? "ETB" : "KES");
		this.jdbc.update("insert into school_languages (school_id, language_code, is_default) values (?, ?, true)", id,
				ethiopia ? "am" : "en");
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
		this.personas.put(Name.PLAIN_USER, newUser("plain-user"));
		UUID suspended = createUser("suspended-user", "SUSPENDED");
		this.personas.put(Name.SUSPENDED_USER, persona("suspended-user", suspended));
	}

	private void add(Name name, String roleCode, AccessScope scope) {
		this.personas.put(name, withRole(name.name().toLowerCase(), roleCode, scope));
	}

	/** Stamp for tests that need "now" from the shared clock in SQL. */
	public Timestamp now() {
		return Timestamp.from(this.clock.instant());
	}

}
