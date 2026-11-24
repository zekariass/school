package com.nemaz.school.reference.domain;

import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.persistence.IdentifiedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Region > Zone > Woreda (ET), County > Sub-county (KE): generic levels (§68). */
@Entity
@Table(name = "administrative_areas")
public class AdministrativeArea extends IdentifiedEntity {

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 2, columnDefinition = "char(2)")
	private String countryCode;

	private UUID parentId;

	private short level;

	private String code;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private Map<String, String> nameI18n;

	protected AdministrativeArea() {
	}

	public AdministrativeArea(String countryCode, UUID parentId, int level, String code,
			Map<String, String> nameI18n) {
		this.countryCode = countryCode;
		this.parentId = parentId;
		this.level = (short) level;
		this.code = code;
		this.nameI18n = Map.copyOf(nameI18n);
	}

	public String getCountryCode() {
		return this.countryCode;
	}

	public UUID getParentId() {
		return this.parentId;
	}

	public int getLevel() {
		return this.level;
	}

	public String getCode() {
		return this.code;
	}

	public Map<String, String> getNameI18n() {
		return this.nameI18n;
	}

}
