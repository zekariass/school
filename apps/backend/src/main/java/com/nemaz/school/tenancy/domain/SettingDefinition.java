package com.nemaz.school.tenancy.domain;

import java.util.List;

import com.nemaz.school.tenancy.ScopeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/** A typed, code-owned setting key (seeded by R__20_definitions.sql). */
@Entity
@Table(name = "setting_definitions")
public class SettingDefinition {

	@Id
	private String key;

	private String module;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private JsonNode valueSchema;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private JsonNode defaultValue;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(columnDefinition = "text[]")
	private List<String> allowedScopes;

	private String description;

	protected SettingDefinition() {
	}

	public String getKey() {
		return this.key;
	}

	public String getModule() {
		return this.module;
	}

	public JsonNode getValueSchema() {
		return this.valueSchema;
	}

	public JsonNode getDefaultValue() {
		return this.defaultValue;
	}

	public List<String> getAllowedScopes() {
		return this.allowedScopes;
	}

	public boolean allows(ScopeType scope) {
		return this.allowedScopes.contains(scope.name());
	}

	public String getDescription() {
		return this.description;
	}

}
