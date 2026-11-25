package com.nemaz.school.engagement;

import java.util.UUID;

/**
 * A row was appended to the points ledger (published in the recording transaction). Drives the balance projection, the
 * badge rules and the notifications; delivery is at-least-once, so listeners must be idempotent. Carries ids and
 * numbers only: never the note text, names or anything else a child wrote or was written about.
 *
 * @param classId null unless {@code context} is CLASS
 * @param points signed: negative for a NEEDS_WORK award and for the reversal of a positive one
 * @param awardedBy null for a system award
 */
public record PointTransactionRecorded(UUID transactionId, UUID schoolId, UUID studentId, PointContext context,
		UUID classId, UUID academicYearId, UUID categoryId, PointPolarity polarity, PointTransactionType type,
		int points, UUID awardedBy) {
}
