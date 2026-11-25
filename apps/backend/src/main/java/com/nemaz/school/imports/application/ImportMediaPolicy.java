package com.nemaz.school.imports.application;

import com.nemaz.school.common.security.AccessControl;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.media.ContextType;
import com.nemaz.school.media.MediaContext;
import com.nemaz.school.media.MediaInfo;
import com.nemaz.school.media.MediaPurpose;
import com.nemaz.school.media.MediaPurposePolicy;
import com.nemaz.school.tenancy.TenantDirectory;

import org.springframework.stereotype.Component;

/**
 * Roster files ({@code IMPORT_FILE}): they hold the personal data of children, so a school's import files are for
 * the people who hold {@code school.import.manage} on that school, to upload and to read. Every read is audited.
 * Access ends with the permission, even for the uploader.
 */
@Component
class ImportMediaPolicy implements MediaPurposePolicy {

	private final AccessControl access;

	private final TenantDirectory directory;

	ImportMediaPolicy(AccessControl access, TenantDirectory directory) {
		this.access = access;
		this.directory = directory;
	}

	@Override
	public MediaPurpose purpose() {
		return MediaPurpose.IMPORT_FILE;
	}

	@Override
	public boolean canCreate(CurrentUser user, MediaContext context) {
		return context.type() == ContextType.SCHOOL
				&& this.access.can(user, Permissions.SCHOOL_IMPORT_MANAGE, AccessScope.school(context.id()))
				&& this.directory.school(context.id()).isPresent();
	}

	@Override
	public boolean canRead(CurrentUser user, MediaInfo media) {
		return canCreate(user, media.context());
	}

	@Override
	public boolean auditReads() {
		return true;
	}

}
