package com.nemaz.school.authorization.infrastructure;

import java.time.Duration;

import com.nemaz.school.authorization.application.GrantCache;
import com.nemaz.school.authorization.application.GrantCache.GrantSet;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

/**
 * Role grants per user in Redis, as JSON. Evicted after every assignment change; the TTL only
 * bounds staleness after a role's permissions change in the seed (deploy) or an eviction failed.
 */
@Configuration(proxyBeanMethods = false)
class GrantCacheConfig {

	static final Duration TTL = Duration.ofMinutes(10);

	@Bean
	RedisCacheManagerBuilderCustomizer roleGrantsCacheAsJson(JsonMapper json) {
		RedisCacheConfiguration configuration = RedisCacheConfiguration.defaultCacheConfig()
			.entryTtl(TTL)
			.disableCachingNullValues()
			.serializeValuesWith(SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(json, GrantSet.class)));
		return (builder) -> builder.withCacheConfiguration(GrantCache.CACHE_NAME, configuration);
	}

}
