package com.nemaz.school.tenancy.application;

import java.util.UUID;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * Redis cache in front of {@link SchoolConfigurationLoader}, keyed by school id. Entries
 * live at most the configured TTL (5 minutes) and are evicted explicitly when anything they
 * depend on changes ({@link ConfigurationCacheEvictor}). A separate bean so that the
 * {@code @Cacheable} proxy is crossed on every call.
 */
@Component
public class SchoolConfigurationCache {

	public static final String CACHE_NAME = "school-configuration";

	private final SchoolConfigurationLoader loader;

	private final CacheManager caches;

	SchoolConfigurationCache(SchoolConfigurationLoader loader, CacheManager caches) {
		this.loader = loader;
		this.caches = caches;
	}

	@Cacheable(cacheNames = CACHE_NAME, key = "#schoolId")
	public SchoolConfiguration get(UUID schoolId) {
		return this.loader.load(schoolId);
	}

	void evict(UUID schoolId) {
		Cache cache = this.caches.getCache(CACHE_NAME);
		if (cache != null) {
			cache.evict(schoolId);
		}
	}

	/** Drops every school's entry (country- or platform-wide change). */
	void clear() {
		Cache cache = this.caches.getCache(CACHE_NAME);
		if (cache != null) {
			cache.clear();
		}
	}

}
