package com.nemaz.school.identity.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.tx.AfterCommit;
import com.nemaz.school.common.web.ClientRequests;
import com.nemaz.school.identity.domain.AuthMethod;
import com.nemaz.school.identity.domain.UserStatus;
import com.nemaz.school.identity.infrastructure.AccessTokenKeys;
import com.nemaz.school.identity.infrastructure.AuthSessionStore;
import com.nemaz.school.identity.infrastructure.AuthSessionStore.Session;
import com.nemaz.school.identity.infrastructure.SessionDenyList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Access tokens and refresh-token sessions.
 * <ul>
 * <li><b>Access token:</b> ES256 JWT, type {@code at+jwt}, 15 minutes; claims {@code iss},
 * {@code aud}, {@code sub} (user id), {@code sid} (session family), {@code iat}, {@code exp},
 * {@code jti}. No roles or permissions: those are loaded per request.</li>
 * <li><b>Refresh token:</b> 256 random bits, base64url, shown once; only its SHA-256 is
 * stored. A session family lives at most 30 days from sign-in and dies after 14 days
 * unused.</li>
 * <li><b>Rotation:</b> each refresh revokes the presented row (ROTATED) and issues the next
 * one in the same family, under a row lock. Presenting a token that was already rotated
 * means it was copied: the whole family is revoked (REUSE_DETECTED), deny-listed and audited,
 * and the caller gets 401 {@code auth.session_revoked}. Of two concurrent refreshes of one
 * token, exactly one succeeds; the other counts as reuse.</li>
 * </ul>
 */
@Service
public class TokenService {

	private static final Logger log = LoggerFactory.getLogger(TokenService.class);

	private static final int REFRESH_TOKEN_BYTES = 32;

	private static final int MAX_REFRESH_TOKEN_LENGTH = 128;

	private final SecureRandom random = new SecureRandom();

	private final JwtEncoder encoder;

	private final AccessTokenKeys keys;

	private final AuthSessionStore sessions;

	private final SessionDenyList denyList;

	private final UserSnapshotCache users;

	private final AuditLog audit;

	private final IdentityProperties props;

	private final Clock clock;

	private final TransactionTemplate tx;

	TokenService(JwtEncoder encoder, AccessTokenKeys keys, AuthSessionStore sessions, SessionDenyList denyList,
			UserSnapshotCache users, AuditLog audit, IdentityProperties props, Clock clock, TransactionTemplate tx) {
		this.encoder = encoder;
		this.keys = keys;
		this.sessions = sessions;
		this.denyList = denyList;
		this.users = users;
		this.audit = audit;
		this.props = props;
		this.clock = clock;
		this.tx = tx;
	}

	/** Starts a new session family for a user who just signed in (joins the caller's transaction). */
	@Transactional
	public TokenPair startSession(UUID userId, AuthMethod method) {
		Instant now = this.clock.instant();
		UUID familyId = Ids.newId();
		String refreshToken = newRefreshToken();
		Instant expiresAt = now.plus(this.props.sessions().absoluteTtl());
		this.sessions.insert(Ids.newId(), userId, Hashes.sha256Hex(refreshToken), familyId, method.name(),
				ClientRequests.userAgent(), ClientRequests.ip(), now, expiresAt);
		return pair(userId, familyId, refreshToken, now, expiresAt);
	}

