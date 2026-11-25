package com.nemaz.school.imports.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.imports.ImportCommitRequested;
import com.nemaz.school.imports.ImportDryRunRequested;
import com.nemaz.school.imports.application.ImportModel.Job;
import com.nemaz.school.imports.application.ImportModel.Status;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Housekeeping of imports.
 * <ul>
 * <li><b>Retention:</b> the rows of a job hold personal data of children. Their raw and normalized payloads and the
 * uploaded file are deleted 30 days after the job was created, or 7 days after it was committed, whichever comes
 * first ({@code school.imports.retention-*}). Counts, status and audit stay ({@code rows_purged_at}).</li>
 * <li><b>Stuck jobs:</b> a job that is VALIDATING or COMMITTING with an expired lease lost its worker (the
 * instance died); its event is published again. Workers take the lease first, so this is harmless when the worker
 * is merely slow.</li>
 * </ul>
 */
@Component
public class ImportMaintenance {

	private static final Logger log = LoggerFactory.getLogger(ImportMaintenance.class);

	private final ImportStore store;

	private final ImportService service;

	private final ImportProperties properties;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	ImportMaintenance(ImportStore store, ImportService service, ImportProperties properties, AuditLog audit,
			ApplicationEventPublisher events, Clock clock) {
		this.store = store;
		this.service = service;
		this.properties = properties;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	@Scheduled(cron = "0 20 3 * * *", zone = "UTC")
	@SchedulerLock(name = "imports.purge-rows", lockAtMostFor = "PT30M")
	public int purgeExpired() {
		int total = 0;
		List<UUID> due;
		do {
			due = this.store.duePurge(this.clock.instant().minus(this.properties.retentionAfterCreate()),
					this.clock.instant().minus(this.properties.retentionAfterCommit()));
			for (UUID id : due) {
				purge(id);
			}
			total += due.size();
		}
		while (due.size() == 200);
		if (total > 0) {
			log.info("Purged the rows and files of {} expired imports", total);
		}
		return total;
	}

	@Transactional
	void purge(UUID jobId) {
		Job job = this.store.find(jobId).orElse(null);
		if (job == null || job.rowsPurgedAt() != null) {
			return;
		}
		this.store.purgePayloads(jobId);
		this.service.releaseFile(job);
		this.store.markPurged(jobId, true);
		this.audit.record(AuditEntry.of("import.purge", AuditOutcome.SUCCESS)
			.actor(null)
			.target("import_job", jobId)
			.school(job.schoolId())
			.metadata("status", job.status().name()));
	}

	@Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
	@SchedulerLock(name = "imports.resubmit-stuck", lockAtMostFor = "PT5M")
	@Transactional
	public int resubmitStuck() {
		List<UUID> stuck = this.store.stuck(this.properties.stuckAfter());
		for (UUID id : stuck) {
			Job job = this.store.find(id).orElse(null);
			if (job == null) {
				continue;
			}
			if (job.status() == Status.VALIDATING) {
				this.events.publishEvent(new ImportDryRunRequested(id));
			}
			else if (job.status() == Status.COMMITTING) {
				this.events.publishEvent(new ImportCommitRequested(id));
			}
			log.warn("Queued import {} again: its worker is gone", id);
		}
		return stuck.size();
	}

}
