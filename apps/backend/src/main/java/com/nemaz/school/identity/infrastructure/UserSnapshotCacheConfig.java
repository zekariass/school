package com.nemaz.school.identity.infrastructure;

import java.time.Duration;

import com.nemaz.school.identity.application.UserSnapshotCache;
import com.nemaz.school.identity.application.UserSnapshotCache.UserSnapshot;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

/** Account status per user in Redis as JSON, for at most a minute (evicted on change). */
@Configuration(proxyBeanMethods = false)
class UserSnapshotCacheConfig {

	static final Duration TTL = Duration.ofSeconds(60);

	@Bean
	RedisCacheManagerBuilderCustomizer userSnapshotCacheAsJson(JsonMapper json) {
		RedisCacheConfiguration configuration = RedisCacheConfiguration.defaultCacheConfig()
			.entryTtl(TTL)
			.disableCachingNullValues()
			.serializeValuesWith(
					SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(json, UserSnapshot.class)));
		return (builder) -> builder.withCacheConfiguration(UserSnapshotCache.CACHE_NAME, configuration);
	}

}
