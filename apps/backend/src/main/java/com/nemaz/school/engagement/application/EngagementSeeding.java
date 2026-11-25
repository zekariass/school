package com.nemaz.school.engagement.application;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives a school its own, editable copy of the platform templates (default point categories and badges) the first
 * time the school uses points. Idempotent and safe under concurrency (an advisory lock per school and
 * {@code on conflict do nothing}); a template added to the platform later does not reach schools that were seeded.
 */
@Component
class EngagementSeeding {

	private final JdbcClient jdbc;

	EngagementSeeding(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Joins the caller's transaction (which must be writable). */
	@Transactional
	void ensure(UUID schoolId) {
		boolean categories = exists("select exists (select 1 from point_categories where school_id = :s and class_id is null)", schoolId);
		boolean badges = exists("select exists (select 1 from badges where school_id = :s)", schoolId);
		if (categories && badges) {
			return;
		}
		this.jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", "engagement-seed:" + schoolId)
			.query().listOfRows();
		if (!exists("select exists (select 1 from point_categories where school_id = :s and class_id is null)", schoolId)) {
			this.jdbc.sql("""
					insert into point_categories (school_id, template_code, name_i18n, icon, color, default_points, polarity, context,
					                              sort_order)
					select :s, template_code, name_i18n, icon, color, default_points, polarity, context, sort_order
					from point_categories where school_id is null and template_code is not null
					on conflict do nothing""").param("s", schoolId).update();
		}
		if (!exists("select exists (select 1 from badges where school_id = :s)", schoolId)) {
			this.jdbc.sql("""
					insert into badges (school_id, code, name_i18n, description_i18n, icon, criteria_type, criteria, sort_order)
					select :s, code, name_i18n, description_i18n, icon, criteria_type, criteria, sort_order
					from badges where school_id is null
					on conflict do nothing""").param("s", schoolId).update();
		}
	}

	private boolean exists(String sql, UUID schoolId) {
		return this.jdbc.sql(sql).param("s", schoolId).query(Boolean.class).single();
	}

}
