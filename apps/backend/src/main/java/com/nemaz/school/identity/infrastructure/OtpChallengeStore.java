package com.nemaz.school.identity.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** {@code otp_challenges} with explicit SQL (row locks for verification). Instants come from the application clock. */
@Component
public class OtpChallengeStore {

	private final JdbcTemplate jdbc;

	OtpChallengeStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Ends every live challenge for the destination and purpose (a new code replaces them). */
	public int invalidateLive(String destination, String purpose, Instant now) {
		return this.jdbc.update("""
				update otp_challenges set expires_at = ?
				where destination = ? and purpose = ? and consumed_at is null and expires_at > ?""",
				Timestamp.from(now), destination, purpose, Timestamp.from(now));
	}

	public void insert(UUID id, UUID userId, String channel, String destination, String purpose, String codeHash,
			Instant expiresAt, String ip, Instant now) {
		this.jdbc.update("""
				insert into otp_challenges (id, user_id, channel, destination, purpose, code_hash, expires_at, request_ip,
				                            created_at)
				values (?, ?, ?, ?, ?, ?, ?, ?::inet, ?)""", id, userId, channel, destination, purpose, codeHash,
				Timestamp.from(expiresAt), ip, Timestamp.from(now));
	}

	/** The newest unconsumed challenge of the user for the destination, locked for update. */
	public Optional<Challenge> lockLatest(UUID userId, String destination, String purpose) {
		List<Challenge> rows = this.jdbc.query("""
				select id, code_hash, attempts, expires_at from otp_challenges
				where user_id = ? and destination = ? and purpose = ? and consumed_at is null
				order by created_at desc limit 1
				for update""", (rs, i) -> new Challenge(rs.getObject("id", UUID.class), rs.getString("code_hash"),
				rs.getInt("attempts"), rs.getTimestamp("expires_at").toInstant()), userId, destination, purpose);
		return rows.stream().findFirst();
	}

	public void recordAttempt(UUID id) {
		this.jdbc.update("update otp_challenges set attempts = attempts + 1 where id = ?", id);
	}

	public void consume(UUID id, Instant now) {
		this.jdbc.update("update otp_challenges set consumed_at = ? where id = ?", Timestamp.from(now), id);
	}

	public int purgeCreatedBefore(Instant cutoff) {
		return this.jdbc.update("delete from otp_challenges where created_at < ?", Timestamp.from(cutoff));
	}

	public record Challenge(UUID id, String codeHash, int attempts, Instant expiresAt) {
	}

}
