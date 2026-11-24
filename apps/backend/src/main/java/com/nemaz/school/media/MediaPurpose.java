package com.nemaz.school.media;

/**
 * What a piece of media is for (Database_Design §4.9). Object keys derive from it. A purpose
 * is usable once its owning module registers a {@link MediaPurposePolicy}; until then creating
 * media for it is refused with {@code media.purpose_not_supported}.
 */
public enum MediaPurpose {

	AVATAR, POST, STORY, MESSAGE, PORTFOLIO, SUBMISSION, ASSIGNMENT, LIBRARY, COURSE, RECORDING, TRANSCRIPT,
	VERIFICATION_DOC, IMPORT_FILE, REPORT, BRANDING;

	/** The path segment in object keys: {@code VERIFICATION_DOC} becomes {@code verification-doc}. */
	public String keySegment() {
		return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
	}

}
