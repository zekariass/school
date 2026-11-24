package com.nemaz.school.identity.infrastructure;

import java.time.Duration;
import java.util.Collection;
import java.util.UUID;

import com.nemaz.school.identity.application.IdentityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Revoked session families in Redis, so their access tokens stop working immediately rather
 * than at expiry. An entry lives as long as an access token can (lifetime plus clock skew);
 * after that every token of the family has expired anyway. If Redis is unreachable, checks
 * pass (logged) and revoked tokens live until their expiry, at most the access-token lifetime.
 */
@Component
public class SessionDenyList {

	private static final Logger log = LoggerFactory.getLogger(SessionDenyList.class);

	private static final String PREFIX = "auth:deny:sid:";

	private final StringRedisTemplate redis;

	private final Duration ttl;

	SessionDenyList(StringRedisTemplate redis, IdentityProperties props) {
		this.redis = redis;
		this.ttl = props.jwt().accessTokenTtl().plus(props.jwt().clockSkew());
	}

	public void deny(Collection<UUID> sessionIds) {
		for (UUID id : sessionIds) {
			try {
				this.redis.opsForValue().set(PREFIX + id, "1", this.ttl);
			}
			catch (DataAccessException ex) {
				log.error("Could not deny-list session {}: its access tokens stay valid until they expire", id, ex);
			}
		}
	}

	public boolean isDenied(UUID sessionId) {
		try {
			return Boolean.TRUE.equals(this.redis.hasKey(PREFIX + sessionId));
		}
		catch (DataAccessException ex) {
			log.warn("Session deny-list unavailable; accepting token: {}", ex.getMessage());
			return false;
		}
	}

}
