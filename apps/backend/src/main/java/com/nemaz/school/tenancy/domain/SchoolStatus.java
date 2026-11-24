package com.nemaz.school.tenancy.domain;

public enum SchoolStatus {

	ONBOARDING, ACTIVE, SUSPENDED, ARCHIVED;

	/** ARCHIVED is terminal and a school never goes back to ONBOARDING. */
	public boolean canChangeTo(SchoolStatus target) {
		return this != ARCHIVED && target != ONBOARDING;
	}

}
