package com.nemaz.school.moderation;

import java.util.UUID;

/**
 * How the existing report hooks reach moderation (docs/Safeguarding_Rules.md S1.6): the owning module's idempotent event
 * listener calls {@link #ingest}. Redelivery changes nothing (the {@code sourceKey} is unique), a duplicate of an open report
 * of the same reporter is ignored, and the case and the single acknowledgement are created exactly once.
 */
public interface ReportIntake {

	/**
	 * @param sourceKey a stable key of the original report, e.g. {@code message_report:<id>}; the dedupe key
	 * @param details always null from the existing hooks (they never carry text)
	 */
	void ingest(String sourceKey, String targetType, UUID targetId, UUID reporterId, String reasonCode, String details);

}
