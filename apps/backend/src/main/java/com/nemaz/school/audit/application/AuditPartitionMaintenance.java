package com.nemaz.school.audit.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps monthly {@code audit_logs} partitions ahead of time: the current month (UTC) and the
 * next {@value #MONTHS_AHEAD}. Runs daily (ShedLock) and once at startup; creation is
 * idempotent ({@code create table if not exists}) and serialized by an advisory lock, so
 * several instances may run it at once.
 */
@Component
public class AuditPartitionMaintenance {

	static final int MONTHS_AHEAD = 3;

	private static final Logger log = LoggerFactory.getLogger(AuditPartitionMaintenance.class);

	private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("'audit_logs_y'yyyy'm'MM");

	private final JdbcTemplate jdbc;

	private final Clock clock;

	private final TransactionTemplate tx;

	AuditPartitionMaintenance(JdbcTemplate jdbc, Clock clock, TransactionTemplate tx) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.tx = tx;
	}

	@Scheduled(cron = "0 20 2 * * *", zone = "UTC")
	@SchedulerLock(name = "audit.partitions")
	public void scheduled() {
		ensurePartitions();
	}

	@EventListener(ApplicationReadyEvent.class)
	public void atStartup() {
		try {
			ensurePartitions();
		}
		catch (RuntimeException ex) {
			log.error("Could not create audit_logs partitions at startup; the daily job retries", ex);
		}
	}

	/** Creates missing partitions from the current month to {@value #MONTHS_AHEAD} months ahead; returns their names. */
	public List<String> ensurePartitions() {
		return ensurePartitions(YearMonth.now(this.clock.withZone(ZoneOffset.UTC)));
	}

	public List<String> ensurePartitions(YearMonth from) {
		return this.tx.execute((status) -> create(from));
	}

	private List<String> create(YearMonth from) {
		this.jdbc.queryForList("select pg_advisory_xact_lock(hashtext('audit.partitions'))");
		List<String> names = new ArrayList<>();
		for (int i = 0; i <= MONTHS_AHEAD; i++) {
			YearMonth month = from.plusMonths(i);
			LocalDate start = month.atDay(1);
			String name = NAME.format(start);
			// Identifiers and bounds come from dates formatted here, never from input.
			this.jdbc.execute("create table if not exists " + name + " partition of audit_logs for values from ('"
					+ start + " 00:00:00+00') to ('" + start.plusMonths(1) + " 00:00:00+00')");
			names.add(name);
		}
		return names;
	}

}
