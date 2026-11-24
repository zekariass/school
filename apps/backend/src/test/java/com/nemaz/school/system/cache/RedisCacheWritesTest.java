package com.nemaz.school.system.cache;

import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.tenancy.application.SchoolConfiguration;
import com.nemaz.school.tenancy.application.SchoolConfigurationCache;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test: Spring Data Redis 4.2 completes cache writes after the call returns
 * unless {@code immediateWrites()} is configured, which let a read right after an
 * eviction see the old value (about 40% of the time). Eviction on configuration changes
 * relies on read-your-writes, so this must stay immediate.
 */
@IntegrationTest
class RedisCacheWritesTest {

	@Autowired
	CacheManager caches;

	@Test
	void putsAndEvictionsAreVisibleToTheNextRead() {
		Cache cache = this.caches.getCache(SchoolConfigurationCache.CACHE_NAME);
		for (int i = 0; i < 300; i++) {
			UUID key = UUID.randomUUID();
			cache.put(key, new SchoolConfiguration(key, "SCHOOL_FREE", true, Map.of(), Map.of()));
			assertThat(cache.get(key)).as("read after put #%d", i).isNotNull();
			cache.evict(key);
			assertThat(cache.get(key)).as("read after evict #%d", i).isNull();
		}
		cache.clear();
	}

}
