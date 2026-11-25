package com.nemaz.school.imports.application;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.persistence.Constraints;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.imports.domain.HeaderSynonyms;
import com.nemaz.school.imports.domain.ImportType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saved column mappings of a school (per import type): field key to the header text it was found under, so the same
 * export of the school's own system maps itself next time. A new import applies the most recently saved profile whose
 * headers are all present and complete the required fields; the user may also name a profile.
 */
@Service
@Transactional
public class MappingProfileService {

	private final JdbcClient jdbc;

	private final SchoolGate gate;

	private final JsonMapper json;

	private final AuditLog audit;

	MappingProfileService(JdbcClient jdbc, SchoolGate gate, JsonMapper json, AuditLog audit) {
		this.jdbc = jdbc;
		this.gate = gate;
		this.json = json;
		this.audit = audit;
	}

	public record ProfileView(@NotNull UUID id, @NotNull String name, @NotNull ImportType importType,
			@Schema(description = "Field key to the header text of the column") @NotNull Map<String, String> columnMapping,
			@NotNull Instant updatedAt) {
	}

	/** A profile applied to a file: field key to column index. */
	record Applied(UUID id, String name, Map<String, Integer> mapping) {
	}

	@Transactional(readOnly = true)
	public List<ProfileView> list(CurrentUser user, UUID schoolId, ImportType type) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_IMPORT_MANAGE);
		return this.jdbc.sql("""
				select id, name, import_type, column_mapping::text as mapping, updated_at from import_mapping_profiles
				where school_id = :s and (cast(:type as text) is null or import_type = :type) order by lower(name), id""")
			.param("s", schoolId)
			.param("type", (type != null) ? type.name() : null)
			.query(this::view)
			.list();
	}

	public ProfileView create(CurrentUser user, UUID schoolId, String name, ImportType type, Map<String, String> mapping) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_IMPORT_MANAGE);
		validate(type, name, mapping);
		UUID id = Ids.newId();
		try {
			this.jdbc.sql("""
					insert into import_mapping_profiles (id, school_id, name, import_type, column_mapping, created_by)
					values (:id, :s, :name, :type, cast(:mapping as jsonb), :by)""")
				.param("id", id)
				.param("s", schoolId)
				.param("name", name.strip())
				.param("type", type.name())
				.param("mapping", this.json.writeValueAsString(mapping))
				.param("by", user.userId())
				.update();
		}
		catch (DataIntegrityViolationException ex) {
			throw nameTaken(ex);
		}
		this.audit.record(AuditEntry.of("import.profile.create", AuditOutcome.SUCCESS)
			.target("import_mapping_profile", id)
			.school(schoolId)
			.metadata("importType", type.name()));
		return find(schoolId, id);
	}

	public ProfileView update(CurrentUser user, UUID schoolId, UUID id, String name, Map<String, String> mapping) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_IMPORT_MANAGE);
		ProfileView before = find(schoolId, id);
		validate(before.importType(), name, mapping);
		try {
			this.jdbc.sql("""
					update import_mapping_profiles set name = :name, column_mapping = cast(:mapping as jsonb), updated_at = now(),
					       version = version + 1 where id = :id""")
				.param("name", name.strip())
				.param("mapping", this.json.writeValueAsString(mapping))
				.param("id", id)
				.update();
		}
		catch (DataIntegrityViolationException ex) {
			throw nameTaken(ex);
		}
		this.audit.record(AuditEntry.of("import.profile.update", AuditOutcome.SUCCESS)
			.target("import_mapping_profile", id)
			.school(schoolId));
		return find(schoolId, id);
	}

	public void delete(CurrentUser user, UUID schoolId, UUID id) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_IMPORT_MANAGE);
		find(schoolId, id);
		// Jobs keep the mapping they ran with; only their link to the profile ends.
		this.jdbc.sql("update import_jobs set mapping_profile_id = null where mapping_profile_id = :id and school_id = :s")
			.param("id", id)
			.param("s", schoolId)
			.update();
		this.jdbc.sql("delete from import_mapping_profiles where id = :id").param("id", id).update();
		this.audit.record(AuditEntry.of("import.profile.delete", AuditOutcome.SUCCESS)
			.target("import_mapping_profile", id)
			.school(schoolId));
	}

	/** The named profile applied to the headers; 404 when it is not this school's or not of this type. */
	@Transactional(readOnly = true)
	Applied apply(UUID schoolId, UUID profileId, ImportType type, List<String> headers) {
		ProfileView profile = find(schoolId, profileId);
		if (profile.importType() != type) {
			throw ApiException.badRequest("import.profile_type_mismatch");
		}
		return new Applied(profile.id(), profile.name(), resolve(profile.columnMapping(), headers));
	}

	/** The newest profile of the type whose every header is in the file and that completes the required fields. */
	@Transactional(readOnly = true)
	Optional<Applied> autoApply(UUID schoolId, ImportType type, List<String> headers) {
		List<ProfileView> candidates = this.jdbc.sql("""
				select id, name, import_type, column_mapping::text as mapping, updated_at from import_mapping_profiles
				where school_id = :s and import_type = :type order by updated_at desc, id desc limit 20""")
			.param("s", schoolId)
			.param("type", type.name())
			.query(this::view)
			.list();
		for (ProfileView p : candidates) {
			Map<String, Integer> mapping = resolve(p.columnMapping(), headers);
			if (mapping.size() == p.columnMapping().size() && type.missing(mapping.keySet()).isEmpty()) {
				return Optional.of(new Applied(p.id(), p.name(), mapping));
			}
		}
		return Optional.empty();
	}

	/** Field key to column index for the fields whose header text is in the file (each column once). */
	static Map<String, Integer> resolve(Map<String, String> labels, List<String> headers) {
		List<String> normalized = headers.stream().map(HeaderSynonyms::normalize).toList();
		Map<String, Integer> mapping = new LinkedHashMap<>();
		Set<Integer> used = new HashSet<>();
		labels.forEach((field, label) -> {
			String wanted = HeaderSynonyms.normalize(label);
			for (int i = 0; i < normalized.size(); i++) {
				if (!used.contains(i) && normalized.get(i).equals(wanted)) {
					mapping.put(field, i);
					used.add(i);
					return;
				}
			}
		});
		return mapping;
	}

	private ProfileView find(UUID schoolId, UUID id) {
		return this.jdbc.sql("""
				select id, name, import_type, column_mapping::text as mapping, updated_at from import_mapping_profiles
				where id = :id and school_id = :s""")
			.param("id", id)
			.param("s", schoolId)
			.query(this::view)
			.optional()
			.orElseThrow(() -> ApiException.notFound("import.profile_not_found"));
	}

	private void validate(ImportType type, String name, Map<String, String> mapping) {
		if (name == null || name.isBlank() || name.length() > 100) {
			throw ApiException.badRequest("import.profile_name_invalid");
		}
		if (mapping == null || mapping.isEmpty()) {
			throw ApiException.badRequest("import.mapping_incomplete", String.join(", ", type.requiredFields()));
		}
		for (Map.Entry<String, String> e : mapping.entrySet()) {
			if (!type.has(e.getKey())) {
				throw ApiException.badRequest("import.mapping_unknown_field", e.getKey());
			}
			if (e.getValue() == null || e.getValue().isBlank() || e.getValue().length() > 200) {
				throw ApiException.badRequest("import.mapping_column_invalid", e.getKey());
			}
		}
		List<String> missing = type.missing(mapping.keySet());
		if (!missing.isEmpty()) {
			throw ApiException.badRequest("import.mapping_incomplete", String.join(", ", missing));
		}
	}

	private ProfileView view(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		Map<String, String> mapping = this.json.readValue(rs.getString("mapping"), new TypeReference<Map<String, String>>() {
		});
		return new ProfileView(Rows.uuid(rs, "id"), rs.getString("name"), ImportType.valueOf(rs.getString("import_type")),
				mapping, Rows.instant(rs, "updated_at"));
	}

	private static ApiException nameTaken(DataIntegrityViolationException ex) {
		if (Constraints.violated(ex, "ux_import_profiles_name")) {
			return ApiException.conflict("import.profile_name_taken");
		}
		throw ex;
	}

}
