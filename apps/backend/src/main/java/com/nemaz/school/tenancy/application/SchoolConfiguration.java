package com.nemaz.school.tenancy.application;

import java.util.Map;
import java.util.UUID;

import com.nemaz.school.tenancy.ConfigSource;

/**
 * The fully resolved configuration of one school: what the Redis cache stores (as JSON,
 * see SchoolConfigurationCacheConfig). Plain values only: JSON setting values are kept as
 * text so the cache entry does not depend on how JSON node types serialize.
 */
public record SchoolConfiguration(UUID schoolId, String planCode, boolean planIsFreeFallback,
		Map<String, FeatureState> features, Map<String, SettingState> settings) {

	/**
	 * @param enabled flag enabled AND entitled
	 * @param flagEnabled what the override chain (or definition default) says
	 * @param flagSource level that supplied {@code flagEnabled}
	 * @param entitled the plan includes the feature
	 * @param limit the plan's numeric limit, null = unlimited
	 * @param configJson {@code config} of the winning override, null if none
	 */
	public record FeatureState(String key, boolean enabled, boolean flagEnabled, ConfigSource flagSource,
			boolean entitled, Long limit, String configJson) {
	}

	public record SettingState(String key, String module, String valueJson, ConfigSource source) {
	}

}
