package com.nemaz.school.tenancy;

/** Which level of the precedence chain supplied an effective value. */
public enum ConfigSource {

	SCHOOL, ORGANIZATION, COUNTRY, PLATFORM,
	/** No override at any level: the definition's default applies. */
	DEFAULT;

	public static ConfigSource of(ScopeType type) {
		return switch (type) {
			case SCHOOL -> SCHOOL;
			case ORGANIZATION -> ORGANIZATION;
			case COUNTRY -> COUNTRY;
			case PLATFORM -> PLATFORM;
		};
	}

}
