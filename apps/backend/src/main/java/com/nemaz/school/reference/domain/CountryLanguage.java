package com.nemaz.school.reference.domain;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "country_languages")
@IdClass(CountryLanguage.Key.class)
public class CountryLanguage {

	@Id
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 2, columnDefinition = "char(2)")
	private String countryCode;

	@Id
	private String languageCode;

	private boolean isDefault;

	private boolean isOfficial;

	private short sortOrder;

	protected CountryLanguage() {
	}

	public String getCountryCode() {
		return this.countryCode;
	}

	public String getLanguageCode() {
		return this.languageCode;
	}

	public boolean isDefault() {
		return this.isDefault;
	}

	public boolean isOfficial() {
		return this.isOfficial;
	}

	public short getSortOrder() {
		return this.sortOrder;
	}

	public record Key(String countryCode, String languageCode) implements Serializable {
	}

}
