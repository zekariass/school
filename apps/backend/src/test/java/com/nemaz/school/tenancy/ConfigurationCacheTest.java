package com.nemaz.school.tenancy;

import java.time.Duration;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.subscriptions.AssignSubscription;
import com.nemaz.school.subscriptions.SubscriptionService;
import com.nemaz.school.tenancy.application.PlatformConfigurationService;
import com.nemaz.school.tenancy.application.SchoolConfigurationCache;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Redis caching of resolved school configuration and its eviction on every kind of change. */
@IntegrationTest
class ConfigurationCacheTest extends TenancyTestSupport {

	@Autowired
	SettingsService settings;

	@Autowired
	FeatureService features;

	@Autowired
	PlatformConfigurationService configuration;

	@Autowired
	SubscriptionService subscriptions;

	@Autowired
	StringRedisTemplate redis;

	Cache cache() {
		return this.caches.getCache(SchoolConfigurationCache.CACHE_NAME);
	}

	@Test
	void resolvedConfigurationIsCachedInRedisWithAShortTtl() {
		UUID school = newSchool();
		assertThat(cache().get(school)).isNull();

		assertThat(this.features.isEnabled("attendance", school)).isTrue();

		assertThat(cache().get(school)).isNotNull();
		Long ttl = this.redis.getExpire(SchoolConfigurationCache.CACHE_NAME + "::" + school);
		assertThat(ttl).isBetween(1L, 300L);
		// stored as readable JSON, not Java serialization
		assertThat(this.redis.opsForValue().get(SchoolConfigurationCache.CACHE_NAME + "::" + school))
			.contains("\"planCode\":\"SCHOOL_FREE\"")
			.contains("\"attendance\"");
	}

	@Test
	void cachedValuesAreServedUntilEvicted() {
		UUID school = newSchool();
		assertThat(this.features.isEnabled("attendance", school)).isTrue();

		// a change that bypasses the services (and therefore eviction) is not seen: proves the cache is used
		this.jdbc.update("insert into feature_overrides (feature_key, scope_type, enabled) values ('attendance', 'PLATFORM', false)");
		assertThat(this.features.isEnabled("attendance", school)).isTrue();

		cache().evict(school);
		assertThat(this.features.isEnabled("attendance", school)).isFalse();
	}

	@Test
	void schoolScopeChangesAreVisibleImmediately() {
		UUID school = newSchool();
		UUID other = newSchool();
		assertThat(this.features.isEnabled("attendance", school)).isTrue();
		assertThat(this.features.isEnabled("attendance", other)).isTrue();

		this.configuration.setFeatureOverride("attendance", SettingScope.school(school), false, null, null);

		assertThat(this.features.isEnabled("attendance", school)).isFalse();
		assertThat(this.features.isEnabled("attendance", other)).isTrue();
		assertThat(cache().get(other)).as("other schools keep their entries").isNotNull();
	}

	@Test
	void organizationChangesEvictOnlyThatOrganizationsSchools() {
		UUID org = newOrganization("ET");
		UUID member = newSchool(org);
		UUID outsider = newSchool();
		this.features.isEnabled("attendance", member);
		this.features.isEnabled("attendance", outsider);

		this.settings.set("engagement.badges.enabled", SettingScope.organization(org), this.json.readTree("false"), null);

		assertThat(cache().get(member)).isNull();
		assertThat(cache().get(outsider)).isNotNull();
		assertThat(this.settings.getBoolean("engagement.badges.enabled", SettingScope.school(member))).isFalse();
	}

	@Test
	void countryAndPlatformChangesClearEverySchool() {
		UUID a = newSchool();
		UUID b = newSchool();
		this.features.isEnabled("attendance", a);
		this.features.isEnabled("attendance", b);

		this.configuration.setFeatureOverride("attendance", SettingScope.country("ET"), false, null, null);
		assertThat(cache().get(a)).isNull();
		assertThat(cache().get(b)).isNull();
		assertThat(this.features.isEnabled("attendance", a)).isFalse();

		this.features.isEnabled("attendance", b);
		this.settings.set("engagement.badges.enabled", SettingScope.platform(), this.json.readTree("false"), null);
		assertThat(cache().get(a)).isNull();
		assertThat(cache().get(b)).isNull();
	}

	@Test
	void subscriptionChangesEvictTheSchool() {
		UUID school = newSchool();
		this.configuration.setFeatureOverride("library", SettingScope.school(school), true, null, null);
		assertThat(this.features.isEnabled("library", school)).isFalse();
		assertThat(cache().get(school)).isNotNull();

		this.subscriptions.assignToSchool(school, new AssignSubscription("SCHOOL_STANDARD", null, null), null);

		assertThat(cache().get(school)).isNull();
		assertThat(this.features.isEnabled("library", school)).isTrue();
	}

	@Test
	void removingAnOverrideEvictsToo() {
		UUID school = newSchool();
		this.settings.set("engagement.badges.enabled", SettingScope.school(school), this.json.readTree("false"), null);
		assertThat(this.settings.getBoolean("engagement.badges.enabled", SettingScope.school(school))).isFalse();
		this.settings.clear("engagement.badges.enabled", SettingScope.school(school), null);
		assertThat(this.settings.getBoolean("engagement.badges.enabled", SettingScope.school(school))).isTrue();
	}

	@Test
	void evictionListenersCompleteTheirEventPublications() {
		UUID school = newSchool();
		this.configuration.setFeatureOverride("points", SettingScope.school(school), false, null, null);
		await().atMost(Duration.ofSeconds(10))
			.untilAsserted(() -> assertThat(this.jdbc.queryForObject(
					"select count(*) from event_publication where completion_date is null "
							+ "and serialized_event like ?",
					Integer.class, "%" + school + "%"))
				.isZero());
	}

}
