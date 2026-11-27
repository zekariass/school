package com.nemaz.school.tenancy;

import java.time.Instant;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.subscriptions.AssignSubscription;
import com.nemaz.school.subscriptions.SubscriptionService;
import com.nemaz.school.subscriptions.SubscriptionStatus;
import com.nemaz.school.tenancy.application.PlatformConfigurationService;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The R-33 precedence rules: school, organization, country, platform, definition default; flag AND entitlement. */
@IntegrationTest
class ConfigurationResolutionTest extends TenancyTestSupport {

	private static final String BADGES = "engagement.badges.enabled";

	private static final String NEEDS_WORK = "engagement.needs_work.enabled";

	@Autowired
	SettingsService settings;

	@Autowired
	FeatureService features;

	@Autowired
	PlatformConfigurationService configuration;

	@Autowired
	SubscriptionService subscriptions;

	// --- settings ---------------------------------------------------------------------

	@Test
	void settingFallsBackToTheDefinitionDefault() {
		UUID school = newSchool();
		SettingValue value = this.settings.resolve(NEEDS_WORK, SettingScope.school(school));
		assertThat(value.value().asBoolean()).isTrue(); // decision D2
		assertThat(value.source()).isEqualTo(ConfigSource.DEFAULT);
		assertThat(this.settings.getBoolean("engagement.leaderboard.enabled", SettingScope.school(school))).isFalse();
		assertThat(this.settings.getInt("messaging.student.min_age", SettingScope.school(school))).isEmpty();
		assertThat(this.settings.getString("messaging.student.teacher_direct.initiator", SettingScope.school(school)))
			.contains("TEACHER_ONLY");
		assertThat(this.settings.getStringList("messaging.group_create.roles", SettingScope.school(school)))
			.containsExactly("TEACHER", "SCHOOL_ADMIN");
	}

	@Test
	void precedenceRunsPlatformCountryOrganizationSchool() {
		UUID org = newOrganization("ET");
		UUID school = newSchool(org);
		SettingScope atSchool = SettingScope.school(school);

		set(BADGES, SettingScope.platform(), false);
		assertThat(effective(BADGES, atSchool)).isEqualTo(new Resolved(false, ConfigSource.PLATFORM));

		set(BADGES, SettingScope.country("ET"), true);
		assertThat(effective(BADGES, atSchool)).isEqualTo(new Resolved(true, ConfigSource.COUNTRY));

		set(BADGES, SettingScope.organization(org), false);
		assertThat(effective(BADGES, atSchool)).isEqualTo(new Resolved(false, ConfigSource.ORGANIZATION));

		set(BADGES, SettingScope.school(school), true);
		assertThat(effective(BADGES, atSchool)).isEqualTo(new Resolved(true, ConfigSource.SCHOOL));

		// removing overrides walks back up the chain
		this.settings.clear(BADGES, SettingScope.school(school), null);
		assertThat(effective(BADGES, atSchool)).isEqualTo(new Resolved(false, ConfigSource.ORGANIZATION));
		this.settings.clear(BADGES, SettingScope.organization(org), null);
		assertThat(effective(BADGES, atSchool)).isEqualTo(new Resolved(true, ConfigSource.COUNTRY));
		this.settings.clear(BADGES, SettingScope.country("ET"), null);
		assertThat(effective(BADGES, atSchool)).isEqualTo(new Resolved(false, ConfigSource.PLATFORM));
		this.settings.clear(BADGES, SettingScope.platform(), null);
		assertThat(effective(BADGES, atSchool)).isEqualTo(new Resolved(true, ConfigSource.DEFAULT));
	}

	@Test
	void aSchoolWithoutOrganizationSkipsThatLevel() {
		UUID org = newOrganization("ET");
		UUID independent = newSchool();
		UUID member = newSchool(org);
		set(BADGES, SettingScope.organization(org), false);
		assertThat(effective(BADGES, SettingScope.school(independent))).isEqualTo(new Resolved(true, ConfigSource.DEFAULT));
		assertThat(effective(BADGES, SettingScope.school(member))).isEqualTo(new Resolved(false, ConfigSource.ORGANIZATION));
	}

	@Test
	void overridesAreScopedToTheirOwnCountryOrganizationAndSchool() {
		UUID orgA = newOrganization("ET");
		UUID orgB = newOrganization("ET");
		UUID inA = newSchool(orgA);
		UUID inB = newSchool(orgB);
		UUID other = newSchool();
		set(BADGES, SettingScope.organization(orgA), false);
		set(BADGES, SettingScope.school(other), false);
		set(BADGES, SettingScope.country("KE"), false); // another country: never applies to ET schools
		assertThat(effective(BADGES, SettingScope.school(inA)).value()).isFalse();
		assertThat(effective(BADGES, SettingScope.school(inB)).value()).isTrue();
		assertThat(effective(BADGES, SettingScope.school(other)).value()).isFalse();
		assertThat(effective(BADGES, SettingScope.school(inB)).source()).isEqualTo(ConfigSource.DEFAULT);
	}

