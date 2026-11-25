package com.nemaz.school.imports.application;

import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.common.id.Ids;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * External ids of the school's own system (spec section 125): {@code external_entity_mappings} maps an id from the
 * file to the platform's entity so that a later file updates the entity instead of duplicating it. Files are
 * "CSV" system data; connectors for school information systems will add systems of their own.
 */
@Component
class ExternalMappings {

	static final String CSV_SYSTEM = "CSV";

	private final JdbcClient jdbc;

	ExternalMappings(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** The school's "CSV import" external system, created on first use. */
	UUID system(UUID schoolId) {
		this.jdbc.sql("""
				insert into external_systems (id, school_id, system_type, name) values (:id, :s, 'CSV', 'CSV import')
				on conflict (school_id, system_type, name) do nothing""")
			.param("id", Ids.newId())
			.param("s", schoolId)
			.update();
		return this.jdbc.sql("select id from external_systems where school_id = :s and system_type = 'CSV' and name = 'CSV import'")
			.param("s", schoolId)
			.query(UUID.class)
			.single();
	}

	Optional<UUID> find(UUID systemId, String entityType, String externalId) {
		return this.jdbc.sql("""
				select internal_id from external_entity_mappings
				where external_system_id = :sys and entity_type = :type and external_id = :ext""")
			.param("sys", systemId)
			.param("type", entityType)
			.param("ext", externalId)
			.query(UUID.class)
			.optional();
	}

	void put(UUID schoolId, UUID systemId, String entityType, String externalId, UUID internalId) {
		this.jdbc.sql("""
				insert into external_entity_mappings (id, school_id, external_system_id, entity_type, external_id, internal_id,
				                                      last_synced_at)
				values (:id, :s, :sys, :type, :ext, :internal, now())
				on conflict (external_system_id, entity_type, external_id)
				do update set internal_id = excluded.internal_id, last_synced_at = now()""")
			.param("id", Ids.newId())
			.param("s", schoolId)
			.param("sys", systemId)
			.param("type", entityType)
			.param("ext", externalId)
			.param("internal", internalId)
			.update();
	}

}
