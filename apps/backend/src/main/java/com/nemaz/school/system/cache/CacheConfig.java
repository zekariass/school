package com.nemaz.school.system.cache;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Spring Cache on Redis (TTL and cache names come from {@code spring.cache.*}).
 * <ul>
 * <li>A Redis failure must not take the API down: cache errors are logged and the call
 * falls through to the database ({@link LoggingCacheErrorHandler}). Staleness after a
 * failed eviction is bounded by the TTL.</li>
 * <li>Clearing a cache uses SCAN, not KEYS, so it does not block a shared Redis.</li>
 * <li>Writes are immediate: by default Spring Data Redis 4.2 may complete puts and evictions
 * after the call returns, so a read right after an eviction could still see the old value.
 * Configuration changes must be visible to the very next read.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
class CacheConfig implements CachingConfigurer {

	@Override
	public CacheErrorHandler errorHandler() {
		return new LoggingCacheErrorHandler(false);
	}

	@Bean
	RedisCacheManagerBuilderCustomizer scanBasedCacheClearing(RedisConnectionFactory connectionFactory) {
		return (builder) -> builder.cacheWriter(RedisCacheWriter.create(connectionFactory,
				(writer) -> writer.batchStrategy(BatchStrategies.scan(1000)).immediateWrites()));
	}

}
