package com.nemaz.school.attendance.application;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.text.CsvCells;
import com.nemaz.school.system.RateLimiter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attendance summary reports: marks counted per student and class over a range of school-local dates, for the teachers
 * of a class (their class) and for holders of {@code school.attendance.read} (the school, a class or a student). The CSV
 * export is audited and rate limited, UTF-8 with a byte order mark, CRLF line ends, headers in the caller's language,
 * and every text cell that a spreadsheet could read as a formula is neutralized. The teacher's notes and the absence
 * reports are not part of it.
 */
@Service
@Transactional(readOnly = true)
public class AttendanceReports {

	static final int MAX_EXPORT_ROWS = 50_000;

	static final int MAX_RANGE_DAYS = 400;

	private final AttendanceAccess access;

	private final SchoolTime time;

	private final JdbcClient jdbc;

	private final RateLimiter limiter;

	private final AuditLog audit;

	private final MessageSource messages;

	AttendanceReports(AttendanceAccess access, SchoolTime time, JdbcClient jdbc, RateLimiter limiter, AuditLog audit,
			MessageSource messages) {
		this.access = access;
		this.time = time;
		this.jdbc = jdbc;
		this.limiter = limiter;
		this.audit = audit;
		this.messages = messages;
	}

	public CursorPage<AttendanceReportRow> report(CurrentUser user, UUID schoolId, UUID classId, UUID studentId,
			LocalDate from, LocalDate to, String cursor, Integer limit) {
		authorize(user, schoolId, classId);
		Range range = range(schoolId, from, to);
		int pageSize = Cursors.clampLimit(limit);
		String afterName = null;
		UUID afterStudent = null;
		UUID afterClass = null;
		if (cursor != null) {
			List<String> parts = Cursors.decode(cursor, 3);
			afterName = parts.get(0);
			try {
				afterStudent = UUID.fromString(parts.get(1));
				afterClass = UUID.fromString(parts.get(2));
			}
			catch (IllegalArgumentException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		List<Sorted> rows = this.jdbc.sql("select * from (" + REPORT_SELECT + """
				) x where (cast(:as_an as text) is null
				           or (x.sort_name, x.student_id, x.class_id) > (cast(:as_an as text), cast(:as_as as uuid), cast(:as_ac as uuid)))
				order by x.sort_name, x.student_id, x.class_id limit :as_n""")
			.param("as_school", schoolId)
			.param("as_class", classId)
			.param("as_student", studentId)
			.param("as_from", java.sql.Date.valueOf(range.from()))
			.param("as_to", java.sql.Date.valueOf(range.to()))
			.param("as_an", afterName)
			.param("as_as", afterStudent)
			.param("as_ac", afterClass)
			.param("as_n", pageSize + 1)
			.query(AttendanceReports::row)
			.list();
		return CursorPage
			.fromOverfetch(rows, pageSize,
					(r) -> Cursors.encode(r.sortName(), r.row().studentId().toString(), r.row().classId().toString()))
			.map(Sorted::row);
	}

	/** The whole report as a CSV file (at most {@value #MAX_EXPORT_ROWS} rows: narrow the range or the class beyond that). */
	@Transactional
	public ExportFile export(CurrentUser user, UUID schoolId, UUID classId, UUID studentId, LocalDate from, LocalDate to) {
		authorize(user, schoolId, classId);
		this.limiter.consume("attendance-export", user.userId().toString(), 10, Duration.ofMinutes(10),
				"attendance.export_rate_limited");
		Range range = range(schoolId, from, to);
		Locale locale = LocaleContextHolder.getLocale();
		List<Sorted> rows = this.jdbc.sql("select * from (" + REPORT_SELECT + ") x order by x.sort_name, x.student_id, x.class_id limit :as_n")
			.param("as_school", schoolId)
			.param("as_class", classId)
			.param("as_student", studentId)
			.param("as_from", java.sql.Date.valueOf(range.from()))
			.param("as_to", java.sql.Date.valueOf(range.to()))
			.param("as_n", MAX_EXPORT_ROWS + 1)
			.query(AttendanceReports::row)
			.list();
		if (rows.size() > MAX_EXPORT_ROWS) {
			throw ApiException.unprocessable("attendance.export_too_large");
		}
		this.audit.record(AuditEntry.of("attendance.report.export", AuditOutcome.SUCCESS)
			.target("school", schoolId)
			.school(schoolId)
			.metadata("classId", (classId != null) ? classId.toString() : null)
			.metadata("studentId", (studentId != null) ? studentId.toString() : null)
			.metadata("from", range.from().toString())
			.metadata("to", range.to().toString())
			.metadata("rows", rows.size()));
		return new ExportFile(csv(rows, locale), "attendance-report.csv");
	}


	// --- per-period summary -------------------------------------------------------------------

	/**
	 * Marks counted per class, slot and subject over a range of school-local dates (the per-period summary): who may ask
	 * is the same as for {@link #report}. Rolls taken without a subject (DAY, AM, PM and periods without one) have no
	 * subject.
	 */
	public CursorPage<PeriodReportRow> periodReport(CurrentUser user, UUID schoolId, UUID classId, String slot, UUID subjectId,
			LocalDate from, LocalDate to, String cursor, Integer limit) {
		authorize(user, schoolId, classId);
		checkSlot(slot);
		Range range = range(schoolId, from, to);
		int pageSize = Cursors.clampLimit(limit);
		String afterName = null;
		UUID afterClass = null;
		String afterSlot = null;
		UUID afterSubject = null;
		if (cursor != null) {
			List<String> parts = Cursors.decode(cursor, 4);
			afterName = parts.get(0);
			afterSlot = parts.get(2);
			try {
				afterClass = UUID.fromString(parts.get(1));
				afterSubject = UUID.fromString(parts.get(3));
			}
			catch (IllegalArgumentException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		List<SortedPeriod> rows = this.jdbc.sql("select * from (" + PERIOD_SELECT + """
				) x where (cast(:as_an as text) is null
				           or (x.sort_name, x.class_id, x.slot, x.subject_key)
				              > (cast(:as_an as text), cast(:as_ac as uuid), cast(:as_asl as text), cast(:as_asub as uuid)))
				order by x.sort_name, x.class_id, x.slot, x.subject_key limit :as_n""")
			.param("as_school", schoolId)
			.param("as_class", classId)
			.param("as_slot", slot)
			.param("as_subject", subjectId)
			.param("as_from", java.sql.Date.valueOf(range.from()))
			.param("as_to", java.sql.Date.valueOf(range.to()))
			.param("as_an", afterName)
			.param("as_ac", afterClass)
			.param("as_asl", afterSlot)
			.param("as_asub", afterSubject)
			.param("as_n", pageSize + 1)
			.query(AttendanceReports::periodRow)
			.list();
		return CursorPage
			.fromOverfetch(rows, pageSize,
					(r) -> Cursors.encode(r.sortName(), r.row().classId().toString(), r.row().slot(), r.subjectKey().toString()))
			.map(SortedPeriod::row);
	}

	/** The per-period summary as a CSV file: same rules and limits as {@link #export}, audited as {@code attendance.period_report.export}. */
	@Transactional
	public ExportFile exportPeriodReport(CurrentUser user, UUID schoolId, UUID classId, String slot, UUID subjectId,
			LocalDate from, LocalDate to) {
		authorize(user, schoolId, classId);
		this.limiter.consume("attendance-export", user.userId().toString(), 10, Duration.ofMinutes(10),
				"attendance.export_rate_limited");
		checkSlot(slot);
		Range range = range(schoolId, from, to);
		Locale locale = LocaleContextHolder.getLocale();
		List<SortedPeriod> rows = this.jdbc.sql("select * from (" + PERIOD_SELECT
				+ ") x order by x.sort_name, x.class_id, x.slot, x.subject_key limit :as_n")
			.param("as_school", schoolId)
			.param("as_class", classId)
			.param("as_slot", slot)
			.param("as_subject", subjectId)
			.param("as_from", java.sql.Date.valueOf(range.from()))
			.param("as_to", java.sql.Date.valueOf(range.to()))
			.param("as_n", MAX_EXPORT_ROWS + 1)
			.query(AttendanceReports::periodRow)
			.list();
		if (rows.size() > MAX_EXPORT_ROWS) {
			throw ApiException.unprocessable("attendance.export_too_large");
		}
		this.audit.record(AuditEntry.of("attendance.period_report.export", AuditOutcome.SUCCESS)
			.target("school", schoolId)
			.school(schoolId)
			.metadata("classId", (classId != null) ? classId.toString() : null)
			.metadata("slot", slot)
			.metadata("subjectId", (subjectId != null) ? subjectId.toString() : null)
			.metadata("from", range.from().toString())
			.metadata("to", range.to().toString())
			.metadata("rows", rows.size()));
		return new ExportFile(periodCsv(rows, locale), "attendance-period-report.csv");
	}

	private static void checkSlot(String slot) {
		if (slot != null && !slot.matches("DAY|AM|PM|P[1-8]")) {
			throw ApiException.badRequest("attendance.invalid_slot");
		}
	}

	private static final String PERIOD_SELECT = """
			select c.id as class_id, c.name as class_name, lower(c.name) as sort_name, s.slot, s.subject_id,
			       sub.name as subject_name,
			       coalesce(s.subject_id, cast('00000000-0000-0000-0000-000000000000' as uuid)) as subject_key,
			       count(distinct s.id) as rolls,
			       count(distinct r.student_id) as students,
			       count(r.id) as marked,
			       count(r.id) filter (where r.status = 'PRESENT') as present,
			       count(r.id) filter (where r.status = 'ABSENT') as absent,
			       count(r.id) filter (where r.status = 'LATE') as late,
			       count(r.id) filter (where r.status = 'EXCUSED') as excused,
			       count(r.id) filter (where r.status = 'LEFT_EARLY') as left_early,
			       coalesce(sum(r.minutes_late), 0) as minutes_late
			from attendance_sessions s
			join classes c on c.id = s.class_id
			left join subjects sub on sub.id = s.subject_id
			left join attendance_records r on r.attendance_session_id = s.id
			where s.school_id = :as_school and s.attendance_date between :as_from and :as_to
			  and (cast(:as_class as uuid) is null or s.class_id = :as_class)
			  and (cast(:as_slot as text) is null or s.slot = :as_slot)
			  and (cast(:as_subject as uuid) is null or s.subject_id = :as_subject)
			group by c.id, c.name, s.slot, s.subject_id, sub.name""";

	private static SortedPeriod periodRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		return new SortedPeriod(new PeriodReportRow(Rows.uuid(rs, "class_id"), rs.getString("class_name"), rs.getString("slot"),
				Rows.uuid(rs, "subject_id"), rs.getString("subject_name"), rs.getInt("rolls"), rs.getInt("students"),
				rs.getInt("marked"), rs.getInt("present"), rs.getInt("absent"), rs.getInt("late"), rs.getInt("excused"),
				rs.getInt("left_early"), rs.getLong("minutes_late")), rs.getString("sort_name"), Rows.uuid(rs, "subject_key"));
	}

	private record SortedPeriod(PeriodReportRow row, String sortName, UUID subjectKey) {
	}

	private byte[] periodCsv(List<SortedPeriod> rows, Locale locale) {
		StringWriter out = new StringWriter();
		out.write('\ufeff');
		try (CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
			printer.printRecord(text("class", locale), text("slot", locale), text("subject", locale), text("rolls", locale),
					text("students", locale), text("marked", locale), text("present", locale), text("absent", locale),
					text("late", locale), text("excused", locale), text("left_early", locale), text("minutes_late", locale));
			for (SortedPeriod s : rows) {
				PeriodReportRow r = s.row();
				printer.printRecord(CsvCells.safe(r.className()), r.slot(), CsvCells.safe(r.subjectName()), r.rolls(),
						r.students(), r.marked(), r.present(), r.absent(), r.late(), r.excused(), r.leftEarly(), r.minutesLate());
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		return out.toString().getBytes(StandardCharsets.UTF_8);
	}

	private void authorize(CurrentUser user, UUID schoolId, UUID classId) {
		if (classId != null) {
			this.access.readableClass(user, schoolId, classId);
			return;
		}
		this.access.gate().requireVisible(user, schoolId);
		this.access.requireFeature(schoolId);
		if (!this.access.reader(user, schoolId)) {
			throw ApiException.forbidden("attendance.access_denied");
		}
	}

	/** The range as dates, inclusive; defaults to the last 30 school days' worth of calendar (30 days ending today). */
	private Range range(UUID schoolId, LocalDate from, LocalDate to) {
		ZoneId zone = this.time.zoneOfSchool(schoolId);
		LocalDate end = (to != null) ? to : this.time.today(zone);
		LocalDate start = (from != null) ? from : end.minusDays(29);
		if (end.isBefore(start) || end.toEpochDay() - start.toEpochDay() > MAX_RANGE_DAYS) {
			throw ApiException.badRequest("attendance.invalid_range");
		}
		return new Range(start, end);
	}

	private record Range(LocalDate from, LocalDate to) {
	}

	private static final String REPORT_SELECT = """
			select r.student_id, u.display_name, lower(u.display_name) as sort_name, r.class_id, c.name as class_name,
			       count(*) as marked,
			       count(*) filter (where r.status = 'PRESENT') as present,
			       count(*) filter (where r.status = 'ABSENT') as absent,
			       count(*) filter (where r.status = 'LATE') as late,
			       count(*) filter (where r.status = 'EXCUSED') as excused,
			       count(*) filter (where r.status = 'LEFT_EARLY') as left_early,
			       coalesce(sum(r.minutes_late), 0) as minutes_late
			from attendance_records r
			join users u on u.id = r.student_id
			join classes c on c.id = r.class_id
			where r.school_id = :as_school and r.attendance_date between :as_from and :as_to
			  and (cast(:as_class as uuid) is null or r.class_id = :as_class)
			  and (cast(:as_student as uuid) is null or r.student_id = :as_student)
			group by r.student_id, u.display_name, r.class_id, c.name""";

	private static Sorted row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		return new Sorted(new AttendanceReportRow(Rows.uuid(rs, "student_id"), rs.getString("display_name"),
				Rows.uuid(rs, "class_id"), rs.getString("class_name"), rs.getInt("marked"), rs.getInt("present"),
				rs.getInt("absent"), rs.getInt("late"), rs.getInt("excused"), rs.getInt("left_early"),
				rs.getLong("minutes_late")), rs.getString("sort_name"));
	}

	private record Sorted(AttendanceReportRow row, String sortName) {
	}

	// --- CSV --------------------------------------------------------------------------------

	private byte[] csv(List<Sorted> rows, Locale locale) {
		StringWriter out = new StringWriter();
		out.write('﻿');
		try (CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
			printer.printRecord(text("student", locale), text("class", locale), text("marked", locale), text("present", locale),
					text("absent", locale), text("late", locale), text("excused", locale), text("left_early", locale),
					text("minutes_late", locale));
			for (Sorted s : rows) {
				AttendanceReportRow r = s.row();
				printer.printRecord(CsvCells.safe(r.studentName()), CsvCells.safe(r.className()), r.marked(), r.present(),
						r.absent(), r.late(), r.excused(), r.leftEarly(), r.minutesLate());
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		return out.toString().getBytes(StandardCharsets.UTF_8);
	}

	private String text(String column, Locale locale) {
		return this.messages.getMessage("attendance.report.csv." + column, null, locale);
	}

	// --- shapes -----------------------------------------------------------------------------

	public record AttendanceReportRow(@NotNull UUID studentId, @NotNull String studentName, @NotNull UUID classId,
			@NotNull String className, @Schema(description = "Marks in the range (every status)") int marked, int present,
			int absent, int late, int excused, int leftEarly, @Schema(description = "Total minutes late") long minutesLate) {
	}

	@Schema(description = "Marks of one class in one slot (and subject, for a period roll) over the range")
	public record PeriodReportRow(@NotNull UUID classId, @NotNull String className, @NotNull String slot,
			@Schema(description = "The subject of a period roll; null for rolls taken without one") UUID subjectId,
			String subjectName, @Schema(description = "Rolls taken in the range") int rolls,
			@Schema(description = "Distinct students marked") int students, int marked, int present, int absent, int late,
			int excused, int leftEarly, long minutesLate) {
	}

	public record ExportFile(byte[] content, String filename) {
	}

}
