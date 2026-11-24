package com.nemaz.school.identity.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code auth_sessions}: one row per refresh token, grouped by token family. Only SHA-256
 * hashes of refresh tokens are stored. Instants come from the application clock.
 */
@Component
public class AuthSessionStore {

	private static final String COLUMNS = """
			id, user_id, token_family_id, auth_method, created_at, last_seen_at, expires_at, revoked_at, revoked_reason""";

	private final JdbcTemplate jdbc;

	AuthSessionStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(UUID id, UUID userId, String tokenHash, UUID familyId, String authMethod, String userAgent,
			String ip, Instant now, Instant expiresAt) {
		this.jdbc.update("""
				insert into auth_sessions (id, user_id, refresh_token_hash, token_family_id, auth_method, user_agent, ip,
				                           created_at, last_seen_at, expires_at)
				values (?, ?, ?, ?, ?, ?, ?::inet, ?, ?, ?)""", id, userId, tokenHash, familyId, authMethod, userAgent, ip,
				Timestamp.from(now), Timestamp.from(now), Timestamp.from(expiresAt));
	}

	/** The row of a refresh token, locked: concurrent refreshes of one token run one after the other. */
	public Optional<Session> lockByTokenHash(String tokenHash) {
		return this.jdbc
			.query("select " + COLUMNS + " from auth_sessions where refresh_token_hash = ? for update",
					(rs, i) -> map(rs), tokenHash)
			.stream()
			.findFirst();
	}

	public Optional<Session> findByTokenHash(String tokenHash) {
		return this.jdbc
			.query("select " + COLUMNS + " from auth_sessions where refresh_token_hash = ?", (rs, i) -> map(rs),
					tokenHash)
			.stream()
			.findFirst();
	}

	public void revoke(UUID id, String reason, Instant now) {
		this.jdbc.update("update auth_sessions set revoked_at = ?, revoked_reason = ? where id = ? and revoked_at is null",
				Timestamp.from(now), reason, id);
	}

	/** Revokes the family's remaining active rows; returns how many. */
	public int revokeFamily(UUID familyId, String reason, Instant now) {
		return this.jdbc.update("""
				update auth_sessions set revoked_at = ?, revoked_reason = ?
				where token_family_id = ? and revoked_at is null""", Timestamp.from(now), reason, familyId);
	}

	/** Revokes every active row of the user (optionally sparing one family); returns the families revoked. */
	public List<UUID> revokeAllOfUser(UUID userId, UUID exceptFamilyId, String reason, Instant now) {
		return this.jdbc.queryForList("""
				update auth_sessions set revoked_at = ?, revoked_reason = ?
				where user_id = ? and revoked_at is null and (cast(? as uuid) is null or token_family_id <> ?)
				returning token_family_id""", UUID.class, Timestamp.from(now), reason, userId, exceptFamilyId,
				exceptFamilyId)
			.stream()
			.distinct()
			.toList();
	}

	/** When the family was first signed into (the first row's creation). */
	public Optional<Instant> familyStartedAt(UUID familyId) {
		Timestamp started = this.jdbc.queryForObject(
				"select min(created_at) from auth_sessions where token_family_id = ?", Timestamp.class, familyId);
		return Optional.ofNullable(started).map(Timestamp::toInstant);
	}

	public int purgeExpiredBefore(Instant cutoff) {
		return this.jdbc.update("delete from auth_sessions where expires_at < ?", Timestamp.from(cutoff));
	}

	private static Session map(ResultSet rs) throws SQLException {
		Timestamp lastSeen = rs.getTimestamp("last_seen_at");
		Timestamp revoked = rs.getTimestamp("revoked_at");
		return new Session(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
				rs.getObject("token_family_id", UUID.class), rs.getString("auth_method"),
				rs.getTimestamp("created_at").toInstant(), (lastSeen != null) ? lastSeen.toInstant() : null,
				rs.getTimestamp("expires_at").toInstant(), (revoked != null) ? revoked.toInstant() : null,
				rs.getString("revoked_reason"));
	}

	public record Session(UUID id, UUID userId, UUID familyId, String authMethod, Instant createdAt,
			Instant lastSeenAt, Instant expiresAt, Instant revokedAt, String revokedReason) {

		public boolean isRevoked() {
			return this.revokedAt != null;
		}

	}

}
