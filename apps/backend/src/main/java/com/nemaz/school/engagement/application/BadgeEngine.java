package com.nemaz.school.engagement.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.engagement.PointContext;
import com.nemaz.school.engagement.PointPolarity;
import com.nemaz.school.engagement.PointTransactionRecorded;
import com.nemaz.school.engagement.PointTransactionType;
import com.nemaz.school.engagement.application.BadgeService.Badge;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * The badge rule engine: after a positive CLASS or SCHOOL award it checks the school's active POINTS_TOTAL and
 * CATEGORY_TOTAL badges the student does not hold yet against the ledger (net points of CLASS and SCHOOL context; HOME
 * points never count, D2) and grants those whose threshold is reached. Idempotent: a grant is unique per student and
 * badge, so a redelivered event or two parallel awards grant once. ATTENDANCE_STREAK badges are granted by the
 * attendance module (M7) through {@code PointsApi#grantBadge}; MANUAL ones by teachers.
 */
@Component
class BadgeEngine {

	private final BadgeService badges;

	private final EngagementSettings settings;

	private final JdbcClient jdbc;

	BadgeEngine(BadgeService badges, EngagementSettings settings, JdbcClient jdbc) {
		this.badges = badges;
		this.settings = settings;
		this.jdbc = jdbc;
	}

	@ApplicationModuleListener
	void on(PointTransactionRecorded event) {
		if (event.type() != PointTransactionType.AWARD || event.polarity() != PointPolarity.POSITIVE
				|| event.context() == PointContext.HOME || !this.settings.badges(event.schoolId())) {
			return;
		}
		for (Badge badge : this.badges.candidates(event.schoolId(), event.studentId())) {
			int threshold = ((Number) badge.criteria().getOrDefault("threshold", Integer.MAX_VALUE)).intValue();
			long total = total(event.schoolId(), event.studentId(), badge);
			if (total >= threshold) {
				Map<String, Object> source = new LinkedHashMap<>();
				source.put("transactionId", event.transactionId().toString());
				source.put("total", total);
				this.badges.grant(event.schoolId(), event.studentId(), badge, null, source);
			}
		}
	}

	private long total(UUID schoolId, UUID studentId, Badge badge) {
		if ("CATEGORY_TOTAL".equals(badge.criteriaType())) {
			Object categoryId = badge.criteria().get("categoryId");
			Object templateCode = badge.criteria().get("templateCode");
			return this.jdbc.sql("""
					select coalesce(sum(t.points), 0) from point_transactions t
					where t.school_id = :s and t.student_id = :st and t.context in ('CLASS', 'SCHOOL')
					  and t.category_id in (select c.id from point_categories c where c.school_id = :s
					                         and (c.id = cast(:cid as uuid) or c.template_code = :tc))""")
				.param("s", schoolId)
				.param("st", studentId)
				.param("cid", (categoryId != null) ? UUID.fromString(categoryId.toString()) : null)
				.param("tc", (templateCode != null) ? templateCode.toString() : null)
				.query(Long.class)
				.single();
		}
		Object context = badge.criteria().get("context");
		return this.jdbc.sql("""
				select coalesce(sum(points), 0) from point_transactions
				where school_id = :s and student_id = :st and context in ('CLASS', 'SCHOOL')
				  and (cast(:ctx as text) is null or context = :ctx)""")
			.param("s", schoolId)
			.param("st", studentId)
			.param("ctx", (context != null) ? context.toString() : null)
			.query(Long.class)
			.single();
	}

}
