package com.nemaz.school.moderation.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The retention purge of moderation cases (docs/Safeguarding_Rules.md S6): a RESOLVED or ESCALATED case is removed, with its
 * reports, history, warnings and lifted suspensions, once {@code safeguarding.retention.case_years} (a placeholder pending
 * counsel) passed since it was resolved, and ONLY when it is not under legal hold and no suspension is still in force.
 * Idempotent, safe on several instances (ShedLock), each case in its own transaction, logs counts only. The reported content
 * itself belongs to its owning module and is kept for the same period by the evidence hold; this job never touches it.
 * Returns {@code Integer}: ShedLock refuses primitive return types.
 */
@Component
public class ModerationRetention {

	private static final Logger log = LoggerFactory.getLogger(ModerationRetention.class);

	private static final int BATCH = 500;

	private final JdbcClient jdbc;

	private final ModerationSettings settings;

	private final TransactionTemplate tx;

	private final Clock clock;

	ModerationRetention(JdbcClient jdbc, ModerationSettings settings, TransactionTemplate tx, Clock clock) {
		this.jdbc = jdbc;
		this.settings = settings;
		this.tx = tx;
		this.clock = clock;
	}

	@Scheduled(cron = "0 15 3 * * *", zone = "UTC")
	@SchedulerLock(name = "moderation.purge-cases", lockAtMostFor = "PT30M")
	public Integer purgeJob() {
		return purgeExpired();
	}

	/** One pass; returns how many cases were removed. */
	public Integer purgeExpired() {
		Instant now = this.clock.instant();
		record Candidate(UUID id, UUID schoolId, Instant resolvedAt) {
		}
		List<Candidate> candidates = this.jdbc.sql("""
				select id, school_id, resolved_at from moderation_cases
				where status in ('RESOLVED', 'ESCALATED') and not legal_hold and resolved_at < :oldest
				order by resolved_at, id limit :n""")
			.param("oldest", Timestamp.from(now.atZone(ZoneOffset.UTC).minusYears(1).toInstant()))
			.param("n", BATCH)
			.query((rs, i) -> new Candidate(rs.getObject("id", UUID.class), rs.getObject("school_id", UUID.class),
					rs.getTimestamp("resolved_at").toInstant()))
			.list();
		int purged = 0;
		for (Candidate c : candidates) {
			int years = this.settings.caseRetentionYears(c.schoolId());
			if (c.resolvedAt().isAfter(now.atZone(ZoneOffset.UTC).minusYears(years).toInstant())) {
				continue;
			}
			if (Boolean.TRUE.equals(this.tx.execute((status) -> purge(c.id())))) {
				purged++;
			}
		}
		if (purged > 0) {
			log.info("Retention purge: {} expired moderation case(s) removed", purged);
		}
		return purged;
	}

	private boolean purge(UUID caseId) {
		Boolean eligible = this.jdbc.sql("""
				select not legal_hold and status in ('RESOLVED', 'ESCALATED')
				       and not exists (select 1 from school_suspensions s where s.case_id = moderation_cases.id and s.lifted_at is null)
				from moderation_cases where id = :id for update""")
			.param("id", caseId)
			.query(Boolean.class)
			.optional()
			.orElse(false);
		if (!Boolean.TRUE.equals(eligible)) {
			return false;
		}
		this.jdbc.sql("select set_config('school.retention_purge', 'on', true)").query().listOfRows();
		this.jdbc.sql("delete from moderation_actions where case_id = :id").param("id", caseId).update();
		this.jdbc.sql("delete from content_reports where case_id = :id").param("id", caseId).update();
		this.jdbc.sql("delete from account_warnings where case_id = :id").param("id", caseId).update();
		this.jdbc.sql("delete from school_suspensions where case_id = :id").param("id", caseId).update();
		this.jdbc.sql("delete from moderation_cases where id = :id").param("id", caseId).update();
		return true;
	}

}
