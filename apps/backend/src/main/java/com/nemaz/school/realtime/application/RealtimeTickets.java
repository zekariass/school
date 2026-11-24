package com.nemaz.school.realtime.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.system.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Single-use tickets that let a browser open a WebSocket without an access token in the URL
 * or a header it cannot set. A ticket is 256 random bits, shown once; Redis holds only its
 * SHA-256 ({@code rt:ticket:<hash>}) with the user and session family, for 60 seconds, and it is
 * deleted atomically when redeemed ({@code GETDEL}), so a copied ticket is worthless.
 */
@Service
public class RealtimeTickets {

	public static final Duration TTL = Duration.ofSeconds(60);

	public static final String KEY_PREFIX = "rt:ticket:";

	static final int ISSUES_PER_MINUTE = 30;

	private static final Logger log = LoggerFactory.getLogger(RealtimeTickets.class);

	private final SecureRandom random = new SecureRandom();

	private final StringRedisTemplate redis;

	private final RateLimiter limiter;

	RealtimeTickets(StringRedisTemplate redis, RateLimiter limiter) {
		this.redis = redis;
		this.limiter = limiter;
	}

	public String issue(CurrentUser user) {
		this.limiter.consume("rt-ticket", user.userId().toString(), ISSUES_PER_MINUTE, Duration.ofMinutes(1),
				"realtime.rate_limited");
		byte[] bytes = new byte[32];
		this.random.nextBytes(bytes);
		String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		try {
			this.redis.opsForValue().set(key(ticket), user.userId() + ":" + user.sessionId(), TTL);
		}
		catch (DataAccessException ex) {
			log.error("Could not store a realtime ticket", ex);
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "realtime.unavailable");
		}
		return ticket;
	}

	/** Consumes a ticket: present exactly once, and only within its lifetime. */
	public Optional<Redeemed> redeem(String ticket) {
		if (ticket == null || ticket.isBlank() || ticket.length() > 128) {
			return Optional.empty();
		}
		String value;
		try {
			value = this.redis.opsForValue().getAndDelete(key(ticket));
		}
		catch (DataAccessException ex) {
			log.error("Could not read a realtime ticket", ex);
			return Optional.empty();
		}
		if (value == null) {
			return Optional.empty();
		}
		String[] parts = value.split(":");
		try {
			return Optional.of(new Redeemed(UUID.fromString(parts[0]), UUID.fromString(parts[1])));
		}
		catch (RuntimeException ex) {
			return Optional.empty();
		}
	}

	/** The Redis key of a ticket (tests use it to age a ticket). */
	public static String key(String ticket) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(StandardCharsets.UTF_8));
			return KEY_PREFIX + HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public record Redeemed(UUID userId, UUID sessionId) {
	}

}
