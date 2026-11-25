/**
 * Classroom engagement (Implementation Plan M6, Database_Design §4.7, §4.8, R-16, decision D2, spec §19-§23, §126):
 * point categories, the append-only points ledger with its balance projection, badges, the teacher toolkit's activity
 * templates and the points reports.
 * <ul>
 * <li><b>Ledger:</b> {@code point_transactions} is append-only; an undo is a REVERSAL row. Awards are idempotent per
 * ({@code awarded_by}, {@code client_op_id}, student). Guardians award only HOME points, in HOME categories, through an
 * ACTIVE, not custody-restricted link with {@code can_award_points}; HOME points never reach class or school totals,
 * rankings, badges or reports.</li>
 * <li><b>Projection:</b> {@code point_balances} is rebuilt per (student, year, context, class) from the ledger by an
 * idempotent listener of {@link com.nemaz.school.engagement.PointTransactionRecorded} and verified nightly.</li>
 * <li><b>Badges:</b> rules (POINTS_TOTAL, CATEGORY_TOTAL; the ATTENDANCE_STREAK hook is
 * {@link com.nemaz.school.engagement.PointsApi#grantBadge}) run on those events, over CLASS and SCHOOL points only.</li>
 * <li><b>Access</b> uses {@code academic.ClassAccess} and {@code guardians.GuardianAccess}; other modules use
 * {@link com.nemaz.school.engagement.PointsApi} and the events.</li>
 * </ul>
 */
@ApplicationModule(displayName = "Engagement")
package com.nemaz.school.engagement;

import org.springframework.modulith.ApplicationModule;
