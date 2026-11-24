package com.nemaz.school.tenancy.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A code-owned feature key (seeded by R__20_definitions.sql). */
@Entity
@Table(name = "feature_definitions")
public class FeatureDefinition {

	@Id
	private String key;

	private String description;

	private boolean defaultEnabled;

	protected FeatureDefinition() {
	}

	public String getKey() {
		return this.key;
	}

	public String getDescription() {
		return this.description;
	}

	public boolean isDefaultEnabled() {
		return this.defaultEnabled;
	}

}
