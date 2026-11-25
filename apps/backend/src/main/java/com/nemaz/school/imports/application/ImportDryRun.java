package com.nemaz.school.imports.application;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.imports.application.ImportModel.Counts;
import com.nemaz.school.imports.application.ImportModel.Job;
import com.nemaz.school.imports.application.ImportModel.Status;
import com.nemaz.school.imports.application.RowHandler.JobContext;
import com.nemaz.school.imports.application.RowHandler.Planned;
import com.nemaz.school.imports.application.RowHandler.RowInput;
import com.nemaz.school.imports.application.RowHandler.Session;
import com.nemaz.school.imports.domain.Cells;
import com.nemaz.school.imports.domain.ImportType;
import com.nemaz.school.imports.domain.Problem;
import com.nemaz.school.imports.domain.RowSource;
import com.nemaz.school.imports.domain.RowSource.RowData;
import com.nemaz.school.imports.infrastructure.SourceOptions;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * The dry run: reads the file again, checks and normalizes every row with the type's handler, stores each row with
 * its outcome and counts them. Nothing but {@code import_job_rows} and the job changes.
 * <p>
 * Idempotent and restartable: the job's lease is taken first (a redelivered event while another worker runs does
 * nothing), earlier rows of the job are deleted, rows are stored in batches of short transactions with the
 * progress counter, and the run ends in VALIDATED or FAILED. A job cancelled meanwhile is left alone.
 */
@Component
class ImportDryRun {

	private static final Logger log = LoggerFactory.getLogger(ImportDryRun.class);

	private final ImportStore store;

	private final ImportFiles files;

	private final ImportRunSupport support;

	private final ImportProperties properties;

	private final AuditLog audit;

	private final JsonMapper json;

	ImportDryRun(ImportStore store, ImportFiles files, ImportRunSupport support, ImportProperties properties,
			AuditLog audit, JsonMapper json) {
		this.store = store;
		this.files = files;
		this.support = support;
		this.properties = properties;
		this.audit = audit;
		this.json = json;
	}

	void run(UUID jobId) {
		Job job = this.store.find(jobId).orElse(null);
		if (job == null || job.status() != Status.VALIDATING) {
			return;
		}
		String owner = UUID.randomUUID().toString();
		if (!claim(jobId, Status.VALIDATING, owner)) {
			return;
		}
		try {
			process(job, owner);
		}
		catch (ApiException ex) {
			fail(jobId, "VALIDATE", null, ex.code());
		}
		catch (RuntimeException ex) {
			log.error("Dry run of import {} failed", jobId, ex);
			fail(jobId, "VALIDATE", null, "import.internal_error");
		}
	}

	private void process(Job job, String owner) {
		List<String> missing = job.type().missing(job.columnMapping().keySet());
		if (!missing.isEmpty()) {
			throw ApiException.badRequest("import.mapping_incomplete", String.join(", ", missing));
		}
		List<RowData> rows;
		try (ImportFiles.Staged file = this.files.stage(job.mediaId())) {
			if (!file.sha256().equals(job.fingerprint())) {
				throw ApiException.conflict("import.file_changed");
			}
			rows = this.files.parse(() -> {
				List<RowData> collected = new ArrayList<>();
				try (RowSource source = this.files.open(file, sourceOptions(job))) {
					source.read(collected::add);
				}
				catch (java.io.IOException ex) {
					throw new java.io.UncheckedIOException(ex);
				}
				return collected;
			});
		}
		JobContext context = this.support.context(job);
		Session session = this.support.handler(job.type()).open(context);
		this.support.inNewTransaction(() -> this.store.deleteRows(job.id()));
		Map<String, Integer> mapping = job.columnMapping();
		List<StoredRow> batch = new ArrayList<>();
		int processed = 0;
		boolean header = true;
		for (RowData row : rows) {
			if (header) {
				header = false;
				continue;
			}
			if (isExample(row)) {
				continue;
			}
			Planned planned = session.plan(input(job.type(), mapping, row));
			batch.add(new StoredRow(row.rowNumber(), row.cells(), planned));
			processed++;
			if (batch.size() >= this.properties.batchSize()) {
				if (!flush(job, owner, batch, processed)) {
					return;
				}
			}
		}
		if (!flush(job, owner, batch, processed)) {
			return;
		}
		Counts counts = this.store.counts(job.id());
		String hash = ImportHashes.validated(job);
		this.support.inNewTransaction(() -> {
			this.store.finishValidation(job.id(), counts, hash);
			this.audit.record(AuditEntry.of("import.validate", AuditOutcome.SUCCESS)
				.actor(job.initiatedBy())
				.target("import_job", job.id())
				.school(job.schoolId())
				.metadata("importType", job.type().name())
				.metadata("rows", counts.total())
				.metadata("valid", counts.valid())
				.metadata("invalid", counts.invalid())
				.metadata("possibleDuplicates", counts.duplicateOpen()));
			Job done = this.store.find(job.id()).orElse(job);
			if (done.status() == Status.VALIDATED) {
				this.support.notifyStage(done, "IMPORT_VALIDATED", "notification.import_validated", done.filename(),
						counts.valid(), counts.invalid(), counts.duplicateOpen());
			}
		});
	}

