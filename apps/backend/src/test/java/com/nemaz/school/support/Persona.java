package com.nemaz.school.support;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A test user with a real access token, issued through the application's TokenService (so
 * requests exercise the full JWT path: signature, issuer, audience, deny-list, account
 * status, role grants). Tokens are re-issued when the test clock has moved on.
 */
public final class Persona {

	private static final Duration REISSUE_AFTER = Duration.ofMinutes(10);

	private final String name;

	private final UUID userId;

	private final Supplier<String> issuer;

	private final MutableClock clock;

	private String token;

	private Instant issuedAt;

	Persona(String name, UUID userId, Supplier<String> issuer, MutableClock clock) {
		this.name = name;
		this.userId = userId;
		this.issuer = issuer;
		this.clock = clock;
	}

	public String name() {
		return this.name;
	}

	public UUID userId() {
		return this.userId;
	}

	/** A currently valid access token. */
	public synchronized String accessToken() {
		Instant now = this.clock.instant();
		if (this.token == null || now.isBefore(this.issuedAt) || now.isAfter(this.issuedAt.plus(REISSUE_AFTER))) {
			this.token = this.issuer.get();
			this.issuedAt = now;
		}
		return this.token;
	}

	/** Adds {@code Authorization: Bearer <token>} to a MockMvc request. */
	public RequestPostProcessor bearer() {
		return (request) -> {
			request.addHeader("Authorization", "Bearer " + accessToken());
			return request;
		};
	}

	@Override
	public String toString() {
		return this.name;
	}

}
