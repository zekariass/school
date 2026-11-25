package com.nemaz.school.engagement.api;

import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.engagement.application.PointViews;
import com.nemaz.school.engagement.application.PointViews.ClassPoints;
import com.nemaz.school.engagement.application.PointViews.Leaderboard;
import com.nemaz.school.engagement.application.PointViews.PointSection;
import com.nemaz.school.engagement.application.PointViews.StudentPoints;
import com.nemaz.school.engagement.application.PointViews.TimelineItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** What points look like to each reader; see {@link PointViews} for who sees what. */
@RestController
@Tag(name = "points")
public class PointViewController {

	private final PointViews views;

	PointViewController(PointViews views) {
		this.views = views;
	}

	@GetMapping("/api/v1/schools/{schoolId}/classes/{classId}/points")
	@Operation(operationId = "getClassPoints",
			summary = "The class overview: every ACTIVE student with their class total (from the balance projection), plus the class settings the award screen needs. ACTIVE teachers of the class and class managers only (403 for students and guardians who see the class, 404 otherwise)")
	public ClassPoints classPoints(@PathVariable UUID schoolId, @PathVariable UUID classId) {
		return this.views.classPoints(CurrentUser.require(), schoolId, classId);
	}

	@GetMapping("/api/v1/schools/{schoolId}/classes/{classId}/leaderboard")
	@Operation(operationId = "getClassLeaderboard",
			summary = "Top positive class totals (at most 10, ties share a rank). 403 points.leaderboard_disabled unless the school turned engagement.leaderboard.enabled on (default off); members of the class roster only, never guardians")
	public Leaderboard leaderboard(@PathVariable UUID schoolId, @PathVariable UUID classId) {
		return this.views.leaderboard(CurrentUser.require(), schoolId, classId);
	}

	@GetMapping("/api/v1/schools/{schoolId}/students/{studentId}/points")
	@Operation(operationId = "getStudentPoints",
			summary = "A student's totals by category and the badges earned: the school section (CLASS and SCHOOL points) and, for the student and their ACTIVE guardians only, a separate home section that is never added into class or school totals. Callers: the student, their guardians with can_view_academics, teachers (the classes they teach), school.points.read. academicYearId defaults to the school's current year")
	public StudentPoints studentPoints(@PathVariable UUID schoolId, @PathVariable UUID studentId,
			@RequestParam(required = false) UUID academicYearId) {
		return this.views.studentPoints(CurrentUser.require(), schoolId, studentId, academicYearId);
	}

	@GetMapping("/api/v1/schools/{schoolId}/students/{studentId}/points/transactions")
	@Operation(operationId = "listStudentPointTransactions",
			summary = "The points timeline of one section (SCHOOL or HOME), newest first, keyset paged; HOME only for the student and their guardians (403 otherwise). Reversals are rows of their own and awards that were undone say so")
	public CursorPage<TimelineItem> timeline(@PathVariable UUID schoolId, @PathVariable UUID studentId,
			@RequestParam(defaultValue = "SCHOOL") PointSection section, @RequestParam(required = false) UUID academicYearId,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.views.timeline(CurrentUser.require(), schoolId, studentId, section, academicYearId, cursor, limit);
	}

}
