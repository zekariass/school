package com.nemaz.school.reference.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** ISO 4217 currency; keyed by code, so it does not extend BaseEntity. */
@Entity
@Table(name = "currencies")
public class Currency {

	@Id
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 3, columnDefinition = "char(3)")
	private String code;

	private String name;

	private String symbol;

	private short minorUnits;

	protected Currency() {
	}

	public String getCode() {
		return this.code;
	}

	public String getName() {
		return this.name;
	}

	public String getSymbol() {
		return this.symbol;
	}

	public short getMinorUnits() {
		return this.minorUnits;
	}

}
