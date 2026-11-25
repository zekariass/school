package com.nemaz.school.engagement;

import java.util.Map;
import java.util.UUID;

/**
 * What other modules may ask of the engagement module. Joins the caller's transaction; no permission checks (the
 * caller made them).
 */
public interface PointsApi {

	/**
	 * Net points (positive plus negative) the student earned in the class during its academic year, from the balance
	 * projection (HOME points never count). Zero when none.
	 */
	int classTotal(UUID studentId, UUID classId);

	/**
	 * Appends a system award ({@code awarder_role = SYSTEM}) in the category of the school's own copy of the platform
	 * template {@code templateCode}, for example "Excellent attendance" (M7). {@code classId} null awards in the SCHOOL
	 * context. Idempotent per {@code clientOpId}: the same id returns the first award.
	 *
	 * @return the transaction id
	 * @throws com.nemaz.school.common.error.ApiException when the category is unknown or inactive, the student is not an
	 * ACTIVE student of the school or the school has no current academic year
	 */
	UUID awardAsSystem(UUID schoolId, UUID studentId, UUID classId, String templateCode, UUID clientOpId);

	/**
	 * Grants the school's badge {@code badgeCode} to the student unless they hold it already (the hook of rules that
	 * live elsewhere, such as ATTENDANCE_STREAK from the attendance module in M7). Sends the notifications.
	 *
	 * @param source what triggered it (ids and dates only), stored with the grant
	 * @return true when the badge was newly granted
	 */
	boolean grantBadge(UUID schoolId, UUID studentId, String badgeCode, Map<String, Object> source);

}
