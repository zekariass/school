package com.nemaz.school.safeguarding.application;

import com.nemaz.school.common.security.AccessControl;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.media.ContextType;
import com.nemaz.school.media.MediaContext;
import com.nemaz.school.media.MediaInfo;
import com.nemaz.school.media.MediaPurpose;
import com.nemaz.school.media.MediaPurposePolicy;

import org.springframework.stereotype.Component;

/**
 * Attachments of safeguarding incident notes (purpose SAFEGUARDING, docs/Safeguarding_Rules.md S4.4): uploaded into the school's
 * context by a safeguarding lead who may work incidents, readable ONLY by safeguarding leads of that school (platform leads
 * everywhere). Not even the uploader keeps access once they stop being a lead, and every signed-URL read is audited.
 */
@Component
class IncidentMediaPolicy implements MediaPurposePolicy {

	private final AccessControl access;

	IncidentMediaPolicy(AccessControl access) {
		this.access = access;
	}

	@Override
	public MediaPurpose purpose() {
		return MediaPurpose.SAFEGUARDING;
	}

	@Override
	public boolean canCreate(CurrentUser user, MediaContext context) {
		return context.type() == ContextType.SCHOOL
				&& this.access.can(user, Permissions.SCHOOL_SAFEGUARDING_MANAGE, AccessScope.school(context.id()));
	}

	@Override
	public boolean canRead(CurrentUser user, MediaInfo media) {
		return media.schoolId() != null
				&& this.access.can(user, Permissions.SCHOOL_SAFEGUARDING_READ, AccessScope.school(media.schoolId()));
	}

	@Override
	public boolean ownerAlwaysReads(MediaInfo media) {
		return false;
	}

	@Override
	public boolean auditReads() {
		return true;
	}

}
