package com.nemaz.school.common.time;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Inject {@link Clock} instead of calling {@code Instant.now()} so time-dependent logic
 * (expiry, quiet hours, story lifetimes) is testable. Always UTC: display timezones are a
 * presentation concern (spec §64).
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
