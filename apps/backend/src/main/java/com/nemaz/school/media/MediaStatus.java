package com.nemaz.school.media;

/** Lifecycle of a media row. */
public enum MediaStatus {

	/** The upload URL was issued; the client has not completed yet. */
	PENDING_UPLOAD,
	/** The object exists and passed the size, type and magic-byte checks; processing is queued. */
	UPLOADED,
	PROCESSING,
	/** Processed: it can be attached and read. */
	READY,
	/** Processing failed for a permanent reason (unreadable or unsupported content). */
	FAILED,
	/** The content did not match what the client declared; the object was deleted. */
	QUARANTINED,
	/** Soft-deleted; objects are removed. */
	DELETED

}
