package com.nemaz.school.tenancy.domain;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** A language a school operates in (§110); exactly one is the default. */
@Entity
@Table(name = "school_languages")
@IdClass(SchoolLanguage.Key.class)
public class SchoolLanguage {

	@Id
	private UUID schoolId;

	@Id
	private String languageCode;

	private boolean isDefault;

	protected SchoolLanguage() {
	}

	public SchoolLanguage(UUID schoolId, String languageCode, boolean isDefault) {
		this.schoolId = schoolId;
		this.languageCode = languageCode;
		this.isDefault = isDefault;
	}

	public UUID getSchoolId() {
		return this.schoolId;
	}

	public String getLanguageCode() {
		return this.languageCode;
	}

	public boolean isDefault() {
		return this.isDefault;
	}

	public record Key(UUID schoolId, String languageCode) implements Serializable {
	}

}
