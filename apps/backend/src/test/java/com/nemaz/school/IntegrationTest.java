package com.nemaz.school;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.nemaz.school.support.SecurityTestConfiguration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full-context integration test against PostgreSQL 18 (Testcontainers). All classes using
 * this annotation share one cached Spring context and one database container, so keep the
 * properties identical here rather than per test class. The server listens on a random port
 * (WebSocket tests connect to it; MockMvc still drives the HTTP tests).
 *
 * <p>
 * The "test" profile (application-test.yaml) supplies development secrets (ephemeral JWT key,
 * OTP pepper, console SMS). {@link SecurityTestConfiguration} adds the persona harness
 * ({@code support.Personas}), a controllable clock and recording SMS/email senders.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		// OpenApiSpecTest reads /v3/api-docs
		"springdoc.api-docs.enabled=true",
		// scheduled jobs are invoked explicitly by tests, never in the background
		"school.scheduling.enabled=false",
		"school.events.resubmit-older-than=PT0S" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, SecurityTestConfiguration.class })
public @interface IntegrationTest {

}
