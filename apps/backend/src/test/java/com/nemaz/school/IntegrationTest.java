package com.nemaz.school;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Full-context integration test against PostgreSQL 18 (Testcontainers). All classes using
 * this annotation share one cached Spring context and one database container, so keep the
 * properties identical here rather than per test class.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(properties = {
		// OpenApiSpecTest reads /v3/api-docs
		"springdoc.api-docs.enabled=true",
		// scheduled jobs are invoked explicitly by tests, never in the background
		"school.scheduling.enabled=false",
		"school.events.resubmit-older-than=PT0S" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {

}
