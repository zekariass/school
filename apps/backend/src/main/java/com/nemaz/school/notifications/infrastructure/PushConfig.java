package com.nemaz.school.notifications.infrastructure;

import java.util.Set;

import com.nemaz.school.notifications.domain.PushSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Selects the push provider from {@code school.push.provider}: {@code expo} (the Expo push
 * service) or {@code log} (local and test profiles, or anywhere with {@code school.push.allow-log=true}).
 * No provider means the application does not start: users would silently never get pushes.
 */
@Configuration(proxyBeanMethods = false)
class PushConfig {

	private static final Logger log = LoggerFactory.getLogger(PushConfig.class);

	private static final Set<String> LOG_PROFILES = Set.of("local", "test");

	@Bean
	PushSender pushSender(PushProperties props, Environment environment, JsonMapper json) {
		String provider = props.provider();
		if ("expo".equalsIgnoreCase(provider)) {
			return new ExpoPushSender(props.expo(), json);
		}
		if ("log".equalsIgnoreCase(provider)) {
			boolean devProfile = environment.matchesProfiles(LOG_PROFILES.toArray(String[]::new));
			if (!devProfile && !props.allowLog()) {
				throw new IllegalStateException("school.push.provider=log is only allowed in the local and test "
						+ "profiles; set school.push.allow-log=true to use it elsewhere (never in production)");
			}
			if (!devProfile) {
				log.warn("Push notifications are only written to the log (school.push.allow-log=true)");
			}
			return new LoggingPushSender();
		}
		throw new IllegalStateException(
				"No push provider configured: set school.push.provider (SCHOOL_PUSH_PROVIDER; supported: expo, log)");
	}

}
