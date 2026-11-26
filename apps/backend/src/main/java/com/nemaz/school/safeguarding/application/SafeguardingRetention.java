package com.nemaz.school.safeguarding.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.media.MediaService;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The retention purge of safeguarding incidents (docs/Safeguarding_Rules.md S6): a CLOSED incident is removed, with its notes
 * and attachments, once {@code safeguarding.retention.incident_years} (a placeholder pending counsel) passed since it was
 * closed, and ONLY when it is not under legal hold. Idempotent (a second run finds nothing), safe on several instances
 * (ShedLock), each incident in its own transaction. Logs counts only. The method returns {@code Integer}: ShedLock refuses
 * primitive return types.
 */
@Component
public class SafeguardingRetention {

	private static final Logger log = LoggerFactory.getLogger(SafeguardingRetention.class);

	private static final int BATCH = 500;

	private final JdbcClient jdbc;

	private final SettingsService settings;

	private final MediaService media;

	private final TransactionTemplate tx;

	private final Clock clock;

	SafeguardingRetention(JdbcClient jdbc, SettingsService settings, MediaService media, TransactionTemplate tx, Clock clock) {
		this.jdbc = jdbc;
		this.settings = settings;
		this.media = media;
		this.tx = tx;
		this.clock = clock;
	}

	@Scheduled(cron = "0 30 3 * * *", zone = "UTC")
	@SchedulerLock(name = "safeguarding.purge-incidents", lockAtMostFor = "PT30M")
	public Integer purgeJob() {
		return purgeExpired();
	}

	/** One pass; returns how many incidents were removed. */
	public Integer purgeExpired() {
		Instant now = this.clock.instant();
		record Candidate(UUID id, UUID schoolId, Instant closedAt) {
		}
		List<Candidate> candidates = this.jdbc.sql("""
				select id, school_id, closed_at from safeguarding_incidents
				where status = 'CLOSED' and not legal_hold and closed_at < :oldest order by closed_at, id limit :n""")
			.param("oldest", Timestamp.from(now.atZone(ZoneOffset.UTC).minusYears(1).toInstant()))
			.param("n", BATCH)
			.query((rs, i) -> new Candidate(rs.getObject("id", UUID.class), rs.getObject("school_id", UUID.class),
					rs.getTimestamp("closed_at").toInstant()))
			.list();
		int purged = 0;
		for (Candidate c : candidates) {
			SettingScope scope = (c.schoolId() != null) ? SettingScope.school(c.schoolId()) : SettingScope.platform();
			int years = this.settings.getInt("safeguarding.retention.incident_years", scope).orElse(7);
			if (c.closedAt().isAfter(now.atZone(ZoneOffset.UTC).minusYears(years).toInstant())) {
				continue;
			}
			Boolean removed = this.tx.execute((status) -> purge(c.id()));
			if (Boolean.TRUE.equals(removed)) {
				purged++;
			}
		}
		if (purged > 0) {
			log.info("Retention purge: {} expired safeguarding incident(s) removed", purged);
		}
		return purged;
	}

	private boolean purge(UUID incidentId) {
		// a hold set since the candidate query wins: re-check under the row lock
		Boolean unheld = this.jdbc.sql("select not legal_hold from safeguarding_incidents where id = :id and status = 'CLOSED' for update")
			.param("id", incidentId)
			.query(Boolean.class)
			.optional()
			.orElse(false);
		if (!Boolean.TRUE.equals(unheld)) {
			return false;
		}
		List<UUID> attachments = this.jdbc.sql("select attachment_media_id from safeguarding_incident_notes where incident_id = :id and attachment_media_id is not null")
			.param("id", incidentId)
			.query(UUID.class)
			.list();
		this.jdbc.sql("select set_config('school.retention_purge', 'on', true)").query().listOfRows();
		this.jdbc.sql("delete from safeguarding_incident_notes where incident_id = :id").param("id", incidentId).update();
		this.jdbc.sql("delete from safeguarding_incidents where id = :id").param("id", incidentId).update();
		attachments.forEach(this.media::discard);
		return true;
	}

}
