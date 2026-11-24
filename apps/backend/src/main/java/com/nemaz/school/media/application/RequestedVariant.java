package com.nemaz.school.media.application;

import com.nemaz.school.media.MediaVariant;

/** What {@code GET /media/{id}/url} can be asked for: a stored variant, or the original object. */
public enum RequestedVariant {

	ORIGINAL(null), THUMB(MediaVariant.THUMB), SMALL(MediaVariant.SMALL), MEDIUM(MediaVariant.MEDIUM),
	LARGE(MediaVariant.LARGE), POSTER(MediaVariant.POSTER), PDF_PREVIEW(MediaVariant.PDF_PREVIEW);

	private final MediaVariant variant;

	RequestedVariant(MediaVariant variant) {
		this.variant = variant;
	}

	/** The stored variant, null for {@link #ORIGINAL}. */
	MediaVariant variant() {
		return this.variant;
	}

}
