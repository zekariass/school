package com.nemaz.school.tenancy.domain;

import java.time.Instant;
import java.util.UUID;

import com.nemaz.school.common.persistence.IdentifiedEntity;
import com.nemaz.school.tenancy.ScopeType;
import com.nemaz.school.tenancy.SettingScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** One scoped override of a setting definition. Exactly the key of {@code scopeType} is set. */
@Entity
@Table(name = "settings")
@EntityListeners(AuditingEntityListener.class)
public class Setting extends IdentifiedEntity {

	@Column(name = "key")
	private String key;

	@Enumerated(EnumType.STRING)
	private ScopeType scopeType;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 2, columnDefinition = "char(2)")
	private String countryCode;

	private UUID organizationId;

	private UUID schoolId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private JsonNode value;

	private UUID updatedBy;

	@LastModifiedDate
	private Instant updatedAt;

	protected Setting() {
	}

	public Setting(String key, SettingScope scope, JsonNode value, UUID updatedBy) {
		this.key = key;
		this.scopeType = scope.type();
		this.countryCode = scope.countryCode();
		this.organizationId = scope.organizationId();
		this.schoolId = scope.schoolId();
		this.value = value;
		this.updatedBy = updatedBy;
	}

	public void change(JsonNode value, UUID updatedBy) {
		this.value = value;
		this.updatedBy = updatedBy;
	}

	public String getKey() {
		return this.key;
	}

	public ScopeType getScopeType() {
		return this.scopeType;
	}

	public SettingScope scope() {
		return new SettingScope(this.scopeType, this.countryCode, this.organizationId, this.schoolId);
	}

	public JsonNode getValue() {
		return this.value;
	}

	public UUID getUpdatedBy() {
		return this.updatedBy;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

}
