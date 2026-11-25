package com.nemaz.school.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** Test doubles shared by every integration test (imported by {@code @IntegrationTest}). */
@TestConfiguration(proxyBeanMethods = false)
@Import(Personas.class)
public class SecurityTestConfiguration {

	@Bean
	@Primary
	MutableClock testClock() {
		return new MutableClock();
	}

	/** Replaces the configured push provider: tests read what would have been pushed. */
	@Bean
	@Primary
	RecordingPushSender recordingPushSender() {
		return new RecordingPushSender();
	}

	@Bean
	DomainEventRecorder domainEventRecorder() {
		return new DomainEventRecorder();
	}

	@Bean
	@Primary
	RecordingMessages recordingMessages() {
		return new RecordingMessages();
	}

}
