package com.nemaz.school.system.idempotency;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.system.SchoolProperties;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code idempotency_keys} rows, written outside the request's business transaction
 * (auto-commit): the reservation must be visible to a concurrent duplicate before the first
 * request finishes. Keyed by (user, key).
 */
@Component
class IdempotencyStore {

	private final JdbcTemplate jdbc;

	private final Clock clock;

	private final SchoolProperties.Idempotency config;

	IdempotencyStore(JdbcTemplate jdbc, Clock clock, SchoolProperties props) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.config = props.idempotency();
	}

	Outcome reserve(UUID userId, String key, String requestHash) {
		Instant now = this.clock.instant();
		Timestamp created = Timestamp.from(now);
		Timestamp expires = Timestamp.from(now.plus(this.config.retention()));
		int inserted = this.jdbc.update("""
				insert into idempotency_keys (key, user_id, request_hash, created_at, expires_at)
				values (?, ?, ?, ?, ?) on conflict (user_id, key) do nothing""", key, userId, requestHash, created, expires);
		if (inserted == 1) {
			return Outcome.reserved();
		}
		List<Existing> rows = this.jdbc.query("""
				select request_hash, response_status, response_body::text as body, created_at, expires_at
				from idempotency_keys where user_id = ? and key = ?""",
				(rs, i) -> new Existing(rs.getString("request_hash"), (Integer) rs.getObject("response_status"),
						rs.getString("body"), rs.getTimestamp("created_at").toInstant(),
						rs.getTimestamp("expires_at").toInstant()),
				userId, key);
		if (rows.isEmpty()) { // deleted in between (released): try once more
			return (this.jdbc.update("""
					insert into idempotency_keys (key, user_id, request_hash, created_at, expires_at)
					values (?, ?, ?, ?, ?) on conflict (user_id, key) do nothing""", key, userId, requestHash, created,
					expires) == 1) ? Outcome.reserved() : Outcome.inProgress();
		}
		Existing row = rows.getFirst();
		boolean expired = !row.expiresAt().isAfter(now);
		boolean abandoned = row.status() == null && row.createdAt().plus(this.config.staleAfter()).isBefore(now);
		if (expired || abandoned) {
			int taken = this.jdbc.update("""
					update idempotency_keys
					set request_hash = ?, response_status = null, response_body = null, created_at = ?, expires_at = ?
					where user_id = ? and key = ? and created_at = ?""", requestHash, created, expires, userId, key,
					Timestamp.from(row.createdAt()));
			return (taken == 1) ? Outcome.reserved() : Outcome.inProgress();
		}
		if (!row.requestHash().equals(requestHash)) {
			return Outcome.mismatch();
		}
		if (row.status() == null) {
			return Outcome.inProgress();
		}
		return Outcome.replay(row.status(), row.body());
	}

	void complete(UUID userId, String key, int status, String bodyJson) {
		this.jdbc.update("""
				update idempotency_keys set response_status = ?, response_body = ?::jsonb
				where user_id = ? and key = ? and response_status is null""", status, bodyJson, userId, key);
	}

	/** Forgets an unfinished reservation (the request failed), so the client may retry with the same key. */
	void release(UUID userId, String key) {
		this.jdbc.update("delete from idempotency_keys where user_id = ? and key = ? and response_status is null", userId,
				key);
	}

	int purgeExpired() {
		return this.jdbc.update("delete from idempotency_keys where expires_at < ?", Timestamp.from(this.clock.instant()));
	}

	private record Existing(String requestHash, Integer status, String body, Instant createdAt, Instant expiresAt) {
	}

	enum Kind {

		RESERVED, REPLAY, MISMATCH, IN_PROGRESS

	}

	record Outcome(Kind kind, int status, String body) {

		static Outcome reserved() {
			return new Outcome(Kind.RESERVED, 0, null);
		}

		static Outcome replay(int status, String body) {
			return new Outcome(Kind.REPLAY, status, body);
		}

		static Outcome mismatch() {
			return new Outcome(Kind.MISMATCH, 0, null);
		}

		static Outcome inProgress() {
			return new Outcome(Kind.IN_PROGRESS, 0, null);
		}

	}

}
