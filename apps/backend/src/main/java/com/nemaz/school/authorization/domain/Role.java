package com.nemaz.school.authorization.domain;

import java.util.Locale;
import java.util.Map;

import com.nemaz.school.common.security.ScopeLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A code-owned role (R__40_roles_permissions.sql); read-only for the application. */
@Entity
@Table(name = "roles")
public class Role {

	@Id
	private String code;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "name_i18n", columnDefinition = "jsonb")
	private Map<String, String> nameI18n;

	@Enumerated(EnumType.STRING)
	private ScopeLevel scopeLevel;

	@Column(name = "is_system")
	private boolean system;

	private String description;

	protected Role() {
	}

	public String getCode() {
		return this.code;
	}

	public ScopeLevel getScopeLevel() {
		return this.scopeLevel;
	}

	public boolean isSystem() {
		return this.system;
	}

	public String getDescription() {
		return this.description;
	}

	/** The name in {@code locale}'s language, else English, else the code. */
	public String name(Locale locale) {
		if (this.nameI18n == null) {
			return this.code;
		}
		String language = (locale != null) ? locale.getLanguage() : "en";
		String name = this.nameI18n.get(language);
		if (name == null) {
			name = this.nameI18n.get("en");
		}
		return (name != null) ? name : this.code;
	}

}
