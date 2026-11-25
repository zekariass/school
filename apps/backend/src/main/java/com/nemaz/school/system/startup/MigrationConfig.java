package com.nemaz.school.system.startup;

import org.flywaydb.core.api.FlywayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code school.migrations.mode=validate} (the default of {@code staging} and {@code prod}): the application
 * never changes the schema. At startup it checks that every migration, including the repeatable
 * seed scripts, has been applied exactly as shipped, and refuses to start otherwise. Migrations are applied once
 * per deploy by {@code DatabaseMigrator} ({@code --migrate}) before the new version is started, so several
 * instances can start at the same time without racing on the schema. The default {@code migrate} (local, tests)
 * keeps Flyway's usual behaviour.
 */
@Configuration(proxyBeanMethods = false)
class MigrationConfig {

	private static final Logger log = LoggerFactory.getLogger(MigrationConfig.class);

	@Bean
	@ConditionalOnProperty(name = "school.migrations.mode", havingValue = "validate")
	FlywayMigrationStrategy validateOnlyMigrationStrategy() {
		return (flyway) -> {
			try {
				flyway.validate();
				log.info("Database schema is up to date (migrations are applied by the migrate job, not at startup)");
			}
			catch (FlywayException ex) {
				throw new IllegalStateException("The database schema does not match this version "
						+ "(school.migrations.mode=validate). Run the migrate job first (the image with --migrate), "
						+ "then start the application. Cause: " + ex.getMessage(), ex);
			}
		};
	}

}
