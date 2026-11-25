package com.nemaz.school.engagement.application;

import java.util.Map;
import java.util.UUID;

import com.nemaz.school.engagement.PointsApi;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link PointsApi} for the other modules. */
@Component
@Transactional
class DefaultPointsApi implements PointsApi {

	private final JdbcClient jdbc;

	private final AwardService awards;

	private final BadgeService badges;

	DefaultPointsApi(JdbcClient jdbc, AwardService awards, BadgeService badges) {
		this.jdbc = jdbc;
		this.awards = awards;
		this.badges = badges;
	}

	@Override
	@Transactional(readOnly = true)
	public int classTotal(UUID studentId, UUID classId) {
		return this.jdbc.sql("""
				select coalesce(sum(b.positive_total + b.needs_work_total), 0)
				from point_balances b join classes c on c.id = b.class_id and c.academic_year_id = b.academic_year_id
				where b.student_id = :st and b.class_id = :c and b.context = 'CLASS'""")
			.param("st", studentId)
			.param("c", classId)
			.query(Integer.class)
			.single();
	}

	@Override
	public UUID awardAsSystem(UUID schoolId, UUID studentId, UUID classId, String templateCode, UUID clientOpId) {
		return this.awards.awardAsSystem(schoolId, studentId, classId, templateCode, clientOpId);
	}

	@Override
	public boolean grantBadge(UUID schoolId, UUID studentId, String badgeCode, Map<String, Object> source) {
		return this.badges.findByCode(schoolId, badgeCode)
			.filter(BadgeService.Badge::active)
			.map((badge) -> this.badges.grant(schoolId, studentId, badge, null, source))
			.orElse(false);
	}

}
