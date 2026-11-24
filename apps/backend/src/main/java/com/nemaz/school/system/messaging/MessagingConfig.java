package com.nemaz.school.system.messaging;

import java.util.Set;

import com.nemaz.school.system.SchoolProperties;
import com.nemaz.school.system.SmsSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Selects the SMS provider from {@code school.sms.provider}. Only {@code console} exists
 * until a real provider is integrated; it is refused outside the local and test profiles
 * unless {@code school.sms.allow-console=true} (e.g. a staging environment without SMS).
 * No provider means the application does not start: one-time codes could not be delivered.
 */
@Configuration(proxyBeanMethods = false)
class MessagingConfig {

	private static final Logger log = LoggerFactory.getLogger(MessagingConfig.class);

	private static final Set<String> CONSOLE_PROFILES = Set.of("local", "test");

	@Bean
	SmsSender smsSender(SchoolProperties props, Environment environment) {
		String provider = props.sms().provider();
		if ("console".equalsIgnoreCase(provider)) {
			boolean devProfile = environment.matchesProfiles(CONSOLE_PROFILES.toArray(String[]::new));
			if (!devProfile && !props.sms().allowConsole()) {
				throw new IllegalStateException("school.sms.provider=console is only allowed in the local and test "
						+ "profiles; set school.sms.allow-console=true to use it elsewhere (never in production)");
			}
			if (!devProfile) {
				log.warn("SMS messages are written to the log (school.sms.allow-console=true)");
			}
			return new ConsoleSmsSender();
		}
		throw new IllegalStateException("No SMS provider configured: set school.sms.provider (supported: console)");
	}

}
