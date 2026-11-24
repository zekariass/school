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

/** Enables or disables a feature at one scope (§80); the plan entitlement is checked separately. */
@Entity
@Table(name = "feature_overrides")
@EntityListeners(AuditingEntityListener.class)
public class FeatureOverride extends IdentifiedEntity {

	private String featureKey;

	@Enumerated(EnumType.STRING)
	private ScopeType scopeType;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 2, columnDefinition = "char(2)")
	private String countryCode;

	private UUID organizationId;

	private UUID schoolId;

	private boolean enabled;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private JsonNode config;

	private UUID updatedBy;

	@LastModifiedDate
	private Instant updatedAt;

	protected FeatureOverride() {
	}

	public FeatureOverride(String featureKey, SettingScope scope, boolean enabled, JsonNode config, UUID updatedBy) {
		this.featureKey = featureKey;
		this.scopeType = scope.type();
		this.countryCode = scope.countryCode();
		this.organizationId = scope.organizationId();
		this.schoolId = scope.schoolId();
		this.enabled = enabled;
		this.config = config;
		this.updatedBy = updatedBy;
	}

	public void change(boolean enabled, JsonNode config, UUID updatedBy) {
		this.enabled = enabled;
		this.config = config;
		this.updatedBy = updatedBy;
	}

	public String getFeatureKey() {
		return this.featureKey;
	}

	public ScopeType getScopeType() {
		return this.scopeType;
	}

	public SettingScope scope() {
		return new SettingScope(this.scopeType, this.countryCode, this.organizationId, this.schoolId);
	}

	public boolean isEnabled() {
		return this.enabled;
	}

	public JsonNode getConfig() {
		return this.config;
	}

	public UUID getUpdatedBy() {
		return this.updatedBy;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

}
