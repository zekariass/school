package com.nemaz.school.people.application;

import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import jakarta.validation.constraints.NotNull;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counts for the school administrator's dashboard and setup checklist: cheap aggregate queries, no personal
 * data. Needs {@code school.read} (404 for callers who cannot see the school, 403 for members without it).
 */
@Service
@Transactional(readOnly = true)
public class SchoolSummaryService {

	private final JdbcClient jdbc;

	private final SchoolGate gate;

	SchoolSummaryService(JdbcClient jdbc, SchoolGate gate) {
		this.jdbc = jdbc;
		this.gate = gate;
	}

	public SchoolCounts summary(CurrentUser user, UUID schoolId) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_READ);
		return this.jdbc.sql("""
				select
				  (select count(*) from school_students where school_id = :s and status = 'ACTIVE') as students,
				  (select count(*) from school_staff where school_id = :s and status = 'ACTIVE' and staff_type = 'TEACHER') as teachers,
				  (select count(*) from school_staff where school_id = :s and status = 'ACTIVE') as staff,
				  (select count(*) from classes where school_id = :s and status = 'ACTIVE') as classes,
				  (select count(*) from academic_years where school_id = :s) as academic_years,
				  exists (select 1 from academic_years where school_id = :s and is_current) as has_current_year,
				  (select count(*) from grade_levels where school_id = :s and is_active) as grade_levels,
				  (select count(*) from subjects where school_id = :s and is_active) as subjects
				""")
			.param("s", schoolId)
			.query((rs, i) -> new SchoolCounts(rs.getLong("students"), rs.getLong("teachers"), rs.getLong("staff"),
					rs.getLong("classes"), rs.getLong("academic_years"), rs.getBoolean("has_current_year"),
					rs.getLong("grade_levels"), rs.getLong("subjects")))
			.single();
	}

	/** Counts of live rows only (active students, teachers and classes; active grade levels and subjects). */
	public record SchoolCounts(@NotNull long students, @NotNull long teachers, @NotNull long staff,
			@NotNull long classes, @NotNull long academicYears, @NotNull boolean hasCurrentYear,
			@NotNull long gradeLevels, @NotNull long subjects) {
	}

}
