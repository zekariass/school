package com.nemaz.school.reference.domain;

import java.time.Instant;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** A country, keyed by its ISO 3166-1 code. Only ENABLED countries accept new schools. */
@Entity
@Table(name = "countries")
@EntityListeners(AuditingEntityListener.class)
public class Country {

	@Id
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 2, columnDefinition = "char(2)")
	private String code;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private Map<String, String> nameI18n;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "default_currency_code", length = 3, columnDefinition = "char(3)")
	private String defaultCurrencyCode;

	private String defaultLanguageCode;

	private String defaultTimezone;

	private String defaultCalendarCode;

	private String phoneCountryCode;

	/** Labels per administrative level and language, required levels, postal rules. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private JsonNode addressFormat;

	/** Labels for given / middle / family name (R-37). */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private JsonNode nameFormat;

	@Enumerated(EnumType.STRING)
	private CountryStatus status;

	private Instant launchedAt;

	@Version
	private int version;

	@CreatedDate
	private Instant createdAt;

	@LastModifiedDate
	private Instant updatedAt;

	protected Country() {
	}

	public String getCode() {
		return this.code;
	}

	public Map<String, String> getNameI18n() {
		return this.nameI18n;
	}

	public String getDefaultCurrencyCode() {
		return this.defaultCurrencyCode;
	}

	public String getDefaultLanguageCode() {
		return this.defaultLanguageCode;
	}

	public String getDefaultTimezone() {
		return this.defaultTimezone;
	}

	public String getDefaultCalendarCode() {
		return this.defaultCalendarCode;
	}

	public String getPhoneCountryCode() {
		return this.phoneCountryCode;
	}

	public JsonNode getAddressFormat() {
		return this.addressFormat;
	}

	public JsonNode getNameFormat() {
		return this.nameFormat;
	}

	public CountryStatus getStatus() {
		return this.status;
	}

	public Instant getLaunchedAt() {
		return this.launchedAt;
	}

	public boolean isEnabled() {
		return this.status == CountryStatus.ENABLED;
	}

}
