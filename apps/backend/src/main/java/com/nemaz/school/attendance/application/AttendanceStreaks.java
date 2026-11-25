package com.nemaz.school.attendance.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.attendance.AttendanceMarked;
import com.nemaz.school.attendance.AttendanceStatus;
import com.nemaz.school.engagement.PointsApi;
import com.nemaz.school.engagement.PointsApi.StreakBadge;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * The ATTENDANCE_STREAK hook of the badge engine (M6): after a PRESENT mark it asks the engagement module which streak
 * badges the student can still earn (the number of days is the badge's own criterion) and grants those whose streak is
 * reached. A school day is perfect when every mark of the student on that date is PRESENT (LATE, EXCUSED, ABSENT and
 * LEFT_EARLY break the streak); the streak is the number of consecutive perfect days, among the dates on which the
 * student has any mark, counted back from their latest one. Dates nobody took a roll on (weekends, holidays) are not
 * days. Idempotent: a grant is unique per student and badge, so a redelivered event does nothing.
 */
@Component
class AttendanceStreaks {

	private final PointsApi points;

	private final JdbcClient jdbc;

	AttendanceStreaks(PointsApi points, JdbcClient jdbc) {
		this.points = points;
		this.jdbc = jdbc;
	}

	@ApplicationModuleListener
	void on(AttendanceMarked event) {
		if (event.status() != AttendanceStatus.PRESENT) {
			return;
		}
		List<StreakBadge> badges = this.points.attendanceStreakBadges(event.schoolId(), event.studentId());
		if (badges.isEmpty()) {
			return;
		}
		int longest = badges.stream().mapToInt(StreakBadge::days).max().orElse(0);
		int streak = streak(event.schoolId(), event.studentId(), longest);
		for (StreakBadge badge : badges) {
			if (streak >= badge.days()) {
				Map<String, Object> source = new LinkedHashMap<>();
				source.put("streakDays", badge.days());
				source.put("through", event.attendanceDate().toString());
				this.points.grantBadge(event.schoolId(), event.studentId(), badge.code(), source);
			}
		}
	}

	/**
	 * The student's current streak: the consecutive perfect days ending at their latest day with marks (so a roll filled in
	 * late for an earlier day counts too), looking back at most {@code limit} days.
	 */
	int streak(UUID schoolId, UUID studentId, int limit) {
		List<Boolean> days = this.jdbc.sql("""
				select bool_and(status = 'PRESENT') from attendance_records
				where school_id = :s and student_id = :st
				group by attendance_date order by attendance_date desc limit :n""")
			.param("s", schoolId)
			.param("st", studentId)
			.param("n", limit)
			.query(Boolean.class)
			.list();
		int streak = 0;
		for (Boolean perfect : days) {
			if (!Boolean.TRUE.equals(perfect)) {
				break;
			}
			streak++;
		}
		return streak;
	}

}
