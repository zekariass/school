package com.nemaz.school.tenancy.domain;

import java.util.UUID;

import com.nemaz.school.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The tenant. Media columns (logo, cover) and the education system are added by later
 * milestones (M2, P2).
 */
@Entity
@Table(name = "schools")
public class School extends BaseEntity {

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 2, columnDefinition = "char(2)")
	private String countryCode;

	private UUID organizationId;

	private String name;

	private String shortName;

	@Column(columnDefinition = "citext")
	private String slug;

	@Enumerated(EnumType.STRING)
	private SchoolType schoolType;

	@Enumerated(EnumType.STRING)
	private OwnershipType ownershipType;

	private String defaultLanguageCode;

	private String timezone;

	private String defaultCalendarCode;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 3, columnDefinition = "char(3)")
	private String currencyCode;

	private UUID addressId;

	private String phoneE164;

	@Column(columnDefinition = "citext")
	private String email;

	private String website;

	private String brandColor;

	@Enumerated(EnumType.STRING)
	private SchoolStatus status;

	protected School() {
	}

	/** Builder-style creation keeps the many optional profile fields readable at the call site. */
	public static School create(String countryCode, UUID organizationId, String name, String slug,
			String defaultLanguageCode, String timezone, String defaultCalendarCode, String currencyCode) {
		School s = new School();
		s.countryCode = countryCode;
		s.organizationId = organizationId;
		s.name = name;
		s.slug = slug;
		s.defaultLanguageCode = defaultLanguageCode;
		s.timezone = timezone;
		s.defaultCalendarCode = defaultCalendarCode;
		s.currencyCode = currencyCode;
		s.status = SchoolStatus.ONBOARDING;
		return s;
	}

	public School profile(String shortName, SchoolType schoolType, OwnershipType ownershipType, String phoneE164,
			String email, String website, String brandColor) {
		this.shortName = shortName;
		this.schoolType = schoolType;
		this.ownershipType = ownershipType;
		this.phoneE164 = phoneE164;
		this.email = email;
		this.website = website;
		this.brandColor = brandColor;
		return this;
	}

	public void changeStatus(SchoolStatus status) {
		this.status = status;
	}

	public String getCountryCode() {
		return this.countryCode;
	}

	public UUID getOrganizationId() {
		return this.organizationId;
	}

	public String getName() {
		return this.name;
	}

	public String getShortName() {
		return this.shortName;
	}

	public String getSlug() {
		return this.slug;
	}

	public SchoolType getSchoolType() {
		return this.schoolType;
	}

	public OwnershipType getOwnershipType() {
		return this.ownershipType;
	}

	public String getDefaultLanguageCode() {
		return this.defaultLanguageCode;
	}

	public String getTimezone() {
		return this.timezone;
	}

	public String getDefaultCalendarCode() {
		return this.defaultCalendarCode;
	}

	public String getCurrencyCode() {
		return this.currencyCode;
	}

	public UUID getAddressId() {
		return this.addressId;
	}

	public String getPhoneE164() {
		return this.phoneE164;
	}

	public String getEmail() {
		return this.email;
	}

	public String getWebsite() {
		return this.website;
	}

	public String getBrandColor() {
		return this.brandColor;
	}

	public SchoolStatus getStatus() {
		return this.status;
	}

}
