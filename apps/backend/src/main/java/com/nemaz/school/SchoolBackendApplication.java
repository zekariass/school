package com.nemaz.school;

import com.nemaz.school.system.DatabaseMigrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Modular monolith (spec §119): each direct subpackage is a Spring Modulith application
 * module. Boundaries are verified by ModularityTest.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SchoolBackendApplication {

	public static void main(String[] args) {
		if (DatabaseMigrator.requested(args, System.getenv())) {
			// One-shot migrate mode of the image: Flyway only, no Spring context, then exit (a deploy step).
			System.exit(DatabaseMigrator.run(System.getenv(), System.out));
		}
		SpringApplication.run(SchoolBackendApplication.class, args);
	}

}
