package com.nemaz.school.imports.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.imports.ImportCommitRequested;
import com.nemaz.school.imports.ImportDryRunRequested;
import com.nemaz.school.imports.application.ImportModel.Action;
import com.nemaz.school.imports.application.ImportModel.Counts;
import com.nemaz.school.imports.application.ImportModel.Job;
import com.nemaz.school.imports.application.ImportModel.Row;
import com.nemaz.school.imports.application.ImportModel.RowStatus;
import com.nemaz.school.imports.application.ImportModel.Status;
import com.nemaz.school.imports.application.ImportViews.AppliedProfile;
import com.nemaz.school.imports.application.ImportViews.CommitResult;
import com.nemaz.school.imports.application.ImportViews.Detected;
import com.nemaz.school.imports.application.ImportViews.FieldView;
import com.nemaz.school.imports.application.ImportViews.Failure;
import com.nemaz.school.imports.application.ImportViews.ImportJobView;
import com.nemaz.school.imports.application.ImportViews.ImportPreview;
import com.nemaz.school.imports.application.ImportViews.ImportRowView;
import com.nemaz.school.imports.application.ImportViews.ImportTypeView;
import com.nemaz.school.imports.application.ImportViews.PreviewRow;
import com.nemaz.school.imports.application.ImportViews.Progress;
import com.nemaz.school.imports.application.ImportViews.ProblemView;
import com.nemaz.school.imports.application.ImportViews.RowCounts;
import com.nemaz.school.imports.domain.HeaderSynonyms;
import com.nemaz.school.imports.domain.ImportType;
import com.nemaz.school.imports.domain.RowSource;
import com.nemaz.school.imports.domain.RowSource.RowData;
import com.nemaz.school.imports.domain.RowSource.SourceInfo;
import com.nemaz.school.media.MediaContext;
import com.nemaz.school.media.MediaPurpose;
import com.nemaz.school.media.MediaService;
import com.nemaz.school.people.PeopleImports;
import com.nemaz.school.system.RateLimiter;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The import workflow the API drives (see {@code docs/research/M4_Roster_Import.md}):
 * <pre>
 * UPLOADED --mapping--> PREVIEWED --dry-run--> VALIDATING --> VALIDATED --commit--> COMMITTING --> COMPLETED
 *                                                  |                                   |
 *                                                FAILED  (dry run again)             FAILED (commit again resumes)
 * any state before COMMITTING --cancel--> CANCELLED
 * </pre>
 * Everyone who touches a job needs {@code school.import.manage} on its school; a job of another school is 404.
 */
@Service
@Transactional
public class ImportService {

	private static final List<Status> MAPPABLE = List.of(Status.UPLOADED, Status.PREVIEWED, Status.VALIDATED, Status.FAILED);

	private final ImportStore store;

	private final ImportFiles files;

	private final MediaService media;

	private final SchoolGate gate;

	private final ImportProperties properties;

	private final RateLimiter limiter;

	private final ExternalMappings mappings;

	private final MappingProfileService profiles;

	private final MessageSource messages;

	private final ProblemMessages problemMessages;

	private final PeopleImports people;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	private final JdbcClient jdbc;

	ImportService(ImportStore store, ImportFiles files, MediaService media, SchoolGate gate, ImportProperties properties,
			RateLimiter limiter, ExternalMappings mappings, MappingProfileService profiles, MessageSource messages,
			ProblemMessages problemMessages, PeopleImports people, AuditLog audit, ApplicationEventPublisher events, JdbcClient jdbc) {
		this.store = store;
		this.files = files;
		this.media = media;
		this.gate = gate;
		this.properties = properties;
		this.limiter = limiter;
		this.mappings = mappings;
		this.profiles = profiles;
		this.messages = messages;
		this.problemMessages = problemMessages;
		this.people = people;
		this.audit = audit;
		this.events = events;
		this.jdbc = jdbc;
	}

	public record CreateImport(ImportType importType, UUID mediaId, ImportOptions options, UUID mappingProfileId) {
	}

	public record SaveMapping(Map<String, Integer> columnMapping, ImportOptions options, String saveAsProfile) {
	}

