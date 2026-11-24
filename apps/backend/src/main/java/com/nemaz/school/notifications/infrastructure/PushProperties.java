package com.nemaz.school.notifications.infrastructure;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Push delivery ({@code school.push.*}). {@code provider} is {@code expo} or {@code log}; deployed
 * environments must choose (no default, startup fails otherwise). {@code log} only writes a masked
 * line per push and is refused outside the local and test profiles unless {@code allowLog}.
 */
@ConfigurationProperties("school.push")
public record PushProperties(@DefaultValue("") String provider, @DefaultValue("false") boolean allowLog,
		@DefaultValue Expo expo) {

	/** Expo push service. {@code accessToken} is optional ({@code EXPO_ACCESS_TOKEN}, "enhanced security"). */
	public record Expo(@DefaultValue("https://exp.host") URI baseUrl, @DefaultValue("") String accessToken,
			@DefaultValue("PT10S") Duration timeout) {
	}

}
