package com.nemaz.school.imports.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.imports.application.ImportModel.Action;
import com.nemaz.school.imports.application.ImportModel.Counts;
import com.nemaz.school.imports.application.ImportModel.Job;
import com.nemaz.school.imports.application.ImportModel.Row;
import com.nemaz.school.imports.application.ImportModel.RowStatus;
import com.nemaz.school.imports.application.ImportModel.Status;
import com.nemaz.school.imports.domain.ImportType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL of import jobs and their rows. Every query that takes a job id after the school id is tenant-scoped. */
@Repository
class ImportStore {

	private static final TypeReference<Map<String, Integer>> INT_MAP = new TypeReference<>() {
	};

	private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
	};

	private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
	};

	private static final TypeReference<List<Map<String, Object>>> OBJECTS = new TypeReference<>() {
	};

	private static final String JOB = """
			select id, school_id, import_type, source_media_id, source_filename, content_fingerprint, mapping_profile_id,
			       column_mapping::text as column_mapping, options::text as options, detected::text as detected,
			       headers::text as headers, external_system_id, status, total_rows, valid_rows, error_rows, duplicate_rows,
			       created_count, updated_count, unchanged_count, skipped_count, progress_done, progress_total,
			       validated_hash, failure_phase, failure_row_number, failure_code, initiated_by, started_at,
			       completed_at, rows_purged_at, created_at, updated_at
			from import_jobs""";

	private static final String ROW = """
			select id, import_job_id, row_number, raw_data::text as raw_data, normalized_data::text as normalized_data,
			       row_hash, status, planned_action, errors::text as errors, warnings::text as warnings,
			       duplicate_candidates::text as candidates, resolution, resolved_entity_id, entity_type, entity_id,
			       apply_result, applied_at
			from import_job_rows""";

	private final JdbcClient jdbc;

	private final JsonMapper json;

	private final Clock clock;

	ImportStore(JdbcClient jdbc, JsonMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.json = json;
		this.clock = clock;
	}

	// --- jobs -------------------------------------------------------------------------------

	Optional<Job> find(UUID schoolId, UUID jobId) {
		return this.jdbc.sql(JOB + " where id = :id and school_id = :s")
			.param("id", jobId)
			.param("s", schoolId)
			.query(this::job)
			.optional();
	}

	Optional<Job> find(UUID jobId) {
		return this.jdbc.sql(JOB + " where id = :id").param("id", jobId).query(this::job).optional();
	}

	List<Job> list(UUID schoolId, Status status, ImportType type, UUID afterId, int limit) {
		StringBuilder where = new StringBuilder(" where school_id = :s");
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("s", schoolId);
		if (status != null) {
			where.append(" and status = :status");
			params.put("status", status.name());
		}
		if (type != null) {
			where.append(" and import_type = :type");
			params.put("type", type.name());
		}
		if (afterId != null) {
			where.append(" and id < :after");
			params.put("after", afterId);
		}
		return this.jdbc.sql(JOB + where + " order by id desc limit " + limit).params(params).query(this::job).list();
	}

	void insert(UUID id, UUID schoolId, ImportType type, UUID mediaId, String filename, String fingerprint,
			UUID profileId, Map<String, Integer> mapping, ImportOptions options, Map<String, Object> detected,
			List<String> headers, UUID externalSystemId, int totalRows, UUID initiatedBy) {
		this.jdbc.sql("""
				insert into import_jobs (id, school_id, import_type, source_media_id, source_filename, content_fingerprint,
				                         mapping_profile_id, column_mapping, options, detected, headers, external_system_id,
				                         status, total_rows, progress_total, initiated_by)
				values (:id, :s, :type, :media, :filename, :fp, :profile, cast(:mapping as jsonb), cast(:options as jsonb),
				        cast(:detected as jsonb), cast(:headers as jsonb), :ext, 'UPLOADED', :total, :total, :by)""")
			.param("id", id)
			.param("s", schoolId)
			.param("type", type.name())
			.param("media", mediaId)
			.param("filename", filename)
			.param("fp", fingerprint)
			.param("profile", profileId)
			.param("mapping", this.json.writeValueAsString(mapping))
			.param("options", this.json.writeValueAsString(options))
			.param("detected", this.json.writeValueAsString(detected))
			.param("headers", this.json.writeValueAsString(headers))
			.param("ext", externalSystemId)
			.param("total", totalRows)
			.param("by", initiatedBy)
			.update();
	}

	/** Mapping, options and what the file looked like with them; the job is PREVIEWED and any earlier check is void. */
	void saveMapping(UUID jobId, UUID profileId, Map<String, Integer> mapping, ImportOptions options,
			Map<String, Object> detected, List<String> headers, int totalRows, Status status) {
		this.jdbc.sql("""
				update import_jobs set mapping_profile_id = :profile, column_mapping = cast(:mapping as jsonb),
				       options = cast(:options as jsonb), detected = cast(:detected as jsonb),
				       headers = cast(:headers as jsonb), total_rows = :total, progress_total = :total, progress_done = 0,
				       status = :status, validated_hash = null, valid_rows = null, error_rows = null, duplicate_rows = null,
				       created_count = null, updated_count = null, unchanged_count = null, skipped_count = null,
				       failure_phase = null, failure_row_number = null, failure_code = null, updated_at = now(),
				       version = version + 1
				where id = :id""")
			.param("profile", profileId)
			.param("mapping", this.json.writeValueAsString(mapping))
			.param("options", this.json.writeValueAsString(options))
			.param("detected", this.json.writeValueAsString(detected))
			.param("headers", this.json.writeValueAsString(headers))
			.param("total", totalRows)
			.param("status", status.name())
			.param("id", jobId)
			.update();
		this.jdbc.sql("delete from import_job_rows where import_job_id = :id").param("id", jobId).update();
	}

	/** Moves the job from one of {@code from} to {@code to}; false when it was in none of them (someone else won). */
	boolean transition(UUID jobId, Status to, Status... from) {
		List<String> names = java.util.Arrays.stream(from).map(Enum::name).toList();
		return this.jdbc.sql("""
				update import_jobs set status = :to, updated_at = now(), version = version + 1
				where id = :id and status in (:from)""")
			.param("to", to.name())
			.param("id", jobId)
			.param("from", names)
			.update() == 1;
	}

	/** Starts a dry run: VALIDATING with fresh progress; false when the job is not in a state that allows it. */
	boolean startValidation(UUID jobId) {
		return this.jdbc.sql("""
				update import_jobs set status = 'VALIDATING', progress_done = 0, validated_hash = null, valid_rows = null,
				       error_rows = null, duplicate_rows = null, created_count = null, updated_count = null,
				       unchanged_count = null, skipped_count = null, failure_phase = null, failure_row_number = null,
				       failure_code = null, lease_owner = null, lease_until = null, started_at = now(), completed_at = null,
				       updated_at = now(), version = version + 1
				where id = :id and status in ('UPLOADED', 'PREVIEWED', 'VALIDATED', 'FAILED')""")
			.param("id", jobId)
			.update() == 1;
	}

	/**
	 * Starts (or resumes) a commit: from VALIDATED with zeroed counters, or from a FAILED commit with the counters it
	 * had. False when the job is in neither state.
	 */
	boolean startCommit(UUID jobId, ImportOptions options, String hash) {
		int fresh = this.jdbc.sql("""
				update import_jobs set status = 'COMMITTING', options = cast(:options as jsonb), progress_done = 0,
				       created_count = 0, updated_count = 0, unchanged_count = 0, skipped_count = 0, failure_phase = null,
				       failure_row_number = null, failure_code = null, lease_owner = null, lease_until = null,
				       started_at = now(), completed_at = null, updated_at = now(), version = version + 1
				where id = :id and status = 'VALIDATED' and validated_hash = :hash""")
			.param("options", this.json.writeValueAsString(options))
			.param("id", jobId)
			.param("hash", hash)
			.update();
		if (fresh == 1) {
			this.jdbc.sql("update import_job_rows set apply_result = null, applied_at = null where import_job_id = :id")
				.param("id", jobId)
				.update();
			return true;
		}
		return this.jdbc.sql("""
				update import_jobs set status = 'COMMITTING', options = cast(:options as jsonb), failure_phase = null,
				       failure_row_number = null, failure_code = null, lease_owner = null, lease_until = null,
				       updated_at = now(), version = version + 1
				where id = :id and status = 'FAILED' and failure_phase = 'COMMIT' and validated_hash = :hash""")
			.param("options", this.json.writeValueAsString(options))
			.param("id", jobId)
			.param("hash", hash)
			.update() == 1;
	}

	/** Takes the job for {@code owner} when it is in {@code status} and nobody holds a live lease. */
	boolean claim(UUID jobId, Status status, String owner, Duration lease) {
		return this.jdbc.sql("""
				update import_jobs set lease_owner = :owner, lease_until = :until
				where id = :id and status = :status and (lease_until is null or lease_until < :now)""")
			.param("owner", owner)
			.param("until", Timestamp.from(this.clock.instant().plus(lease)))
			.param("id", jobId)
			.param("status", status.name())
			.param("now", Timestamp.from(this.clock.instant()))
			.update() == 1;
	}

	void renew(UUID jobId, String owner, Duration lease) {
		this.jdbc.sql("update import_jobs set lease_until = :until where id = :id and lease_owner = :owner")
			.param("until", Timestamp.from(this.clock.instant().plus(lease)))
			.param("id", jobId)
			.param("owner", owner)
			.update();
	}

	void finishValidation(UUID jobId, Counts counts, String hash) {
		this.jdbc.sql("""
				update import_jobs set status = 'VALIDATED', total_rows = :total, valid_rows = :valid, error_rows = :invalid,
				       duplicate_rows = :dup, validated_hash = :hash, progress_done = progress_total, completed_at = now(),
				       lease_owner = null, lease_until = null, updated_at = now(), version = version + 1
				where id = :id and status = 'VALIDATING'""")
			.params(countParams(counts))
			.param("hash", hash)
			.param("id", jobId)
			.update();
	}

	/** Refreshes the stored counts after rows were resolved (the job stays VALIDATED). */
	void saveCounts(UUID jobId, Counts counts) {
		this.jdbc.sql("""
				update import_jobs set valid_rows = :valid, error_rows = :invalid, duplicate_rows = :dup, updated_at = now(),
				       version = version + 1
				where id = :id and status = 'VALIDATED'""")
			.params(countParams(counts))
			.param("id", jobId)
			.update();
	}

	private static Map<String, Object> countParams(Counts c) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("total", c.total());
		params.put("valid", c.valid());
		params.put("invalid", c.invalid());
		params.put("dup", c.duplicateOpen());
		return params;
	}

	void fail(UUID jobId, String phase, Integer rowNumber, String code) {
		this.jdbc.sql("""
				update import_jobs set status = 'FAILED', failure_phase = :phase, failure_row_number = :row,
				       failure_code = :code, lease_owner = null, lease_until = null, completed_at = now(), updated_at = now(),
				       version = version + 1
				where id = :id and status in ('VALIDATING', 'COMMITTING')""")
			.param("phase", phase)
			.param("row", rowNumber)
			.param("code", code)
			.param("id", jobId)
			.update();
	}

	void complete(UUID jobId) {
		this.jdbc.sql("""
				update import_jobs set status = 'COMPLETED', completed_at = now(), lease_owner = null, lease_until = null,
				       progress_done = progress_total, updated_at = now(), version = version + 1
				where id = :id and status = 'COMMITTING'""")
			.param("id", jobId)
			.update();
	}

	void progress(UUID jobId, int done) {
		this.jdbc.sql("update import_jobs set progress_done = :done, updated_at = now() where id = :id")
			.param("done", done)
			.param("id", jobId)
			.update();
	}

	/** Adds a committed batch to the counters (called in the batch's own transaction). */
	void addBatch(UUID jobId, int created, int updated, int unchanged, int skipped, int rows) {
		this.jdbc.sql("""
				update import_jobs set created_count = coalesce(created_count, 0) + :c, updated_count = coalesce(updated_count, 0) + :u,
				       unchanged_count = coalesce(unchanged_count, 0) + :n, skipped_count = coalesce(skipped_count, 0) + :s,
				       progress_done = progress_done + :rows, updated_at = now()
				where id = :id""")
			.param("c", created)
			.param("u", updated)
			.param("n", unchanged)
			.param("s", skipped)
			.param("rows", rows)
			.param("id", jobId)
			.update();
	}

	int running(UUID schoolId) {
		return this.jdbc.sql("select count(*) from import_jobs where school_id = :s and status in ('VALIDATING', 'COMMITTING')")
			.param("s", schoolId)
			.query(Integer.class)
			.single();
	}

	/** Jobs whose worker is gone: validating or committing, no live lease, untouched for {@code idle}. */
	List<UUID> stuck(Duration idle) {
		Instant now = this.clock.instant();
		return this.jdbc.sql("""
				select id from import_jobs where status in ('VALIDATING', 'COMMITTING')
				  and (lease_until is null or lease_until < :now) and updated_at < :idle order by id limit 50""")
			.param("now", Timestamp.from(now))
			.param("idle", Timestamp.from(now.minus(idle)))
			.query(UUID.class)
			.list();
	}

	/** Jobs whose raw rows are due for purging: not purged yet and past the retention of their state. */
	List<UUID> duePurge(Instant createdBefore, Instant completedBefore) {
		return this.jdbc.sql("""
				select id from import_jobs
				where rows_purged_at is null and status not in ('VALIDATING', 'COMMITTING')
				  and (created_at < :created or (status = 'COMPLETED' and completed_at < :completed))
				order by id limit 200""")
			.param("created", Timestamp.from(createdBefore))
			.param("completed", Timestamp.from(completedBefore))
			.query(UUID.class)
			.list();
	}

	void markPurged(UUID jobId, boolean file) {
		this.jdbc.sql("""
				update import_jobs set rows_purged_at = now(), file_purged_at = case when :file then now() else file_purged_at end,
				       updated_at = now() where id = :id""")
			.param("file", file)
			.param("id", jobId)
			.update();
	}

	void delete(UUID jobId) {
		this.jdbc.sql("delete from import_jobs where id = :id").param("id", jobId).update();
	}

	// --- rows -------------------------------------------------------------------------------

	void insertRow(UUID jobId, UUID schoolId, int rowNumber, List<String> raw, Map<String, Object> normalized, String hash,
			RowStatus status, Action action, List<Map<String, Object>> errors, List<Map<String, Object>> warnings,
			List<Map<String, Object>> candidates, String entityType, UUID entityId) {
		this.jdbc.sql("""
				insert into import_job_rows (import_job_id, school_id, row_number, raw_data, normalized_data, row_hash, status,
				                             planned_action, errors, warnings, duplicate_candidates, entity_type, entity_id)
				values (:job, :s, :n, cast(:raw as jsonb), cast(:norm as jsonb), :hash, :status, :action, cast(:errors as jsonb),
				        cast(:warnings as jsonb), cast(:cand as jsonb), :etype, :eid)""")
			.param("job", jobId)
			.param("s", schoolId)
			.param("n", rowNumber)
			.param("raw", this.json.writeValueAsString(raw))
			.param("norm", this.json.writeValueAsString(normalized))
			.param("hash", hash)
			.param("status", status.name())
			.param("action", (action != null) ? action.name() : null)
			.param("errors", errors.isEmpty() ? null : this.json.writeValueAsString(errors))
			.param("warnings", warnings.isEmpty() ? null : this.json.writeValueAsString(warnings))
			.param("cand", candidates.isEmpty() ? null : this.json.writeValueAsString(candidates))
			.param("etype", entityType)
			.param("eid", entityId)
			.update();
	}

	void deleteRows(UUID jobId) {
		this.jdbc.sql("delete from import_job_rows where import_job_id = :id").param("id", jobId).update();
	}

	List<Row> rows(UUID jobId, RowStatus status, boolean onlyOpenDuplicates, int afterRow, int limit) {
		StringBuilder where = new StringBuilder(" where import_job_id = :job and row_number > :after");
		if (status != null) {
			where.append(" and status = :status");
		}
		if (onlyOpenDuplicates) {
			where.append(" and status = 'DUPLICATE' and resolution is null");
		}
		var query = this.jdbc.sql(ROW + where + " order by row_number limit " + limit).param("job", jobId).param("after", afterRow);
		if (status != null) {
			query = query.param("status", status.name());
		}
		return query.query(this::row).list();
	}

	/** Rows with problems or warnings, for the error report. */
	List<Row> rowsWithProblems(UUID jobId, int afterRow, int limit) {
		return this.jdbc.sql(ROW + " " + """
				where import_job_id = :job and row_number > :after
				  and (errors is not null or warnings is not null or (status = 'DUPLICATE' and resolution is null))
				 order by row_number limit""" + " " + limit)
			.param("job", jobId)
			.param("after", afterRow)
			.query(this::row)
			.list();
	}

	Optional<Row> row(UUID jobId, UUID rowId) {
		return this.jdbc.sql(ROW + " where import_job_id = :job and id = :id")
			.param("job", jobId)
			.param("id", rowId)
			.query(this::row)
			.optional();
	}

	/** Rows still to apply: not applied, not purged, in file order after {@code afterRow}. */
	List<Row> unapplied(UUID jobId, int afterRow, int limit) {
		return this.jdbc.sql(ROW + " " + """
				where import_job_id = :job and row_number > :after and apply_result is null
				 order by row_number limit""" + " " + limit)
			.param("job", jobId)
			.param("after", afterRow)
			.query(this::row)
			.list();
	}

	void resolveRow(UUID rowId, RowStatus status, Action action, String resolution, UUID entityId, Map<String, Object> normalized) {
		this.jdbc.sql("""
				update import_job_rows set status = :status, planned_action = :action, resolution = :resolution,
				       resolved_entity_id = :entity, entity_type = case when cast(:entity as uuid) is null then entity_type else 'STUDENT' end,
				       entity_id = :entity, normalized_data = cast(:norm as jsonb)
				where id = :id""")
			.param("status", status.name())
			.param("action", (action != null) ? action.name() : null)
			.param("resolution", resolution)
			.param("entity", entityId)
			.param("norm", this.json.writeValueAsString(normalized))
			.param("id", rowId)
			.update();
	}

	void markApplied(UUID rowId, String result, String entityType, UUID entityId) {
		this.jdbc.sql("""
				update import_job_rows set apply_result = :result, applied_at = now(), entity_type = coalesce(:etype, entity_type),
				       entity_id = coalesce(:eid, entity_id)
				where id = :id""")
			.param("result", result)
			.param("etype", entityType)
			.param("eid", entityId)
			.param("id", rowId)
			.update();
	}

	Counts counts(UUID jobId) {
		return this.jdbc.sql("""
				select count(*) as total,
				       count(*) filter (where status = 'VALID') as valid,
				       count(*) filter (where status = 'INVALID') as invalid,
				       count(*) filter (where status = 'DUPLICATE') as dup,
				       count(*) filter (where status = 'DUPLICATE' and resolution is null) as dup_open,
				       count(*) filter (where status = 'SKIPPED') as skipped,
				       count(*) filter (where status = 'VALID' and planned_action = 'CREATE') as to_create,
				       count(*) filter (where status = 'VALID' and planned_action = 'UPDATE') as to_update,
				       count(*) filter (where status = 'VALID' and planned_action = 'NONE') as unchanged
				from import_job_rows where import_job_id = :id""")
			.param("id", jobId)
			.query((rs, i) -> new Counts(rs.getInt("total"), rs.getInt("valid"), rs.getInt("invalid"), rs.getInt("dup"),
					rs.getInt("dup_open"), rs.getInt("skipped"), rs.getInt("to_create"), rs.getInt("to_update"),
					rs.getInt("unchanged")))
			.single();
	}

	/** Deletes the payloads of the job's rows (PII); the rows themselves stay for the counts. */
	void purgePayloads(UUID jobId) {
		this.jdbc.sql("""
				update import_job_rows set raw_data = null, normalized_data = null, duplicate_candidates = null,
				       errors = null, warnings = null where import_job_id = :id""")
			.param("id", jobId)
			.update();
	}

	// --- mapping ----------------------------------------------------------------------------

	private Job job(ResultSet rs, int i) throws SQLException {
		return new Job(Rows.uuid(rs, "id"), Rows.uuid(rs, "school_id"), ImportType.valueOf(rs.getString("import_type")),
				Rows.uuid(rs, "source_media_id"), rs.getString("source_filename"), rs.getString("content_fingerprint"),
				Rows.uuid(rs, "mapping_profile_id"), read(rs.getString("column_mapping"), INT_MAP, Map.of()),
				read(rs.getString("options"), new TypeReference<ImportOptions>() {
				}, ImportOptions.DEFAULT), read(rs.getString("detected"), OBJECT_MAP, Map.of()),
				read(rs.getString("headers"), STRINGS, List.of()), Rows.uuid(rs, "external_system_id"),
				Status.valueOf(rs.getString("status")), Rows.integer(rs, "total_rows"), Rows.integer(rs, "valid_rows"),
				Rows.integer(rs, "error_rows"), Rows.integer(rs, "duplicate_rows"), Rows.integer(rs, "created_count"),
				Rows.integer(rs, "updated_count"), Rows.integer(rs, "unchanged_count"), Rows.integer(rs, "skipped_count"),
				rs.getInt("progress_done"), rs.getInt("progress_total"), rs.getString("validated_hash"),
				rs.getString("failure_phase"), Rows.integer(rs, "failure_row_number"), rs.getString("failure_code"),
				Rows.uuid(rs, "initiated_by"), Rows.instant(rs, "started_at"), Rows.instant(rs, "completed_at"),
				Rows.instant(rs, "rows_purged_at"), Rows.instant(rs, "created_at"), Rows.instant(rs, "updated_at"));
	}

	private Row row(ResultSet rs, int i) throws SQLException {
		String action = rs.getString("planned_action");
		return new Row(Rows.uuid(rs, "id"), Rows.uuid(rs, "import_job_id"), rs.getInt("row_number"),
				read(rs.getString("raw_data"), STRINGS, null), read(rs.getString("normalized_data"), OBJECT_MAP, null),
				rs.getString("row_hash"), RowStatus.valueOf(rs.getString("status")),
				(action != null) ? Action.valueOf(action) : null, read(rs.getString("errors"), OBJECTS, List.of()),
				read(rs.getString("warnings"), OBJECTS, List.of()), read(rs.getString("candidates"), OBJECTS, List.of()),
				rs.getString("resolution"), Rows.uuid(rs, "resolved_entity_id"), rs.getString("entity_type"),
				Rows.uuid(rs, "entity_id"), rs.getString("apply_result"), Rows.instant(rs, "applied_at"));
	}

	private <T> T read(String text, TypeReference<T> type, T fallback) {
		return (text == null) ? fallback : this.json.readValue(text, type);
	}

	/** The stored jsonb of a mapping profile: field to header label. */
	Map<String, String> parseLabels(String text) {
		return this.json.readValue(text, new TypeReference<Map<String, String>>() {
		});
	}

}