	/** Stores a batch in its own transaction; false when the job was cancelled (or taken over) meanwhile. */
	private boolean flush(Job job, String owner, List<StoredRow> batch, int processed) {
		Job current = this.store.find(job.id()).orElse(null);
		if (current == null || current.status() != Status.VALIDATING) {
			return false;
		}
		if (!batch.isEmpty()) {
			List<StoredRow> copy = List.copyOf(batch);
			this.support.inNewTransaction(() -> {
				for (StoredRow row : copy) {
					Planned p = row.planned();
					Map<String, Object> normalized = p.normalized();
					this.store.insertRow(job.id(), job.schoolId(), row.rowNumber(), row.cells(), normalized,
							ImportHashes.sha256(this.json.writeValueAsString(normalized)), p.status(), p.action(),
							problems(p.errors()), problems(p.warnings()), p.candidates(), p.entityType(), p.entityId());
				}
				this.store.progress(job.id(), processed);
			});
			batch.clear();
		}
		renew(job.id(), owner);
		return true;
	}

	static List<Map<String, Object>> problems(List<Problem> list) {
		List<Map<String, Object>> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (Problem p : list) {
			if (!seen.add(p.column() + "|" + p.code() + "|" + p.args())) {
				continue;
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("column", p.column());
			m.put("code", p.code());
			if (!p.args().isEmpty()) {
				m.put("args", p.args());
			}
			out.add(m);
		}
		return out;
	}

	/** A row whose first cell starts with {@code #} is the template's example (or a comment): not data. */
	static boolean isExample(RowData row) {
		return !row.cells().isEmpty() && row.cells().getFirst() != null && row.cells().getFirst().strip().startsWith("#");
	}

	static SourceOptions sourceOptions(Job job) {
		ImportOptions o = job.options();
		return new SourceOptions(o.encoding(), o.delimiter(), o.sheet());
	}

	static RowInput input(ImportType type, Map<String, Integer> mapping, RowData row) {
		Map<String, String> values = new LinkedHashMap<>();
		Set<String> nativeDates = new HashSet<>();
		BitSet bits = row.nativeDates();
		for (ImportType.Field field : type.fields()) {
			Integer index = mapping.get(field.key());
			if (index == null) {
				continue;
			}
			String cell = (index < row.cells().size()) ? row.cells().get(index) : null;
			String text = Cells.text(cell);
			if (text != null) {
				values.put(field.key(), text);
				if (bits.get(index)) {
					nativeDates.add(field.key());
				}
			}
		}
		return new RowInput(row.rowNumber(), values, nativeDates, row.warnings());
	}

	private record StoredRow(int rowNumber, List<String> cells, Planned planned) {
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
