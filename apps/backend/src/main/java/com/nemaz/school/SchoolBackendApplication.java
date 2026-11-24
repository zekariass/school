package com.nemaz.school;

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
		SpringApplication.run(SchoolBackendApplication.class, args);
	}

}