	@Test
	void settingsResolveAtHigherScopesToo() {
		UUID org = newOrganization("ET");
		set(BADGES, SettingScope.country("ET"), false);
		assertThat(effective(BADGES, SettingScope.organization(org))).isEqualTo(new Resolved(false, ConfigSource.COUNTRY));
		assertThat(effective(BADGES, SettingScope.country("ET"))).isEqualTo(new Resolved(false, ConfigSource.COUNTRY));
		assertThat(effective(BADGES, SettingScope.country("KE"))).isEqualTo(new Resolved(true, ConfigSource.DEFAULT));
		assertThat(effective(BADGES, SettingScope.platform())).isEqualTo(new Resolved(true, ConfigSource.DEFAULT));
	}

	@Test
	void writesAreValidatedAgainstTheDefinition() {
		UUID school = newSchool();
		SettingScope scope = SettingScope.school(school);
		assertApi(() -> this.settings.set("no.such.key", scope, this.json.readTree("true"), null), "setting.unknown_key");
		assertApi(() -> this.settings.set(BADGES, scope, this.json.readTree("\"yes\""), null), "setting.invalid_value");
		assertApi(() -> this.settings.set("messaging.student.teacher_direct.initiator", scope,
				this.json.readTree("\"NOBODY\""), null), "setting.invalid_value");
		assertApi(() -> this.settings.set("messaging.student.min_age", scope, this.json.readTree("99"), null),
				"setting.invalid_value");
		assertApi(() -> this.settings.set("notifications.quiet_hours", scope,
				this.json.readTree("{\"start\":\"25:00\",\"end\":\"06:00\"}"), null), "setting.invalid_value");
		assertApi(() -> this.settings.set("feed.parent_contributions.allowed_media", scope,
				this.json.readTree("[\"DOCUMENT\"]"), null), "setting.invalid_value");
		assertApi(() -> this.settings.set(BADGES, SettingScope.school(UUID.randomUUID()), this.json.readTree("true"), null),
				"school.not_found");
		assertApi(() -> this.settings.set(BADGES, SettingScope.country("ZZ"), this.json.readTree("true"), null),
				"country.not_found");

		// valid values, including nulls and objects
		this.settings.set("messaging.student.min_age", scope, this.json.readTree("13"), null);
		assertThat(this.settings.getInt("messaging.student.min_age", scope)).contains(13);
		this.settings.set("messaging.student.min_age", scope, this.json.nullNode(), null);
		assertThat(this.settings.getInt("messaging.student.min_age", scope)).isEmpty();
		this.settings.set("notifications.quiet_hours", scope, this.json.readTree("{\"start\":\"20:30\",\"end\":\"05:00\"}"), null);
		assertThat(this.settings.getObject("notifications.quiet_hours", scope, QuietHours.class))
			.contains(new QuietHours("20:30", "05:00"));
	}

	@Test
	void settingsCanOnlyBeSetAtAllowedScopes() {
		// every seeded definition allows all four scopes; restrict one to prove the check
		this.jdbc.update("update setting_definitions set allowed_scopes = '{PLATFORM,COUNTRY}' where key = ?", BADGES);
		try {
			assertApi(() -> this.settings.set(BADGES, SettingScope.school(newSchool()), this.json.readTree("false"), null),
					"setting.scope_not_allowed");
			this.settings.set(BADGES, SettingScope.country("ET"), this.json.readTree("false"), null);
		}
		finally {
			this.jdbc.update("update setting_definitions set allowed_scopes = '{PLATFORM,COUNTRY,ORGANIZATION,SCHOOL}' where key = ?",
					BADGES);
		}
	}

	@Test
	void settingTypedAccessorsRejectWrongTypes() {
		SettingScope scope = SettingScope.school(newSchool());
		assertThatThrownBy(() -> this.settings.getInt(BADGES, scope)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> this.settings.getBoolean("messaging.student.teacher_direct.initiator", scope))
			.isInstanceOf(IllegalStateException.class);
	}

	// --- features ---------------------------------------------------------------------

	@Test
	void featureFlagPrecedenceAndFreePlanEntitlement() {
		UUID org = newOrganization("ET");
		UUID school = newSchool(org);

		// no subscription => SCHOOL_FREE: attendance entitled and on by default
		assertThat(this.features.isEnabled("attendance", school)).isTrue();
		// library is off by default in the definition and not in the free plan
		assertThat(this.features.isEnabled("library", school)).isFalse();

		// a flag cannot grant what the plan does not include
		this.configuration.setFeatureOverride("library", SettingScope.school(school), true, null, null);
		assertThat(this.features.isEnabled("library", school)).isFalse();

		// flag precedence: school beats organization beats country beats platform
		this.configuration.setFeatureOverride("attendance", SettingScope.platform(), false, null, null);
		assertThat(this.features.isEnabled("attendance", school)).isFalse();
		this.configuration.setFeatureOverride("attendance", SettingScope.country("ET"), true, null, null);
		assertThat(this.features.isEnabled("attendance", school)).isTrue();
		this.configuration.setFeatureOverride("attendance", SettingScope.organization(org), false, null, null);
		assertThat(this.features.isEnabled("attendance", school)).isFalse();
		this.configuration.setFeatureOverride("attendance", SettingScope.school(school), true, null, null);
		assertThat(this.features.isEnabled("attendance", school)).isTrue();
		this.configuration.clearFeatureOverride("attendance", SettingScope.school(school), null);
		assertThat(this.features.isEnabled("attendance", school)).isFalse();
	}

