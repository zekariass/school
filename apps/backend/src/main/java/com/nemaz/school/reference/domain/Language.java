package com.nemaz.school.reference.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** BCP 47 language; keyed by code. */
@Entity
@Table(name = "languages")
public class Language {

	@Id
	private String code;

	private String name;

	private String nativeName;

	private String script;

	private String direction;

	@Column(name = "is_enabled")
	private boolean enabled;

	protected Language() {
	}

	public String getCode() {
		return this.code;
	}

	public String getName() {
		return this.name;
	}

	public String getNativeName() {
		return this.nativeName;
	}

	public String getScript() {
		return this.script;
	}

	public String getDirection() {
		return this.direction;
	}

	public boolean isEnabled() {
		return this.enabled;
	}

}
