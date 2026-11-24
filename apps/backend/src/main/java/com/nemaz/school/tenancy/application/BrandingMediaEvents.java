package com.nemaz.school.tenancy.application;

import com.nemaz.school.media.MediaDeleted;
import com.nemaz.school.tenancy.infrastructure.OrganizationRepository;
import com.nemaz.school.tenancy.infrastructure.SchoolRepository;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Forgets logos and covers whose media was deleted (idempotent). */
@Component
class BrandingMediaEvents {

	private final SchoolRepository schools;

	private final OrganizationRepository organizations;

	BrandingMediaEvents(SchoolRepository schools, OrganizationRepository organizations) {
		this.schools = schools;
		this.organizations = organizations;
	}

	@ApplicationModuleListener
	void on(MediaDeleted event) {
		this.schools.clearLogo(event.mediaId());
		this.schools.clearCover(event.mediaId());
		this.organizations.clearLogo(event.mediaId());
	}

}
