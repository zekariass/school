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

/** A group of schools under one owner (a chain, a diocese, a ministry office). */
@Entity
@Table(name = "organizations")
public class Organization extends BaseEntity {

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 2, columnDefinition = "char(2)")
	private String countryCode;

	private String name;

	private String legalName;

	@Column(columnDefinition = "citext")
	private String slug;

	private UUID addressId;

	private UUID logoMediaId;

	@Enumerated(EnumType.STRING)
	private OrganizationStatus status;

	protected Organization() {
	}

	public Organization(String countryCode, String name, String legalName, String slug) {
		this.countryCode = countryCode;
		this.name = name;
		this.legalName = legalName;
		this.slug = slug;
		this.status = OrganizationStatus.ACTIVE;
	}

	public void update(String name, String legalName, OrganizationStatus status) {
		this.name = name;
		this.legalName = legalName;
		this.status = status;
	}

	public void setLogoMediaId(UUID mediaId) {
		this.logoMediaId = mediaId;
	}

	public UUID getLogoMediaId() {
		return this.logoMediaId;
	}

	public String getCountryCode() {
		return this.countryCode;
	}

	public String getName() {
		return this.name;
	}

	public String getLegalName() {
		return this.legalName;
	}

	public String getSlug() {
		return this.slug;
	}

	public UUID getAddressId() {
		return this.addressId;
	}

	public OrganizationStatus getStatus() {
		return this.status;
	}

}
