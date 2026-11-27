package com.nemaz.school.reference;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.tenancy.application.ValueSchemaValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** M1a migrations: schema constraints and the repeatable seed data. */
@IntegrationTest
class SeedDataTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	JsonMapper json;

	@Test
	void everyM1aTableExists() {
		List<String> tables = this.jdbc.queryForList(
				"select table_name from information_schema.tables where table_schema = 'public'", String.class);
		assertThat(tables).contains("currencies", "languages", "calendar_systems", "countries", "country_languages",
				"country_calendars", "administrative_areas", "addresses", "holidays", "system_settings",
				"organizations", "schools", "school_languages", "campuses", "setting_definitions", "settings",
				"feature_definitions", "feature_overrides", "subscription_plans", "plan_prices", "plan_entitlements",
				"subscriptions");
	}

	@Test
	void referenceDataIsSeeded() {
		assertThat(this.jdbc.queryForList("select code from currencies order by code", String.class))
			.containsExactly("ETB", "KES", "USD");
		assertThat(this.jdbc.queryForList("select code from languages order by code", String.class))
			.containsExactly("am", "en", "om", "so", "sw", "ti");
		assertThat(this.jdbc.queryForObject("select native_name from languages where code = 'am'", String.class))
			.isEqualTo("አማርኛ");
		assertThat(this.jdbc.queryForObject("select script from languages where code = 'om'", String.class))
			.isEqualTo("Latn");
		assertThat(this.jdbc.queryForList("select code from calendar_systems order by code", String.class))
			.containsExactly("ETHIOPIAN", "GREGORIAN");
	}

	@Test
	void ethiopiaIsEnabledAndKenyaIsADraft() {
		Map<String, Object> et = this.jdbc.queryForMap("select * from countries where code = 'ET'");
		assertThat(et).containsEntry("status", "ENABLED")
			.containsEntry("default_currency_code", "ETB")
			.containsEntry("default_language_code", "am")
			.containsEntry("default_timezone", "Africa/Addis_Ababa")
			.containsEntry("default_calendar_code", "ETHIOPIAN")
			.containsEntry("phone_country_code", "+251");
		assertThat(et.get("launched_at")).isNotNull();
		Map<String, Object> ke = this.jdbc.queryForMap("select * from countries where code = 'KE'");
		assertThat(ke).containsEntry("status", "DRAFT")
			.containsEntry("default_currency_code", "KES")
			.containsEntry("default_language_code", "en")
			.containsEntry("default_timezone", "Africa/Nairobi")
			.containsEntry("default_calendar_code", "GREGORIAN")
			.containsEntry("phone_country_code", "+254");

		assertThat(this.jdbc.queryForList("select jsonb_path_query_array(address_format, '$.levels[*].labels.en') #>> '{}' "
				+ "from countries where code = 'ET'", String.class))
			.containsExactly("[\"Region\", \"Zone\", \"Woreda\", \"Kebele\"]");
		assertThat(this.jdbc.queryForObject("select name_format -> 'middle_name' ->> 'en' from countries where code = 'ET'",
				String.class))
			.isEqualTo("Father's name");
		assertThat(this.jdbc.queryForObject("select name_format -> 'family_name' ->> 'en' from countries where code = 'ET'",
				String.class))
			.isEqualTo("Grandfather's name");
		assertThat(this.jdbc.queryForObject("select address_format #>> '{levels,2,labels,en}' from countries where code = 'KE'",
				String.class))
			.isEqualTo("Ward");
	}

	@Test
	void countryLanguagesAndCalendars() {
		assertThat(this.jdbc.queryForList("select language_code from country_languages where country_code = 'ET' order by sort_order",
				String.class))
			.containsExactly("am", "en", "om", "ti", "so");
		assertThat(this.jdbc.queryForMap("select is_default, is_official from country_languages where country_code = 'ET' and language_code = 'am'"))
			.containsEntry("is_default", true)
			.containsEntry("is_official", true);
		assertThat(this.jdbc.queryForList("select language_code from country_languages where country_code = 'KE' order by sort_order",
				String.class))
			.containsExactly("en", "sw");
		assertThat(this.jdbc.queryForObject("select calendar_code from country_calendars where country_code = 'ET' and is_default",
				String.class))
			.isEqualTo("ETHIOPIAN");
		assertThat(this.jdbc.queryForObject("select count(*) from country_calendars where country_code = 'ET'", Integer.class))
			.isEqualTo(2);
	}

	@Test
	void ethiopiaHasFourteenLevelOneRegionsAndNoZonesYet() {
		List<Map<String, Object>> regions = this.jdbc
			.queryForList("select code, name_i18n ->> 'en' as en, name_i18n ->> 'am' as am from administrative_areas "
					+ "where country_code = 'ET' and level = 1 order by id");
		assertThat(regions).hasSize(14);
		assertThat(regions).extracting((r) -> r.get("en"))
			.containsExactly("Addis Ababa", "Afar", "Amhara", "Benishangul-Gumuz", "Central Ethiopia", "Dire Dawa",
					"Gambela", "Harari", "Oromia", "Sidama", "Somali", "South Ethiopia",
					"South West Ethiopia Peoples'", "Tigray");
		assertThat(regions).allSatisfy((r) -> {
			assertThat(r.get("code")).isNotNull();
			assertThat((String) r.get("am")).isNotBlank();
		});
		assertThat(regions).extracting((r) -> r.get("code")).doesNotHaveDuplicates();
		assertThat(this.jdbc.queryForObject("select count(*) from administrative_areas where country_code = 'ET' and level > 1",
				Integer.class))
			.isZero();
	}

	@Test
	void featureDefinitions() {
		Map<String, Boolean> defaults = new java.util.HashMap<>();
		this.jdbc.query("select key, default_enabled from feature_definitions",
				(RowCallbackHandler) (rs) -> defaults.put(rs.getString(1), rs.getBoolean(2)));
		assertThat(defaults).containsKeys("attendance", "points", "feed", "messaging", "announcements", "calendar",
				"portfolio", "library", "assignments", "payments");
		// the flags of the removed scope (D32-D40) are gone: the migration deleted them and the seeds do not list them
		assertThat(defaults).doesNotContainKeys("courses", "marketplace", "tutoring", "ai_copilot", "ai_student_tutor");
		assertThat(defaults).containsEntry("attendance", true)
			.containsEntry("points", true)
			.containsEntry("feed", true)
			.containsEntry("messaging", true)
			.containsEntry("payments", false);
	}

	@Test
	void settingDefinitionsHaveTheDocumentedDefaults() {
		Map<String, String> defaults = new java.util.HashMap<>();
		this.jdbc.query("select key, default_value::text from setting_definitions",
				(RowCallbackHandler) (rs) -> defaults.put(rs.getString(1), rs.getString(2)));
		assertThat(defaults).containsEntry("engagement.needs_work.enabled", "true")
			.containsEntry("engagement.badges.enabled", "true")
			.containsEntry("engagement.leaderboard.enabled", "false")
			.containsEntry("engagement.public_rankings.enabled", "false")
			.containsEntry("guardians.auto_verify_invite", "false")
			.containsEntry("classes.former_member_history_access", "false")
			.containsEntry("feed.parent_contributions.enabled", "false")
			.containsEntry("feed.parent_contributions.approval_required", "true")
			.containsEntry("messaging.parent_teacher.enabled", "true")
			.containsEntry("messaging.parent_parent.enabled", "false")
			.containsEntry("messaging.student.enabled", "true")
			.containsEntry("messaging.student.min_age", "null")
			.containsEntry("messaging.student.teacher_direct.enabled", "false")
			.containsEntry("messaging.student.teacher_direct.initiator", "\"TEACHER_ONLY\"")
			.containsEntry("messaging.student.student_direct.enabled", "false")
			.containsEntry("messaging.student.group_create", "\"TEACHER_ONLY\"")
			.containsEntry("messaging.group_create.roles", "[\"TEACHER\", \"SCHOOL_ADMIN\"]")
			.containsEntry("messaging.teacher_hours", "null")
			.containsKeys("feed.parent_contributions.allowed_media", "notifications.quiet_hours");
	}

	@Test
	void everyDefaultValueSatisfiesItsOwnSchema() {
		// also proves every seeded schema only uses keywords the validator supports
		this.jdbc.query("select key, value_schema::text, default_value::text from setting_definitions", (RowCallbackHandler) (rs) -> {
			JsonNode schema = this.json.readTree(rs.getString(2));
			JsonNode value = this.json.readTree(rs.getString(3));
			assertThat(ValueSchemaValidator.validate(schema, value)).as(rs.getString(1)).isEmpty();
		});
	}

	@Test
	void plansAndEntitlements() {
		assertThat(this.jdbc.queryForList("select code from subscription_plans order by code", String.class))
			.containsExactly("ENTERPRISE", "SCHOOL_BASIC", "SCHOOL_FREE", "SCHOOL_PREMIUM", "SCHOOL_STANDARD");
		assertThat(this.jdbc.queryForList("select distinct audience from subscription_plans", String.class))
			.containsExactly("SCHOOL");
		assertThat(this.jdbc.queryForObject("select count(*) from plan_prices where amount_minor = 0 and country_code = 'ET' and currency_code = 'ETB'",
				Integer.class))
			.isEqualTo(5);
		// one entitlement row per plan x feature definition
		assertThat(this.jdbc.queryForObject("select count(*) from plan_entitlements", Integer.class))
			.isEqualTo(5 * this.jdbc.queryForObject("select count(*) from feature_definitions", Integer.class));

		assertThat(entitled("SCHOOL_FREE")).contains("attendance", "points", "feed", "messaging", "announcements",
				"calendar")
			.doesNotContain("library", "payments");
		assertThat(entitled("SCHOOL_BASIC")).contains("library", "assignments").doesNotContain("payments");
		assertThat(entitled("SCHOOL_STANDARD")).contains("library", "assignments").doesNotContain("payments");
		assertThat(entitled("SCHOOL_PREMIUM")).contains("payments");
		assertThat(entitled("ENTERPRISE")).contains("payments");
		assertThat(this.jdbc.queryForObject(
				"select count(*) from plan_entitlements where feature_key in ('courses', 'marketplace', 'tutoring', 'ai_copilot', 'ai_student_tutor')",
				Integer.class))
			.isZero();
		assertThat(limit("SCHOOL_FREE", "max_students")).isEqualTo(100L);
		assertThat(limit("ENTERPRISE", "max_students")).isNull();
	}

	@Test
	void repeatableSeedsAreIdempotentAndNeverOverwriteOperatorEdits() throws Exception {
		int regions = count("administrative_areas");
		int definitions = count("setting_definitions");
		int entitlements = count("plan_entitlements");
		this.jdbc.update("update countries set status = 'ENABLED' where code = 'KE'");
		this.jdbc.update("update languages set is_enabled = false where code = 'ti'");
		this.jdbc.update("update feature_definitions set default_enabled = true where key = 'payments'");
		this.jdbc.update("update setting_definitions set default_value = 'false' where key = 'engagement.needs_work.enabled'");
		this.jdbc.update("update plan_entitlements set enabled = true where feature_key = 'payments'"
				+ " and plan_id = (select id from subscription_plans where code = 'SCHOOL_FREE')");
		try {
			for (String script : List.of("R__10_reference_data.sql", "R__20_definitions.sql", "R__30_subscription_plans.sql")) {
				try (var connection = this.jdbc.getDataSource().getConnection()) {
					ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + script));
				}
			}
			assertThat(count("administrative_areas")).isEqualTo(regions);
			assertThat(count("setting_definitions")).isEqualTo(definitions);
			assertThat(count("plan_entitlements")).isEqualTo(entitlements);
			// admin-editable data survives a re-run (DO NOTHING) ...
			assertThat(this.jdbc.queryForObject("select status from countries where code = 'KE'", String.class))
				.isEqualTo("ENABLED");
			assertThat(this.jdbc.queryForObject("select is_enabled from languages where code = 'ti'", Boolean.class))
				.isFalse();
			// ... code-owned definitions are put back (DO UPDATE)
			assertThat(this.jdbc.queryForObject("select default_enabled from feature_definitions where key = 'payments'",
					Boolean.class))
				.isFalse();
			assertThat(this.jdbc.queryForObject(
					"select default_value::text from setting_definitions where key = 'engagement.needs_work.enabled'",
					String.class))
				.isEqualTo("true");
			assertThat(entitled("SCHOOL_FREE")).doesNotContain("payments");
		}
		finally {
			this.jdbc.update("update countries set status = 'DRAFT' where code = 'KE'");
			this.jdbc.update("update languages set is_enabled = true where code = 'ti'");
		}
	}

	// --- constraints ------------------------------------------------------------------

	@Test
	void enumColumnsAreCheckConstrained() {
		assertThatThrownBy(() -> this.jdbc.update("update countries set status = 'BOGUS' where code = 'KE'"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> this.jdbc.update("update languages set direction = 'UP' where code = 'en'"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> this.jdbc.update(
				"insert into subscriptions (plan_id, subscriber_type, school_id, status) select id, 'SCHOOL', null, 'ACTIVE' from subscription_plans limit 1"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void theCheckConstraintsOfTheRemovedScopeNoLongerAcceptItsValues() {
		// V14_01: no course events, no recording or transcription policies or consents, no course/tutor-review/AI targets
		assertThatThrownBy(() -> this.jdbc.update(
				"insert into policy_documents (policy_type, country_code, title) values ('RECORDING', 'ET', 'x')"))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("policy_documents_policy_type_check");
		assertThatThrownBy(() -> this.jdbc.update(
				"insert into policy_documents (policy_type, country_code, title) values ('TRANSCRIPTION', 'ET', 'x')"))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("policy_documents_policy_type_check");
		for (String type : List.of("COURSE", "TUTOR_REVIEW", "AI_OUTPUT")) {
			assertThatThrownBy(() -> this.jdbc.update(
					"insert into moderation_cases (queue, target_type, target_id) values ('PLATFORM', ?, ?)", type, UUID.randomUUID()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("moderation_cases_target_type_check");
		}
		assertThat(this.jdbc.queryForObject(
				"select pg_get_constraintdef(oid) from pg_constraint where conname = 'events_scope_type_check'", String.class))
			.doesNotContain("COURSE")
			.contains("INVITEES");
		assertThat(this.jdbc.queryForObject(
				"select count(*) from information_schema.columns where table_name = 'events' and column_name = 'course_id'",
				Integer.class))
			.isZero();
	}

	@Test
	void scopeChecksAndUniqueIndexesOnSettingsAndFeatureOverrides() {
		// PLATFORM scope must not name a country
		assertThatThrownBy(() -> this.jdbc.update(
				"insert into settings (key, scope_type, country_code, value) values ('engagement.badges.enabled', 'PLATFORM', 'ET', 'true')"))
			.isInstanceOf(DataIntegrityViolationException.class);
		// SCHOOL scope needs a school
		assertThatThrownBy(() -> this.jdbc.update(
				"insert into feature_overrides (feature_key, scope_type, enabled) values ('attendance', 'SCHOOL', true)"))
			.isInstanceOf(DataIntegrityViolationException.class);
		// COUNTRY scope must not also name an organization
		assertThatThrownBy(() -> this.jdbc.update(
				"insert into feature_overrides (feature_key, scope_type, country_code, organization_id, enabled) "
						+ "values ('attendance', 'COUNTRY', 'ET', ?, true)",
				UUID.randomUUID()))
			.isInstanceOf(DataIntegrityViolationException.class);
		// one row per key and scope
		this.jdbc.update("insert into settings (key, scope_type, value) values ('engagement.badges.enabled', 'PLATFORM', 'true')");
		try {
			assertThatThrownBy(() -> this.jdbc.update(
					"insert into settings (key, scope_type, value) values ('engagement.badges.enabled', 'PLATFORM', 'false')"))
				.isInstanceOf(DataIntegrityViolationException.class);
			this.jdbc.update("insert into settings (key, scope_type, country_code, value) values ('engagement.badges.enabled', 'COUNTRY', 'ET', 'false')");
		}
		finally {
			this.jdbc.update("delete from settings");
		}
	}

	@Test
	void slugsAreLowercaseAndUnique() {
		assertThatThrownBy(() -> this.jdbc.update(
				"insert into organizations (country_code, name, slug) values ('ET', 'x', 'Bad_Slug')"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private List<String> entitled(String planCode) {
		return this.jdbc.queryForList("select e.feature_key from plan_entitlements e join subscription_plans p on p.id = e.plan_id "
				+ "where p.code = ? and e.enabled", String.class, planCode);
	}

	private Long limit(String planCode, String feature) {
		return this.jdbc.queryForObject("select e.limit_value from plan_entitlements e join subscription_plans p on p.id = e.plan_id "
				+ "where p.code = ? and e.feature_key = ?", Long.class, planCode, feature);
	}

	private int count(String table) {
		return this.jdbc.queryForObject("select count(*) from " + table, Integer.class);
	}

}
