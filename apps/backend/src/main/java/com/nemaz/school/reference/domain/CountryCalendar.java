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
@Table(name = "country_calendars")
@IdClass(CountryCalendar.Key.class)
public class CountryCalendar {

	@Id
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 2, columnDefinition = "char(2)")
	private String countryCode;

	@Id
	private String calendarCode;

	private boolean isDefault;

	protected CountryCalendar() {
	}

	public String getCountryCode() {
		return this.countryCode;
	}

	public String getCalendarCode() {
		return this.calendarCode;
	}

	public boolean isDefault() {
		return this.isDefault;
	}

	public record Key(String countryCode, String calendarCode) implements Serializable {
	}

}
