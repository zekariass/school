package com.nemaz.school.tenancy.domain;

import java.util.UUID;

import com.nemaz.school.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "campuses")
public class Campus extends BaseEntity {

	private UUID schoolId;

	private String name;

	private String code;

	private UUID addressId;

	/** Null means the school's timezone. */
	private String timezone;

	@Enumerated(EnumType.STRING)
	private CampusStatus status;

	protected Campus() {
	}

	public Campus(UUID schoolId, String name, String code, String timezone) {
		this.schoolId = schoolId;
		this.name = name;
		this.code = code;
		this.timezone = timezone;
		this.status = CampusStatus.ACTIVE;
	}

	public void update(String name, String code, String timezone, CampusStatus status) {
		this.name = name;
		this.code = code;
		this.timezone = timezone;
		this.status = status;
	}

	public UUID getSchoolId() {
		return this.schoolId;
	}

	public String getName() {
		return this.name;
	}

	public String getCode() {
		return this.code;
	}

	public UUID getAddressId() {
		return this.addressId;
	}

	public String getTimezone() {
		return this.timezone;
	}

	public CampusStatus getStatus() {
		return this.status;
	}

}
