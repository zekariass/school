package com.nemaz.school.media;

/** A derived rendition of a media object (Database_Design §4.9). */
public enum MediaVariant {

	/** Longest side at most 256 px. */
	THUMB,
	/** Longest side at most 640 px. */
	SMALL,
	/** Longest side at most 1280 px. */
	MEDIUM,
	LARGE, HLS_MASTER, POSTER,
	/** First page of a PDF, as an image (longest side at most 1280 px). */
	PDF_PREVIEW

}
