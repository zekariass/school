package com.nemaz.school.common.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA auditing with the application {@link Clock}: {@code createdAt}/{@code updatedAt} on
 * {@link BaseEntity} (and {@code updatedAt} on entities that declare
 * {@code @LastModifiedDate}) are set from it, not from the JVM's system clock.
 *
 * <p>
 * JSON columns: Hibernate 7.4 picks its Jackson 3 {@code FormatMapper} automatically when
 * only Jackson 3 ({@code tools.jackson}) is on the classpath, so
 * {@code @JdbcTypeCode(SqlTypes.JSON)} fields map to real {@code jsonb} objects. That is
 * proved by {@code JsonbMappingTest}; no custom mapper is needed.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class PersistenceConfig {

	@Bean
	DateTimeProvider auditingDateTimeProvider(Clock clock) {
		return () -> Optional.of(Instant.now(clock));
	}

}