	/** Rotates a refresh token. See the class comment for reuse detection. */
	public TokenPair refresh(String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank() || refreshToken.length() > MAX_REFRESH_TOKEN_LENGTH) {
			throw ApiException.unauthorized("auth.invalid_refresh_token");
		}
		String hash = Hashes.sha256Hex(refreshToken);
		RefreshResult result = this.tx.execute((status) -> rotate(hash));
		return switch (result.outcome()) {
			case ROTATED -> result.tokens();
			case INVALID -> throw ApiException.unauthorized("auth.invalid_refresh_token");
			case REVOKED -> throw ApiException.unauthorized("auth.session_revoked");
		};
	}

	/** Revokes the family of {@code refreshToken} (unknown tokens are ignored: logout always succeeds). */
	public void logout(String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank() || refreshToken.length() > MAX_REFRESH_TOKEN_LENGTH) {
			return;
		}
		this.tx.executeWithoutResult((status) -> {
			Session session = this.sessions.findByTokenHash(Hashes.sha256Hex(refreshToken)).orElse(null);
			if (session == null) {
				return;
			}
			Instant now = this.clock.instant();
			int revoked = this.sessions.revokeFamily(session.familyId(), "LOGOUT", now);
			denyAfterCommit(List.of(session.familyId()));
			if (revoked > 0) {
				this.audit.record(AuditEntry.of("auth.logout", AuditOutcome.SUCCESS)
					.actor(session.userId())
					.target("user", session.userId())
					.metadata("sessionId", session.familyId().toString()));
			}
		});
	}

	/**
	 * Revokes every session family of the user, except {@code keepFamilyId} when given, and
	 * deny-lists their access tokens. Joins the caller's transaction.
	 */
	@Transactional
	public List<UUID> revokeAll(UUID userId, UUID keepFamilyId, String reason) {
		List<UUID> families = this.sessions.revokeAllOfUser(userId, keepFamilyId, reason, this.clock.instant());
		denyAfterCommit(families);
		return families;
	}

	@Transactional
	public void logoutAll(UUID userId, UUID currentFamilyId) {
		List<UUID> families = this.sessions.revokeAllOfUser(userId, null, "LOGOUT_ALL", this.clock.instant());
		denyAfterCommit(withCurrent(families, currentFamilyId));
		this.audit.record(AuditEntry.of("auth.logout_all", AuditOutcome.SUCCESS)
			.target("user", userId)
			.metadata("sessions", families.size()));
	}

	private RefreshResult rotate(String hash) {
		Session session = this.sessions.lockByTokenHash(hash).orElse(null);
		if (session == null) {
			return RefreshResult.of(Outcome.INVALID);
		}
		Instant now = this.clock.instant();
		if (session.isRevoked()) {
			int revoked = this.sessions.revokeFamily(session.familyId(), "REUSE_DETECTED", now);
			denyAfterCommit(List.of(session.familyId()));
			if ("ROTATED".equals(session.revokedReason()) || revoked > 0) {
				log.warn("Refresh token reuse detected: session family {} of user {} revoked", session.familyId(),
						session.userId());
				this.audit.record(AuditEntry.of("auth.refresh.reuse_detected", AuditOutcome.DENIED)
					.actor(session.userId())
					.target("user", session.userId())
					.metadata("sessionId", session.familyId().toString())
					.metadata("revokedSessions", revoked));
			}
			return RefreshResult.of(Outcome.REVOKED);
		}
		Instant lastUse = (session.lastSeenAt() != null) ? session.lastSeenAt() : session.createdAt();
		boolean expired = !session.expiresAt().isAfter(now)
				|| !lastUse.plus(this.props.sessions().idleTtl()).isAfter(now);
		if (expired) {
			this.sessions.revoke(session.id(), "EXPIRED", now);
			return RefreshResult.of(Outcome.INVALID);
		}
		UserSnapshotCache.UserSnapshot user = this.users.get(session.userId());
		if (user == null || user.status() != UserStatus.ACTIVE) {
			this.sessions.revokeFamily(session.familyId(), "USER_INACTIVE", now);
			denyAfterCommit(List.of(session.familyId()));
			return RefreshResult.of(Outcome.INVALID);
		}
		this.sessions.revoke(session.id(), "ROTATED", now);
		String next = newRefreshToken();
		this.sessions.insert(Ids.newId(), session.userId(), Hashes.sha256Hex(next), session.familyId(),
				session.authMethod(), ClientRequests.userAgent(), ClientRequests.ip(), now, session.expiresAt());
		return new RefreshResult(Outcome.ROTATED,
				pair(session.userId(), session.familyId(), next, now, session.expiresAt()));
	}

	private TokenPair pair(UUID userId, UUID familyId, String refreshToken, Instant now, Instant familyExpiresAt) {
		Duration accessTtl = this.props.jwt().accessTokenTtl();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(this.props.jwt().issuer())
			.audience(List.of(this.props.jwt().audience()))
			.subject(userId.toString())
			.issuedAt(now)
			.expiresAt(now.plus(accessTtl))
			.id(Ids.newId().toString())
			.claim(AccessTokenKeys.SESSION_CLAIM, familyId.toString())
			.build();
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(this.keys.signingKeyId()).type("at+jwt").build();
		String accessToken = this.encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		long refreshSeconds = Math.min(Duration.between(now, familyExpiresAt).toSeconds(),
				this.props.sessions().idleTtl().toSeconds());
		return new TokenPair(accessToken, accessTtl.toSeconds(), refreshToken, Math.max(0, refreshSeconds));
	}

	private String newRefreshToken() {
		byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
		this.random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private void denyAfterCommit(List<UUID> families) {
		if (!families.isEmpty()) {
			List<UUID> copy = List.copyOf(families);
			AfterCommit.run(() -> this.denyList.deny(copy));
		}
	}

	private static List<UUID> withCurrent(List<UUID> families, UUID current) {
		if (current == null || families.contains(current)) {
			return families;
		}
		java.util.ArrayList<UUID> all = new java.util.ArrayList<>(families);
		all.add(current);
		return all;
	}

	/** {@code expiresIn} values are seconds. */
	public record TokenPair(String accessToken, long accessTokenExpiresIn, String refreshToken,
			long refreshTokenExpiresIn) {

		@Override
		public String toString() {
			return "TokenPair[redacted]";
		}

	}

	enum Outcome {

		ROTATED, INVALID, REVOKED

	}

	record RefreshResult(Outcome outcome, TokenPair tokens) {

		static RefreshResult of(Outcome outcome) {
			return new RefreshResult(outcome, null);
		}

	}

}
