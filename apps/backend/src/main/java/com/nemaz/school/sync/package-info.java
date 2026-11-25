/**
 * Offline sync (Implementation Plan M7, Database_Design §4.6, R-27, spec §72 and §104): one generic endpoint,
 * {@code POST /api/v1/sync}, that applies a batch of operations queued by a device and answers one result per operation.
 * Modules plug their operation types in by implementing {@link com.nemaz.school.sync.SyncHandler} (attendance:
 * ATTENDANCE_MARK, engagement: POINT_AWARD); this module knows none of them.
 * <ul>
 * <li><b>Idempotency:</b> an operation is identified by (user, device id, client operation id). The first outcome is
 * stored in {@code sync_operations}; every replay answers DUPLICATE with that outcome, also for parallel replays (an
 * advisory lock per operation).</li>
 * <li><b>Isolation:</b> every operation runs in its own transaction, in order; a rejected or conflicting operation
 * never blocks the next one and never fails the batch.</li>
 * <li><b>Authorization:</b> none of its own: the handler authorizes exactly as the online endpoint would, at the time
 * the operation is applied (a queue can be days old).</li>
 * <li><b>Children's data:</b> payloads and result states are purged after the retention period.</li>
 * </ul>
 */
@ApplicationModule(displayName = "Offline sync")
package com.nemaz.school.sync;

import org.springframework.modulith.ApplicationModule;
