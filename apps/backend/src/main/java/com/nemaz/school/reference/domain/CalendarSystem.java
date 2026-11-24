package com.nemaz.school.reference.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "calendar_systems")
public class CalendarSystem {

	@Id
	private String code;

	private String name;

	protected CalendarSystem() {
	}

	public String getCode() {
		return this.code;
	}

	public String getName() {
		return this.name;
	}

}