	@Test
	void entitlementGatesTheFlagWhenAPlanIsAssigned() {
		UUID school = newSchool();
		this.configuration.setFeatureOverride("library", SettingScope.school(school), true, null, null);
		assertThat(this.features.isEnabled("library", school)).isFalse(); // free plan

		this.subscriptions.assignToSchool(school, new AssignSubscription("SCHOOL_BASIC", null, null), null);
		assertThat(this.features.isEnabled("library", school)).isTrue(); // plan entitles, flag enables
		assertThat(this.features.isEnabled("payments", school)).isFalse(); // plan does not include payments
		this.configuration.setFeatureOverride("library", SettingScope.school(school), false, null, null);
		assertThat(this.features.isEnabled("library", school)).isFalse(); // plan entitles, flag disables

		// downgrading removes what the flag still enables
		this.configuration.setFeatureOverride("library", SettingScope.school(school), true, null, null);
		assertThat(this.features.isEnabled("library", school)).isTrue();
		this.subscriptions.assignToSchool(school, new AssignSubscription("SCHOOL_FREE", null, null), null);
		assertThat(this.features.isEnabled("library", school)).isFalse();
	}

	@Test
	void expiredOrCancelledSubscriptionsFallBackToTheFreePlan() {
		UUID school = newSchool();
		this.subscriptions.assignToSchool(school, new AssignSubscription("SCHOOL_PREMIUM", null, null), null);
		assertThat(this.subscriptions.entitlementsFor(school).planCode()).isEqualTo("SCHOOL_PREMIUM");

		// period ended in the past (not yet marked EXPIRED by a job): not live
		this.jdbc.update("update subscriptions set current_period_end = now() - interval '1 day', "
				+ "current_period_start = now() - interval '30 days' where school_id = ?", school);
		assertThat(this.subscriptions.entitlementsFor(school).planCode()).isEqualTo("SCHOOL_FREE");
		assertThat(this.subscriptions.entitlementsFor(school).freeFallback()).isTrue();

		this.jdbc.update("update subscriptions set current_period_end = null, status = 'CANCELLED' where school_id = ?", school);
		assertThat(this.subscriptions.entitlementsFor(school).planCode()).isEqualTo("SCHOOL_FREE");
	}

	@Test
	void limitsComeFromThePlan() {
		UUID school = newSchool();
		assertThat(this.features.limit("max_students", school)).hasValue(100);
		this.subscriptions.assignToSchool(school, new AssignSubscription("SCHOOL_BASIC", SubscriptionStatus.TRIALING,
				Instant.now().plusSeconds(86_400)), null);
		assertThat(this.features.limit("max_students", school)).hasValue(500);
		this.subscriptions.assignToSchool(school, new AssignSubscription("ENTERPRISE", null, null), null);
		assertThat(this.features.limit("max_students", school)).isEmpty(); // unlimited
		assertApi(() -> this.features.isEnabled("no_such_feature", school), "feature.unknown_key");
	}

	@Test
	void featureOverrideConfigIsReturnedInTheResolvedView() {
		UUID school = newSchool();
		this.configuration.setFeatureOverride("attendance", SettingScope.school(school), true,
				this.json.readTree("{\"mode\":\"PER_PERIOD\"}"), null);
		var state = this.configuration.schoolConfiguration(school).features().get("attendance");
		assertThat(state.flagSource()).isEqualTo(ConfigSource.SCHOOL);
		assertThat(state.configJson()).contains("PER_PERIOD");
		assertApi(() -> this.configuration.setFeatureOverride("attendance", SettingScope.school(school), true,
				this.json.readTree("[1]"), null), "feature.invalid_config");
	}

	// --- helpers ----------------------------------------------------------------------

	private void set(String key, SettingScope scope, boolean value) {
		this.settings.set(key, scope, this.json.readTree(String.valueOf(value)), null);
	}

	private Resolved effective(String key, SettingScope scope) {
		SettingValue v = this.settings.resolve(key, scope);
		return new Resolved(v.value().asBoolean(), v.source());
	}

	private static void assertApi(Runnable call, String code) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, (e) -> assertThat(e.code()).isEqualTo(code));
	}

	record Resolved(boolean value, ConfigSource source) {
	}

	record QuietHours(String start, String end) {
	}

}
