package com.nemaz.school.media;

import com.nemaz.school.common.security.CurrentUser;

/**
 * Who may create and read media of one {@link MediaPurpose} (R-30: access derives from the
 * owning object, there is no ACL table). The module that owns the object implements it as a
 * Spring bean, so the media module depends on no business module. Implementations must deny
 * by default and must not reveal whether a context exists.
 */
public interface MediaPurposePolicy {

	MediaPurpose purpose();

	/** May {@code user} upload media of this purpose to {@code context}? */
	boolean canCreate(CurrentUser user, MediaContext context);

	/** May {@code user} read this READY media (its signed URLs)? The owner may always read it. */
	boolean canRead(CurrentUser user, MediaInfo media);

	/**
	 * May {@code user} delete or attach this media? The default is the uploader, or anyone who
	 * may create media in the same context (the administrators of the owning object).
	 */
	default boolean canManage(CurrentUser user, MediaInfo media) {
		return media.ownerId().equals(user.userId()) || canCreate(user, media.context());
	}

	/**
	 * The school that owns the context, recorded on the media row ({@code media.school_id}). The default knows only
	 * SCHOOL contexts; a policy whose contexts belong to a school through another object (a class) overrides it.
	 */
	default java.util.UUID schoolOf(MediaContext context) {
		return (context.type() == ContextType.SCHOOL) ? context.id() : null;
	}

	/** Whether each signed-URL read is written to the audit log (sensitive purposes). */
	default boolean auditReads() {
		return false;
	}

}
