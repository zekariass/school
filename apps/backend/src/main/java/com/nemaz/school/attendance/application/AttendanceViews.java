package com.nemaz.school.attendance.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.academic.SqlFragment;
import com.nemaz.school.attendance.AttendanceStatus;
import com.nemaz.school.attendance.application.AttendanceAccess.Viewer;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads of attendance that are not a roll: a student's history and the daily dashboard of the school.
 * <ul>
 * <li><b>History:</b> the student, their ACTIVE unrestricted guardians with {@code can_view_academics} (both see status,
 * lateness, date and class, never the teacher's note), the teachers of the student's classes (only the classes they
 * teach) and holders of {@code school.attendance.read} (everything, notes included). Filtered in SQL.</li>
 * <li><b>Dashboard:</b> per class on a date and slot: how many students, whether the roll was taken, the counts per
 * status and the unmarked rest; plus the school totals and the classes that have not taken attendance. Attendance readers
 * see every class of the school, teachers the classes they teach; students and guardians get 403.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class AttendanceViews {

	private final AttendanceAccess access;

	private final AttendanceSettings settings;

	private final SchoolTime time;

	private final JdbcClient jdbc;

	AttendanceViews(AttendanceAccess access, AttendanceSettings settings, SchoolTime time, JdbcClient jdbc) {
		this.access = access;
		this.settings = settings;
		this.time = time;
		this.jdbc = jdbc;
	}

	public CursorPage<AttendanceHistoryItem> history(CurrentUser user, UUID schoolId, UUID studentId, LocalDate from,
			LocalDate to, UUID classId, String cursor, Integer limit) {
		Viewer viewer = this.access.historyViewer(user, schoolId, studentId);
		if (from != null && to != null && to.isBefore(from)) {
			throw ApiException.badRequest("attendance.invalid_range");
		}
		boolean allClasses = viewer == Viewer.FAMILY || this.access.reader(user, schoolId);
		SqlFragment teaching = allClasses ? SqlFragment.always() : this.access.classes().teachableClasses(user, "c");
		int pageSize = Cursors.clampLimit(limit);
		LocalDate afterDate = null;
		UUID afterId = null;
		if (cursor != null) {
			List<String> parts = Cursors.decode(cursor, 2);
			try {
				afterDate = LocalDate.parse(parts.get(0));
				afterId = UUID.fromString(parts.get(1));
			}
			catch (RuntimeException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		boolean notes = viewer == Viewer.STAFF;
		List<AttendanceHistoryItem> rows = this.jdbc.sql("""
				select r.id, r.attendance_date, s.slot, r.class_id, c.name as class_name, r.status, r.minutes_late, r.note,
				       r.recorded_at
				from attendance_records r
				join attendance_sessions s on s.id = r.attendance_session_id
				join classes c on c.id = r.class_id
				where r.school_id = :as_school and r.student_id = :as_student
				  and (cast(:as_from as date) is null or r.attendance_date >= :as_from)
				  and (cast(:as_to as date) is null or r.attendance_date <= :as_to)
				  and (cast(:as_class as uuid) is null or r.class_id = :as_class)
				  and (cast(:as_after_date as date) is null or (r.attendance_date, r.id) < (cast(:as_after_date as date), cast(:as_after_id as uuid)))
				  and (""" + teaching.sql() + """
				)
				order by r.attendance_date desc, r.id desc limit :as_n""")
			.params(teaching.params())
			.param("as_school", schoolId)
			.param("as_student", studentId)
			.param("as_from", (from != null) ? java.sql.Date.valueOf(from) : null)
			.param("as_to", (to != null) ? java.sql.Date.valueOf(to) : null)
			.param("as_class", classId)
			.param("as_after_date", (afterDate != null) ? java.sql.Date.valueOf(afterDate) : null)
			.param("as_after_id", afterId)
			.param("as_n", pageSize + 1)
			.query((rs, i) -> new AttendanceHistoryItem(Rows.uuid(rs, "id"), Rows.date(rs, "attendance_date"),
					rs.getString("slot"), Rows.uuid(rs, "class_id"), rs.getString("class_name"),
					AttendanceStatus.valueOf(rs.getString("status")), Rows.integer(rs, "minutes_late"),
					notes ? rs.getString("note") : null, Rows.instant(rs, "recorded_at")))
			.list();
		return CursorPage.fromOverfetch(rows, pageSize, (r) -> Cursors.encode(r.date().toString(), r.recordId().toString()));
	}

	public AttendanceDashboard dashboard(CurrentUser user, UUID schoolId, LocalDate date, String slot, String cursor,
			Integer limit) {
		this.access.gate().requireVisible(user, schoolId);
		this.access.requireFeature(schoolId);
		boolean reader = this.access.reader(user, schoolId);
		if (!reader && !this.access.activeTeacher(user, schoolId)) {
			throw ApiException.forbidden("attendance.access_denied");
		}
		List<String> slots = this.settings.slots(schoolId);
		String effectiveSlot = (slot == null || slot.isBlank()) ? slots.getFirst() : slot;
		if (!slots.contains(effectiveSlot)) {
			throw ApiException.badRequest("attendance.slot_not_enabled");
		}
		ZoneId zone = this.time.zoneOfSchool(schoolId);
		LocalDate day = (date != null) ? date : this.time.today(zone);
		Instant start = this.time.startOf(day, zone);
		Instant end = this.time.endOf(day, zone);
		SqlFragment scope = reader ? SqlFragment.always() : this.access.classes().teachableClasses(user, "c");
		int pageSize = Cursors.clampLimit(limit);
		String afterName = null;
		UUID afterId = null;
		if (cursor != null) {
			List<String> parts = Cursors.decode(cursor, 2);
			afterName = parts.get(0);
			try {
				afterId = UUID.fromString(parts.get(1));
			}
			catch (IllegalArgumentException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		String inner = """
				select c.id as class_id, c.name as class_name, lower(c.name) as sort_name,
				       (select count(distinct cs.student_id) from class_students cs
				        where cs.class_id = c.id and cs.status in ('ACTIVE', 'FORMER', 'REMOVED')
				          and cs.joined_at < :as_end and (cs.left_at is null or cs.left_at >= :as_start)) as students,
				       s.id as session_id, s.taken_by, s.taken_at,
				       count(r.id) as marked,
				       count(r.id) filter (where r.status = 'PRESENT') as present,
				       count(r.id) filter (where r.status = 'ABSENT') as absent,
				       count(r.id) filter (where r.status = 'LATE') as late,
				       count(r.id) filter (where r.status = 'EXCUSED') as excused,
				       count(r.id) filter (where r.status = 'LEFT_EARLY') as left_early
				from classes c
				left join attendance_sessions s on s.class_id = c.id and s.attendance_date = :as_date and s.slot = :as_slot
				left join attendance_records r on r.attendance_session_id = s.id
				where c.school_id = :as_school and c.status <> 'ARCHIVED' and (""" + scope.sql() + """
				)
				group by c.id, c.name, s.id""";
		List<SortedClass> rows = this.jdbc.sql("select * from (" + inner + """
				) x where (cast(:as_after_name as text) is null or (x.sort_name, x.class_id) > (cast(:as_after_name as text), cast(:as_after_id as uuid)))
				order by x.sort_name, x.class_id limit :as_n""")
			.params(scope.params())
			.param("as_start", java.sql.Timestamp.from(start))
			.param("as_end", java.sql.Timestamp.from(end))
			.param("as_date", java.sql.Date.valueOf(day))
			.param("as_slot", effectiveSlot)
			.param("as_school", schoolId)
			.param("as_after_name", afterName)
			.param("as_after_id", afterId)
			.param("as_n", pageSize + 1)
			.query((rs, i) -> {
				int students = rs.getInt("students");
				int marked = rs.getInt("marked");
				return new SortedClass(new DashboardClass(Rows.uuid(rs, "class_id"), rs.getString("class_name"), students,
						Rows.uuid(rs, "session_id") != null, Rows.uuid(rs, "taken_by"), Rows.instant(rs, "taken_at"), marked,
						Math.max(0, students - marked), rs.getInt("present"), rs.getInt("absent"), rs.getInt("late"),
						rs.getInt("excused"), rs.getInt("left_early")), rs.getString("sort_name"));
			})
			.list();
		DashboardTotals totals = this.jdbc.sql("""
				select count(*) filter (where students > 0) as classes,
				       count(*) filter (where students > 0 and session_id is not null) as taken,
				       count(*) filter (where students > 0 and session_id is null) as not_taken,
				       coalesce(sum(students), 0) as students, coalesce(sum(marked), 0) as marked,
				       coalesce(sum(present), 0) as present, coalesce(sum(absent), 0) as absent, coalesce(sum(late), 0) as late,
				       coalesce(sum(excused), 0) as excused, coalesce(sum(left_early), 0) as left_early
				from (""" + inner + ") x")
			.params(scope.params())
			.param("as_start", java.sql.Timestamp.from(start))
			.param("as_end", java.sql.Timestamp.from(end))
			.param("as_date", java.sql.Date.valueOf(day))
			.param("as_slot", effectiveSlot)
			.param("as_school", schoolId)
			.query((rs, i) -> {
				int students = rs.getInt("students");
				int marked = rs.getInt("marked");
				return new DashboardTotals(rs.getInt("classes"), rs.getInt("taken"), rs.getInt("not_taken"), students, marked,
						Math.max(0, students - marked), rs.getInt("present"), rs.getInt("absent"), rs.getInt("late"),
						rs.getInt("excused"), rs.getInt("left_early"));
			})
			.single();
		CursorPage<DashboardClass> page = CursorPage
			.fromOverfetch(rows, pageSize, (c) -> Cursors.encode(c.sortName(), c.view().classId().toString()))
			.map(SortedClass::view);
		return new AttendanceDashboard(day, effectiveSlot, slots, zone.getId(), totals, page);
	}

	// --- shapes -----------------------------------------------------------------------------

	public record AttendanceHistoryItem(@NotNull UUID recordId, @NotNull LocalDate date, @NotNull String slot,
			@NotNull UUID classId, @NotNull String className, @NotNull AttendanceStatus status, Integer minutesLate,
			@Schema(description = "The teacher's note: staff only") String note, @NotNull Instant recordedAt) {
	}

	public record AttendanceDashboard(@NotNull LocalDate date, @NotNull String slot,
			@Schema(description = "The slots the school enabled (attendance.slots), so clients can offer a switcher") @NotNull List<String> slots,
			@NotNull String timeZone,
			@Schema(description = "Over every class the caller sees, not just this page") @NotNull DashboardTotals totals,
			@NotNull CursorPage<DashboardClass> classes) {
	}

	public record DashboardTotals(@Schema(description = "Classes that have students on the date") int classes,
			int classesTaken, @Schema(description = "Classes with students whose roll was not taken yet") int classesNotTaken,
			int students, int marked, int unmarked, int present, int absent, int late, int excused, int leftEarly) {
	}

	public record DashboardClass(@NotNull UUID classId, @NotNull String className, int students, boolean taken, UUID takenBy,
			Instant takenAt, int marked, int unmarked, int present, int absent, int late, int excused, int leftEarly) {
	}

	private record SortedClass(DashboardClass view, String sortName) {
	}

}
