package com.nemaz.school.sync;

/** The result kinds of the sync endpoint (and the values of {@code sync_operations.status}). */
public enum SyncStatus {

	/** The operation was applied; {@code state} is the server's state afterwards. */
	APPLIED,
	/** The operation was seen before (same user, device and client operation id): the first outcome is returned. */
	DUPLICATE,
	/** The data changed under the client; nothing it carries overwrote the server's data. {@code state} says what is there now. */
	CONFLICT,
	/** The operation can never succeed as sent (not allowed, locked, unknown class, invalid): {@code problem} says why. Do not resend it. */
	REJECTED

}
