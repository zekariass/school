package com.nemaz.school.sync;

import java.util.Set;

import com.nemaz.school.common.security.CurrentUser;

/**
 * Applies one kind of queued operation. Implemented by the module that owns the data and registered as a bean; the sync
 * endpoint finds it by {@link #operationType()}.
 * <p>
 * Contract: {@link #apply} runs in the transaction of that single operation, as the signed-in user, and must authorize
 * exactly like the online endpoint (the sync endpoint grants nothing extra). Validate before writing anything: throw an
 * {@link com.nemaz.school.common.error.ApiException} (the operation is then REJECTED with that code, and the whole
 * transaction is rolled back, events included). Return {@link SyncOutcome#conflict} when the data changed under the
 * client and nothing was overwritten. Be idempotent against the online path: when the same client operation id was
 * already applied there, return {@link SyncOutcome#duplicate}.
 */
public interface SyncHandler {

	/** The {@code operationType} this handler serves, e.g. "ATTENDANCE_MARK". */
	String operationType();

	/** The {@code entityType} values it accepts, e.g. "ATTENDANCE_SESSION". */
	Set<String> entityTypes();

	SyncOutcome apply(SyncOperation operation, CurrentUser user);

}
