package com.nemaz.school.guardians.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code school.guardians.*}. {@code inviteBaseUrl} is the web app origin used in invitation links
 * ({@code {inviteBaseUrl}/{locale}/invite/{token}}); deployed environments must set
 * {@code SCHOOL_GUARDIANS_INVITE_BASE_URL} (startup fails without it), local and test use
 * {@code http://localhost:3100}. {@code inviteTtl} is how long an SMS or email invitation stays valid.
 */
@ConfigurationProperties("school.guardians")
public record GuardiansProperties(String inviteBaseUrl, @DefaultValue("P14D") Duration inviteTtl) {

	public GuardiansProperties {
		if (inviteBaseUrl == null || inviteBaseUrl.isBlank() || inviteBaseUrl.startsWith("${")
				|| !(inviteBaseUrl.startsWith("https://") || inviteBaseUrl.startsWith("http://"))) {
			throw new IllegalStateException(
					"school.guardians.invite-base-url (SCHOOL_GUARDIANS_INVITE_BASE_URL) must be set to the web app origin");
		}
		inviteBaseUrl = inviteBaseUrl.endsWith("/") ? inviteBaseUrl.substring(0, inviteBaseUrl.length() - 1)
				: inviteBaseUrl;
	}

}