	public record Resolve(ImportViews.Resolution resolution, UUID studentId) {
	}

	// --- types ------------------------------------------------------------------------------

	@Transactional(readOnly = true)
	public List<ImportTypeView> types(CurrentUser user, UUID schoolId) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_IMPORT_MANAGE);
		List<ImportTypeView> result = new ArrayList<>();
		for (ImportType type : ImportType.values()) {
			result.add(new ImportTypeView(type, label("import.type." + type.name().toLowerCase(Locale.ROOT)), fields(type),
					type.requiredFields()));
		}
		return result;
	}

	// --- create and preview -----------------------------------------------------------------

	public ImportPreview create(CurrentUser user, UUID schoolId, CreateImport r) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_IMPORT_MANAGE);
		this.limiter.consume("import-create", user.userId().toString(), this.properties.createsPerHour(), Duration.ofHours(1),
				"import.rate_limited");
		this.media.requireAttachable(r.mediaId(), MediaPurpose.IMPORT_FILE, MediaContext.school(schoolId), user);
		ImportOptions options = ((r.options() != null) ? r.options() : ImportOptions.DEFAULT).forReading();
		UUID id = Ids.newId();
		try (ImportFiles.Staged file = this.files.stage(r.mediaId())) {
			Scan scan = scan(file, options);
			if (scan.headers().isEmpty()) {
				throw ApiException.badRequest("import.empty_file");
			}
			MappingProfileService.Applied profile = null;
			Map<String, Integer> mapping = new LinkedHashMap<>();
			if (r.mappingProfileId() != null) {
				profile = this.profiles.apply(schoolId, r.mappingProfileId(), r.importType(), scan.headers());
			}
			else {
				profile = this.profiles.autoApply(schoolId, r.importType(), scan.headers()).orElse(null);
			}
			if (profile != null) {
				mapping.putAll(profile.mapping());
			}
			topUp(mapping, r.importType(), scan.headers());
			UUID system = this.mappings.system(schoolId);
			this.store.insert(id, schoolId, r.importType(), r.mediaId(), file.filename(), file.sha256(),
					(profile != null) ? profile.id() : null, mapping, options, detected(scan), scan.headers(), system,
					scan.totalRows(), user.userId());
		}
		this.audit.record(AuditEntry.of("import.create", AuditOutcome.SUCCESS)
			.target("import_job", id)
			.school(schoolId)
			.metadata("importType", r.importType().name())
			.metadata("rows", this.store.find(id).map(Job::totalRows).orElse(null)));
		return preview(load(schoolId, id));
	}

	@Transactional(readOnly = true)
	public ImportPreview preview(CurrentUser user, UUID schoolId, UUID jobId) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_IMPORT_MANAGE);
		return preview(load(schoolId, jobId));
	}

	/** Re-reads the file with the job's options: headers and first rows for the wizard's mapping step. */
	private ImportPreview preview(Job job) {
		try (ImportFiles.Staged file = this.files.stage(job.mediaId())) {
			Scan scan = scan(file, job.options());
			List<String> missing = job.type().missing(job.columnMapping().keySet());
			AppliedProfile applied = (job.mappingProfileId() == null) ? null : this.jdbc
				.sql("select id, name from import_mapping_profiles where id = :id and school_id = :s")
				.param("id", job.mappingProfileId())
				.param("s", job.schoolId())
				.query((rs, i) -> new AppliedProfile(rs.getObject("id", UUID.class), rs.getString("name")))
				.optional()
				.orElse(null);
			return new ImportPreview(view(job, true), detectedView(scan.info()), scan.headers(), scan.preview(),
					job.columnMapping(), job.type().requiredFields(), missing, fields(job.type()), applied);
		}
	}

	public ImportPreview saveMapping(CurrentUser user, UUID schoolId, UUID jobId, SaveMapping r) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_IMPORT_MANAGE);
		Job job = load(schoolId, jobId);
		if (!MAPPABLE.contains(job.status())) {
			throw ApiException.conflict("import.invalid_state");
		}
		ImportOptions options = (r.options() != null) ? r.options().forReading() : job.options();
		Scan scan;
		try (ImportFiles.Staged file = this.files.stage(job.mediaId())) {
			scan = scan(file, options);
		}
		Map<String, Integer> mapping = validateMapping(job.type(), r.columnMapping(), scan.headers().size());
		this.store.saveMapping(jobId, job.mappingProfileId(), mapping, options, detected(scan), scan.headers(), scan.totalRows(),
				Status.PREVIEWED);
		if (r.saveAsProfile() != null && !r.saveAsProfile().isBlank()) {
			Map<String, String> labels = new LinkedHashMap<>();
			mapping.forEach((field, index) -> labels.put(field, scan.headers().get(index)));
			this.profiles.create(user, schoolId, r.saveAsProfile(), job.type(), labels);
		}
		this.audit.record(AuditEntry.of("import.mapping", AuditOutcome.SUCCESS)
			.target("import_job", jobId)
			.school(schoolId)
			.metadata("fields", mapping.size()));
		return preview(load(schoolId, jobId));
	}

	private Map<String, Integer> validateMapping(ImportType type, Map<String, Integer> given, int columns) {
		if (given == null) {
			throw ApiException.badRequest("import.mapping_incomplete", String.join(", ", type.requiredFields()));
		}
		Map<String, Integer> mapping = new LinkedHashMap<>();
		Set<Integer> used = new HashSet<>();
		for (Map.Entry<String, Integer> e : given.entrySet()) {
			if (e.getValue() == null) {
				continue;
			}
			if (!type.has(e.getKey())) {
				throw ApiException.badRequest("import.mapping_unknown_field", e.getKey());
			}
			if (e.getValue() < 0 || e.getValue() >= columns) {
				throw ApiException.badRequest("import.mapping_column_invalid", e.getKey());
			}
			if (!used.add(e.getValue())) {
				throw ApiException.badRequest("import.mapping_column_reused", e.getKey());
			}
			mapping.put(e.getKey(), e.getValue());
		}
		List<String> missing = type.missing(mapping.keySet());
		if (!missing.isEmpty()) {
			throw ApiException.badRequest("import.mapping_incomplete", String.join(", ", missing));
		}
		return mapping;
	}

	/** Fills the fields a profile did not cover from header synonyms, never reusing a column. */
	private void topUp(Map<String, Integer> mapping, ImportType type, List<String> headers) {
		Map<String, Integer> suggested = HeaderSynonyms.suggest(type, headers);
		Set<Integer> used = new HashSet<>(mapping.values());
		suggested.forEach((field, index) -> {
			if (!mapping.containsKey(field) && !used.contains(index)) {
				mapping.put(field, index);
				used.add(index);
			}
		});
	}

	// --- dry run and commit -----------------------------------------------------------------

	public ImportJobView startDryRun(CurrentUser user, UUID schoolId, UUID jobId) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_IMPORT_MANAGE);
		Job job = load(schoolId, jobId);
		List<String> missing = job.type().missing(job.columnMapping().keySet());
		if (!missing.isEmpty()) {
			throw ApiException.badRequest("import.mapping_incomplete", String.join(", ", missing));
		}
		requireCapacity(schoolId);
		if (!this.store.startValidation(jobId)) {
			throw ApiException.conflict("import.invalid_state");
		}
		this.events.publishEvent(new ImportDryRunRequested(jobId));
		this.audit.record(AuditEntry.of("import.dry_run", AuditOutcome.SUCCESS).target("import_job", jobId).school(schoolId));
		return view(load(schoolId, jobId), false);
	}

	public ImportJobView startCommit(CurrentUser user, UUID schoolId, UUID jobId, Boolean skipInvalidRows) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_IMPORT_MANAGE);
		Job job = load(schoolId, jobId);
		boolean resume = job.status() == Status.FAILED && "COMMIT".equals(job.failurePhase());
		if (job.status() != Status.VALIDATED && !resume) {
			throw ApiException.conflict("import.invalid_state");
		}
		if (job.validatedHash() == null || !job.validatedHash().equals(ImportHashes.validated(job))) {
			throw ApiException.conflict("import.changed_since_dry_run");
		}
		Counts counts = this.store.counts(jobId);
		if (counts.duplicateOpen() > 0) {
			throw ApiException.conflict("import.has_open_duplicates", counts.duplicateOpen());
		}
		boolean skip = Boolean.TRUE.equals(skipInvalidRows) || (resume && job.options().skipInvalid());
		if (!skip && counts.invalid() > 0) {
			throw ApiException.conflict("import.has_invalid_rows", counts.invalid());
		}
		requireCapacity(schoolId);
		ImportOptions options = new ImportOptions(job.options().encoding(), job.options().delimiter(), job.options().sheet(),
				job.options().dateSystemOrDefault(), skip);
		if (!this.store.startCommit(jobId, options, job.validatedHash())) {
			throw ApiException.conflict("import.invalid_state");
		}
		this.events.publishEvent(new ImportCommitRequested(jobId));
		this.audit.record(AuditEntry.of("import.commit.start", AuditOutcome.SUCCESS)
			.target("import_job", jobId)
			.school(schoolId)
			.metadata("skipInvalidRows", skip)
			.metadata("resumed", resume));
		return view(load(schoolId, jobId), false);
	}

	private void requireCapacity(UUID schoolId) {
		if (this.store.running(schoolId) >= this.properties.runningPerSchool()) {
			throw ApiException.tooManyRequests("import.too_many_running", 30);
		}
	}

	// --- reading ----------------------------------------------------------------------------

	@Transactional(readOnly = true)
	public ImportJobView get(CurrentUser user, UUID schoolId, UUID jobId) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_IMPORT_MANAGE);
		return view(load(schoolId, jobId), true);
	}

	@Transactional(readOnly = true)
	public CursorPage<ImportJobView> list(CurrentUser user, UUID schoolId, Status status, ImportType type, String cursor,
			Integer limit) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_IMPORT_MANAGE);
		int size = Cursors.clampLimit(limit);
		UUID after = null;
		if (cursor != null) {
			try {
				after = UUID.fromString(Cursors.decode(cursor, 1).getFirst());
			}
			catch (IllegalArgumentException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		List<Job> jobs = this.store.list(schoolId, status, type, after, size + 1);
		CursorPage<Job> page = CursorPage.fromOverfetch(jobs, size, (j) -> Cursors.encode(j.id().toString()));
		return page.map((j) -> view(j, false));
	}

	@Transactional(readOnly = true)
	public CursorPage<ImportRowView> rows(CurrentUser user, UUID schoolId, UUID jobId, RowStatus status, String cursor,
			Integer limit) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_IMPORT_MANAGE);
		Job job = load(schoolId, jobId);
		int size = Cursors.clampLimit(limit);
		int after = 0;
		if (cursor != null) {
			try {
				after = Integer.parseInt(Cursors.decode(cursor, 1).getFirst());
			}
			catch (NumberFormatException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		List<Row> rows = this.store.rows(job.id(), status, false, after, size + 1);
		CursorPage<Row> page = CursorPage.fromOverfetch(rows, size, (r) -> Cursors.encode(String.valueOf(r.rowNumber())));
		return page.map(this::rowView);
	}

	// --- resolving possible duplicates ------------------------------------------------------

	public ImportRowView resolve(CurrentUser user, UUID schoolId, UUID jobId, UUID rowId, Resolve r) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_IMPORT_MANAGE);
		Job job = load(schoolId, jobId);
		if (job.status() != Status.VALIDATED && !(job.status() == Status.FAILED && "COMMIT".equals(job.failurePhase()))) {
			throw ApiException.conflict("import.invalid_state");
		}
		Row row = this.store.row(jobId, rowId).orElseThrow(() -> ApiException.notFound("import.row_not_found"));
		if (row.normalized() == null) {
			throw ApiException.conflict("import.rows_purged");
		}
		if (row.applyResult() != null) {
			throw ApiException.conflict("import.row_already_applied");
		}
		String resolution = r.resolution().name();
		Map<String, Object> normalized = new LinkedHashMap<>(row.normalized());
		switch (r.resolution()) {
			case SKIP -> this.store.resolveRow(rowId, RowStatus.SKIPPED, Action.NONE, "SKIP", null, normalized);
			case LINK -> {
				requireCandidates(row);
				if (r.studentId() == null) {
					throw ApiException.badRequest("import.student_required");
				}
				this.people.student(schoolId, r.studentId())
					.filter(PeopleImports.StudentSnapshot::live)
					.orElseThrow(() -> ApiException.notFound("import.student_not_found"));
				normalized.put("matchedId", r.studentId().toString());
				normalized.put("matchType", "RESOLVED");
				this.store.resolveRow(rowId, RowStatus.VALID, Action.UPDATE, "LINK", r.studentId(), normalized);
			}
			case CREATE_NEW -> {
				requireCandidates(row);
				normalized.remove("matchedId");
				normalized.remove("matchType");
				this.store.resolveRow(rowId, RowStatus.VALID, Action.CREATE, "CREATE_NEW", null, normalized);
			}
		}
		this.store.saveCounts(jobId, this.store.counts(jobId));
		this.audit.record(AuditEntry.of("import.row.resolve", AuditOutcome.SUCCESS)
			.target("import_job", jobId)
			.school(schoolId)
			.metadata("row", row.rowNumber())
			.metadata("resolution", resolution));
		return rowView(this.store.row(jobId, rowId).orElseThrow());
	}

	private static void requireCandidates(Row row) {
		if (row.candidates().isEmpty()) {
			throw ApiException.conflict("import.row_not_duplicate");
		}
	}

	// --- cancel and delete ------------------------------------------------------------------

	public ImportJobView cancel(CurrentUser user, UUID schoolId, UUID jobId) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_IMPORT_MANAGE);
		Job job = load(schoolId, jobId);
		if (!this.store.transition(jobId, Status.CANCELLED, Status.UPLOADED, Status.PREVIEWED, Status.VALIDATING,
				Status.VALIDATED, Status.FAILED)) {
			throw ApiException.conflict("import.invalid_state");
		}
		this.store.purgePayloads(jobId);
		this.audit.record(AuditEntry.of("import.cancel", AuditOutcome.SUCCESS)
			.target("import_job", jobId)
			.school(schoolId)
			.metadata("from", job.status().name()));
		return view(load(schoolId, jobId), false);
	}

	public void delete(CurrentUser user, UUID schoolId, UUID jobId) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_IMPORT_MANAGE);
		Job job = load(schoolId, jobId);
		if (job.status() == Status.VALIDATING || job.status() == Status.COMMITTING) {
			throw ApiException.conflict("import.running");
		}
		this.store.delete(jobId);
		releaseFile(job);
		this.audit.record(AuditEntry.of("import.delete", AuditOutcome.SUCCESS)
			.target("import_job", jobId)
			.school(schoolId)
			.metadata("status", job.status().name()));
	}

	/** Discards the uploaded file unless another job of any school still reads it. */
	void releaseFile(Job job) {
		boolean shared = this.jdbc.sql("select exists (select 1 from import_jobs where source_media_id = :m and id <> :id)")
			.param("m", job.mediaId())
			.param("id", job.id())
			.query(Boolean.class)
			.single();
		if (!shared) {
			this.media.discard(job.mediaId());
		}
	}

	// --- helpers ----------------------------------------------------------------------------

	Job load(UUID schoolId, UUID jobId) {
		return this.store.find(schoolId, jobId).orElseThrow(() -> ApiException.notFound("import.not_found"));
	}

	/** What reading the file with these options found: header, first rows, row count and what the reader detected. */
	record Scan(List<String> headers, List<PreviewRow> preview, int totalRows, SourceInfo info, int exampleRows) {
	}

	private Scan scan(ImportFiles.Staged file, ImportOptions options) {
		return this.files.parse(() -> {
			List<String> headers = new ArrayList<>();
			List<PreviewRow> preview = new ArrayList<>();
			int[] counts = new int[2];
			boolean[] first = { true };
			try (RowSource source = this.files.open(file,
					new com.nemaz.school.imports.infrastructure.SourceOptions(options.encoding(), options.delimiter(), options.sheet()))) {
				source.read((row) -> accept(row, first, headers, preview, counts));
				return new Scan(List.copyOf(headers), List.copyOf(preview), counts[0], source.info(), counts[1]);
			}
			catch (java.io.IOException ex) {
				throw new java.io.UncheckedIOException(ex);
			}
		});
	}

	private void accept(RowData row, boolean[] first, List<String> headers, List<PreviewRow> preview, int[] counts) {
		if (first[0]) {
			first[0] = false;
			row.cells().forEach((c) -> headers.add((c == null) ? "" : c.strip()));
			return;
		}
		boolean example = ImportDryRun.isExample(row);
		if (example) {
			counts[1]++;
		}
		else {
			counts[0]++;
		}
		if (preview.size() < this.properties.previewRows()) {
			preview.add(new PreviewRow(row.rowNumber(), row.cells(), example));
		}
	}

	private static Map<String, Object> detected(Scan scan) {
		SourceInfo i = scan.info();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("format", i.format());
		m.put("encoding", i.encoding());
		m.put("delimiter", i.delimiter());
		m.put("sheetNames", i.sheetNames());
		m.put("sheet", i.sheet());
		m.put("date1904", i.date1904());
		m.put("formulaCells", i.formulaCells());
		m.put("mergedRegions", i.mergedRegions());
		m.put("exampleRows", scan.exampleRows());
		return m;
	}

	private static Detected detectedView(SourceInfo i) {
		return new Detected(i.format(), i.encoding(), i.delimiter(), i.sheetNames(), i.sheet(), i.date1904(), i.formulaCells(),
				i.mergedRegions());
	}

	private List<FieldView> fields(ImportType type) {
		return type.fields()
			.stream()
			.map((f) -> new FieldView(f.key(), label("import.field." + f.key()), f.required(), f.oneOf()))
			.toList();
	}

	private String label(String key) {
		return this.messages.getMessage(key, null, key, LocaleContextHolder.getLocale());
	}

	ImportJobView view(Job job, boolean live) {
		RowCounts rows = null;
		if (job.validRows() != null) {
			if (live && job.rowsPurgedAt() == null && job.status() != Status.VALIDATING) {
				Counts c = this.store.counts(job.id());
				rows = new RowCounts(c.total(), c.valid(), c.invalid(), c.duplicateOpen(), c.skipped(), c.toCreate(),
						c.toUpdate(), c.unchanged());
			}
			else {
				rows = new RowCounts(value(job.totalRows()), value(job.validRows()), value(job.errorRows()),
						value(job.duplicateRows()), null, null, null, null);
			}
		}
		CommitResult result = (job.createdCount() == null) ? null : new CommitResult(value(job.createdCount()),
				value(job.updatedCount()), value(job.unchangedCount()), value(job.skippedCount()));
		Failure failure = (job.status() == Status.FAILED && job.failureCode() != null) ? new Failure(job.failurePhase(),
				job.failureRowNumber(), job.failureCode(), this.problemMessages.job(job.failureCode(), LocaleContextHolder.getLocale())) : null;
		return new ImportJobView(job.id(), job.type(), job.status(), job.filename(), job.mediaId(), job.mappingProfileId(),
				job.columnMapping(), job.options(), new Progress(job.progressDone(), job.progressTotal()), rows, result, failure,
				job.initiatedBy(), job.createdAt(), job.startedAt(), job.completedAt(), job.rowsPurgedAt() != null);
	}

	private ImportRowView rowView(Row row) {
		return new ImportRowView(row.id(), row.rowNumber(), row.status(), row.action(), row.resolution(), row.applyResult(),
				row.raw(), row.normalized(), problems(row.errors()), problems(row.warnings()), row.candidates(), row.entityId());
	}

	private List<ProblemView> problems(List<Map<String, Object>> stored) {
		List<ProblemView> out = new ArrayList<>();
		for (Map<String, Object> p : stored) {
			String code = String.valueOf(p.get("code"));
			@SuppressWarnings("unchecked")
			List<Object> args = (p.get("args") instanceof List<?> l) ? (List<Object>) l : List.of();
			out.add(new ProblemView((String) p.get("column"), code, this.problemMessages.row(code, args)));
		}
		return out;
	}

	private static int value(Integer n) {
		return (n == null) ? 0 : n;
	}

}
