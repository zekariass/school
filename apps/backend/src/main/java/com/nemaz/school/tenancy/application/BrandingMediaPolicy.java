package com.nemaz.school.tenancy.application;

import com.nemaz.school.common.security.AccessControl;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.media.MediaContext;
import com.nemaz.school.media.MediaInfo;
import com.nemaz.school.media.MediaPurpose;
import com.nemaz.school.media.MediaPurposePolicy;
import com.nemaz.school.tenancy.infrastructure.OrganizationRepository;
import com.nemaz.school.tenancy.infrastructure.SchoolRepository;

import org.springframework.stereotype.Component;

/**
 * Logos and cover images of schools and organizations (R-30: derived from the owning object).
 * <ul>
 * <li><b>Create:</b> {@code school.manage} on the school, or {@code platform.organizations.manage}
 * on the organization (the same permissions that edit them), and the target must exist.</li>
 * <li><b>Read:</b> any signed-in user: logos appear in headers and school pickers.</li>
 * </ul>
 */
@Component
class BrandingMediaPolicy implements MediaPurposePolicy {

	private final AccessControl access;

	private final SchoolRepository schools;

	private final OrganizationRepository organizations;

	BrandingMediaPolicy(AccessControl access, SchoolRepository schools, OrganizationRepository organizations) {
		this.access = access;
		this.schools = schools;
		this.organizations = organizations;
	}

	@Override
	public MediaPurpose purpose() {
		return MediaPurpose.BRANDING;
	}

	@Override
	public boolean canCreate(CurrentUser user, MediaContext context) {
		return switch (context.type()) {
			case SCHOOL -> this.access.can(user, Permissions.SCHOOL_MANAGE, AccessScope.school(context.id()))
					&& this.schools.existsById(context.id());
			case ORGANIZATION -> this.access.can(user, Permissions.PLATFORM_ORGANIZATIONS_MANAGE,
					AccessScope.organization(context.id())) && this.organizations.existsById(context.id());
			case USER, CLASS, CONVERSATION -> false;
		};
	}

	@Override
	public boolean canRead(CurrentUser user, MediaInfo media) {
		return true;
	}

}
