package com.nemaz.school.imports.application;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.imports.application.ImportModel.Job;
import com.nemaz.school.imports.application.ImportModel.Row;
import com.nemaz.school.imports.application.ImportModel.RowStatus;
import com.nemaz.school.imports.application.ImportModel.Status;
import com.nemaz.school.imports.application.RowHandler.Applied;
import com.nemaz.school.imports.application.RowHandler.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * The commit: applies the validated rows through the people and academic services, one transaction per batch of
 * rows. Idempotent in every way a replay can happen:
 * <ul>
 * <li>a row is applied together with its {@code apply_result} in one transaction, so a row is never applied twice
 * and a batch that fails leaves nothing behind;</li>
 * <li>only rows without an {@code apply_result} are processed, so a redelivered event, a second worker after a
 * crash or a resumed commit after FAILED simply continues;</li>
 * <li>the handlers match what exists before they create (external id mappings, student numbers, class names), so
 * a file committed again changes nothing;</li>
 * <li>the job's lease keeps two workers from running the same commit.</li>
 * </ul>
 * A failure ends the job in FAILED with the first failing row; batches already committed stay.
 */
@Component
class ImportCommit {

	private static final Logger log = LoggerFactory.getLogger(ImportCommit.class);

	private final ImportStore store;

	private final ImportFiles files;

	private final ImportRunSupport support;

	private final ImportProperties properties;

	private final ExternalMappings mappings;

	private final AuditLog audit;

	ImportCommit(ImportStore store, ImportFiles files, ImportRunSupport support, ImportProperties properties,
			ExternalMappings mappings, AuditLog audit) {
		this.store = store;
		this.files = files;
		this.support = support;
		this.properties = properties;
		this.mappings = mappings;
		this.audit = audit;
	}

	void run(UUID jobId) {
		Job job = this.store.find(jobId).orElse(null);
		if (job == null || job.status() != Status.COMMITTING) {
			return;
		}
		String owner = UUID.randomUUID().toString();
		if (!claim(jobId, Status.COMMITTING, owner)) {
			return;
		}
		int[] current = { 0 };
		try {
			process(job, owner, current);
		}
		catch (ApiException ex) {
			fail(jobId, "COMMIT", (current[0] > 0) ? current[0] : null, ex.code());
			notifyFailure(job);
		}
		catch (RuntimeException ex) {
			log.error("Commit of import {} failed at row {}", jobId, current[0], ex);
			fail(jobId, "COMMIT", (current[0] > 0) ? current[0] : null, "import.internal_error");
			notifyFailure(job);
		}
	}

	private void process(Job job, String owner, int[] current) {
		if (!ImportHashes.validated(job).equals(job.validatedHash())) {
			throw ApiException.conflict("import.changed_since_dry_run");
		}
		if (!this.files.fingerprint(job.mediaId()).equals(job.fingerprint())) {
			throw ApiException.conflict("import.file_changed");
		}
		Session session = this.support.handler(job.type()).open(this.support.context(job));
		boolean skipInvalid = job.options().skipInvalid();
		int after = 0;
		while (true) {
			List<Row> batch = this.store.unapplied(job.id(), after, this.properties.batchSize());
			if (batch.isEmpty()) {
				break;
			}
			Job live = this.store.find(job.id()).orElse(null);
			if (live == null || live.status() != Status.COMMITTING) {
				return;
			}
			this.support.inNewTransaction(() -> applyBatch(job, session, batch, skipInvalid, current));
			after = batch.getLast().rowNumber();
			renew(job.id(), owner);
		}
		Job done = this.store.find(job.id()).orElse(job);
		this.support.inNewTransaction(() -> {
			this.store.complete(job.id());
			this.audit.record(AuditEntry.of("import.commit", AuditOutcome.SUCCESS)
				.actor(job.initiatedBy())
				.target("import_job", job.id())
				.school(job.schoolId())
				.metadata("importType", job.type().name())
				.metadata("created", done.createdCount())
				.metadata("updated", done.updatedCount())
				.metadata("unchanged", done.unchangedCount())
				.metadata("skipped", done.skippedCount()));
			Job completed = this.store.find(job.id()).orElse(done);
			int changed = value(completed.createdCount()) + value(completed.updatedCount());
			this.support.notifyStage(completed, "IMPORT_COMMITTED", "notification.import_completed", completed.filename(),
					changed, value(completed.skippedCount()));
		});
	}

	private void applyBatch(Job job, Session session, List<Row> batch, boolean skipInvalid, int[] current) {
		int created = 0;
		int updated = 0;
		int unchanged = 0;
		int skipped = 0;
		for (Row row : batch) {
			current[0] = row.rowNumber();
			if (row.status() == RowStatus.DUPLICATE) {
				throw ApiException.conflict("import.has_open_duplicates");
			}
			if (row.status() == RowStatus.INVALID && !skipInvalid) {
				throw ApiException.conflict("import.has_invalid_rows");
			}
			if (row.status() != RowStatus.VALID || row.normalized() == null) {
				this.store.markApplied(row.id(), "SKIPPED", null, null);
				skipped++;
				continue;
			}
			Applied applied = session.apply(row);
			this.store.markApplied(row.id(), applied.result(), applied.entityType(), applied.entityId());
			if (applied.externalId() != null && applied.entityId() != null) {
				this.mappings.put(job.schoolId(), job.externalSystemId(), mappingType(applied.entityType()),
						applied.externalId(), applied.entityId());
			}
			switch (applied.result()) {
				case "CREATED" -> created++;
				case "UPDATED" -> updated++;
				case "UNCHANGED" -> unchanged++;
				default -> skipped++;
			}
		}
		this.store.addBatch(job.id(), created, updated, unchanged, skipped, batch.size());
	}

	/** The entity type of {@code external_entity_mappings} for what a handler reports. */
	private static String mappingType(String entityType) {
		return switch (entityType) {
			case "STAFF" -> "STAFF";
			case "CLASS" -> "CLASS";
			case "SUBJECT" -> "SUBJECT";
			default -> "STUDENT";
		};
	}

	private void notifyFailure(Job job) {
		try {
			this.support.inNewTransaction(() -> {
				Job failed = this.store.find(job.id()).orElse(job);
				this.support.notifyStage(failed, "IMPORT_FAILED", "notification.import_failed", failed.filename());
			});
		}
		catch (RuntimeException ex) {
			log.warn("Could not notify about the failed import {}", job.id());
		}
	}

	private static int value(Integer n) {
		return (n == null) ? 0 : n;
	}

	/**
	 * The listener runs inside a transaction of its own; the job row is written in short transactions of their own
	 * (a lock held by the listener's transaction would block the batches that update the same row).
	 */
	private boolean claim(UUID jobId, Status status, String owner) {
		return this.support.inNewTransaction(() -> this.store.claim(jobId, status, owner, this.properties.leaseDuration()));
	}

	private void renew(UUID jobId, String owner) {
		this.support.inNewTransaction(() -> this.store.renew(jobId, owner, this.properties.leaseDuration()));
	}

	private void fail(UUID jobId, String phase, Integer row, String code) {
		this.support.inNewTransaction(() -> this.store.fail(jobId, phase, row, code));
	}

}
