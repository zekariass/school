package com.nemaz.school.tenancy.api;

import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.tenancy.ScopeType;
import com.nemaz.school.tenancy.SettingScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** A level of the configuration hierarchy: set exactly the key of {@code type}. */
@Schema(description = "A configuration scope. PLATFORM sets no key, COUNTRY sets countryCode, "
		+ "ORGANIZATION sets organizationId, SCHOOL sets schoolId.")
public record ScopeRef(@NotNull(message = Validation.REQUIRED) ScopeType type, String countryCode,
		UUID organizationId, UUID schoolId) {

	public static ScopeRef of(SettingScope scope) {
		return new ScopeRef(scope.type(), scope.countryCode(), scope.organizationId(), scope.schoolId());
	}

	/** The domain scope; {@code scope.invalid} (400) when keys do not match the type. */
	public SettingScope toScope() {
		if (this.type == null) {
			throw ApiException.badRequest("scope.invalid");
		}
		try {
			return new SettingScope(this.type,
					(this.countryCode != null) ? this.countryCode.toUpperCase(java.util.Locale.ROOT) : null,
					this.organizationId, this.schoolId);
		}
		catch (IllegalArgumentException ex) {
			throw ApiException.badRequest("scope.invalid");
		}
	}

}
