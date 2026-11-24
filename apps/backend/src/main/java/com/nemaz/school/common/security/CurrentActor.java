package com.nemaz.school.common.security;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The acting user's id for {@code updated_by}/{@code assigned_by} columns. Users and
 * tokens arrive in M1b; until then the authentication name is used when it is a UUID
 * and the actor is empty otherwise.
 */
public final class CurrentActor {

	private CurrentActor() {
	}

	public static Optional<UUID> id() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || authentication.getName() == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(UUID.fromString(authentication.getName()));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

}
