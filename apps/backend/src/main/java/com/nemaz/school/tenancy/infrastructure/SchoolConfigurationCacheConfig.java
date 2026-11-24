package com.nemaz.school.tenancy.infrastructure;

import java.time.Duration;

import com.nemaz.school.tenancy.application.SchoolConfiguration;
import com.nemaz.school.tenancy.application.SchoolConfigurationCache;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

/**
 * Stores resolved school configurations in Redis as JSON rather than with Java
 * serialization: readable in {@code redis-cli}, tolerant of added fields, and no Java
 * deserialization of data read from a shared Redis. Same TTL as the other caches
 * ({@code spring.cache.redis.time-to-live}).
 */
@Configuration(proxyBeanMethods = false)
class SchoolConfigurationCacheConfig {

	@Bean
	RedisCacheManagerBuilderCustomizer schoolConfigurationCacheAsJson(JsonMapper json,
			@Value("${spring.cache.redis.time-to-live:5m}") Duration ttl) {
		RedisCacheConfiguration configuration = RedisCacheConfiguration.defaultCacheConfig()
			.entryTtl(ttl)
			.disableCachingNullValues()
			.serializeValuesWith(SerializationPair
				.fromSerializer(new JacksonJsonRedisSerializer<>(json, SchoolConfiguration.class)));
		return (builder) -> builder.withCacheConfiguration(SchoolConfigurationCache.CACHE_NAME, configuration);
	}

}
