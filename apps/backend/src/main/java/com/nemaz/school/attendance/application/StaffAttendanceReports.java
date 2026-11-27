package com.nemaz.school.attendance.application;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.nemaz.school.attendance.application.AttendanceReports.ExportFile;
import com.nemaz.school.attendance.application.StaffAttendanceModels.StaffDailyReport;
import com.nemaz.school.attendance.application.StaffAttendanceModels.StaffDailySummaryRow;
import com.nemaz.school.attendance.application.StaffAttendanceModels.StaffMonthlyReport;
import com.nemaz.school.attendance.application.StaffAttendanceModels.StaffMonthlySummaryRow;
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
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The staff attendance summaries (Implementation Plan M7d): a daily summary (counts per school-local day) and a monthly
 * summary (counts per staff member), for holders of {@code school.staff_attendance.read} or {@code .manage} only, with
 * the same switches as {@link StaffAttendanceService}. Both come as JSON and as CSV; every CSV export is audited
 * ({@code staff_attendance.report.export}: kind, range and row count, never a name or a note) and rate limited (10 per
 * 10 minutes, 429 {@code staff_attendance.export_rate_limited}). The CSV rules are those of the roll reports: UTF-8 with
 * a byte order mark, CRLF, headers in the caller's language, text cells neutralized with {@link CsvCells#safe}. Staff
 * records appear nowhere else: the class, points and student reports read other tables.
 */
@Service
@Transactional(readOnly = true)
public class StaffAttendanceReports {

	static final int MAX_DAILY_RANGE_DAYS = 93;

	static final int MAX_EXPORT_ROWS = 50_000;

	private static final String DAILY_SELECT = """
			select r.attendance_date, count(*) as marked,
			       count(*) filter (where r.status = 'PRESENT') as present,
			       count(*) filter (where r.status = 'ABSENT') as absent,
			       count(*) filter (where r.status = 'LATE') as late,
			       count(*) filter (where r.status = 'HALF_DAY') as half_day,
			       count(*) filter (where r.status = 'EXCUSED') as excused,
			       coalesce(sum(r.minutes_late), 0) as minutes_late
			from staff_attendance_records r
			where r.school_id = :school and r.attendance_date between :from and :to
			group by r.attendance_date order by r.attendance_date""";

	private static final String MONTHLY_SELECT = """
			select st.id as staff_id, u.display_name, lower(u.display_name) as sort_name, st.staff_type, st.job_title,
			       count(r.id) as marked,
			       count(r.id) filter (where r.status = 'PRESENT') as present,
			       count(r.id) filter (where r.status = 'ABSENT') as absent,
			       count(r.id) filter (where r.status = 'LATE') as late,
			       count(r.id) filter (where r.status = 'HALF_DAY') as half_day,
			       count(r.id) filter (where r.status = 'EXCUSED') as excused,
			       coalesce(sum(r.minutes_late), 0) as minutes_late
			from school_staff st
			join users u on u.id = st.user_id
			left join staff_attendance_records r on r.staff_id = st.id and r.attendance_date between :from and :to
			where st.school_id = :school
			  and (st.status = 'ACTIVE' or exists (select 1 from staff_attendance_records x
			                                       where x.staff_id = st.id and x.attendance_date between :from and :to))
			group by st.id, u.display_name, st.staff_type, st.job_title""";

	private final StaffAttendanceService staff;

	private final SchoolTime time;

	private final JdbcClient jdbc;

	private final RateLimiter limiter;

	private final AuditLog audit;

	private final MessageSource messages;

	StaffAttendanceReports(StaffAttendanceService staff, SchoolTime time, JdbcClient jdbc, RateLimiter limiter, AuditLog audit,
			MessageSource messages) {
		this.staff = staff;
		this.time = time;
		this.jdbc = jdbc;
		this.limiter = limiter;
		this.audit = audit;
		this.messages = messages;
	}

	// --- daily ------------------------------------------------------------------------------

	public StaffDailyReport daily(CurrentUser user, UUID schoolId, LocalDate from, LocalDate to) {
		this.staff.requireReader(user, schoolId);
		return dailyReport(schoolId, from, to);
	}

	@Transactional
	public ExportFile exportDaily(CurrentUser user, UUID schoolId, LocalDate from, LocalDate to) {
		this.staff.requireReader(user, schoolId);
		this.limiter.consume("staff-attendance-export", user.userId().toString(), 10, Duration.ofMinutes(10),
				"staff_attendance.export_rate_limited");
		StaffDailyReport report = dailyReport(schoolId, from, to);
		audit("DAILY", schoolId, report.from().toString(), report.to().toString(), report.rows().size());
		Locale locale = LocaleContextHolder.getLocale();
		StringWriter out = new StringWriter();
		out.write('﻿');
		try (CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
			printer.printRecord(text("date", locale), text("marked", locale), text("present", locale), text("absent", locale),
					text("late", locale), text("half_day", locale), text("excused", locale), text("minutes_late", locale));
			for (StaffDailySummaryRow r : report.rows()) {
				printer.printRecord(r.date(), r.marked(), r.present(), r.absent(), r.late(), r.halfDay(), r.excused(),
						r.minutesLate());
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		return new ExportFile(out.toString().getBytes(StandardCharsets.UTF_8), "staff-attendance-daily.csv");
	}

	private StaffDailyReport dailyReport(UUID schoolId, LocalDate from, LocalDate to) {
		ZoneId zone = this.time.zoneOfSchool(schoolId);
		LocalDate end = (to != null) ? to : this.time.today(zone);
		LocalDate start = (from != null) ? from : end.minusDays(29);
		if (end.isBefore(start) || end.toEpochDay() - start.toEpochDay() > MAX_DAILY_RANGE_DAYS) {
			throw ApiException.badRequest("staff_attendance.invalid_range");
		}
		List<StaffDailySummaryRow> rows = this.jdbc.sql(DAILY_SELECT)
			.param("school", schoolId)
			.param("from", java.sql.Date.valueOf(start))
			.param("to", java.sql.Date.valueOf(end))
			.query((rs, i) -> new StaffDailySummaryRow(Rows.date(rs, "attendance_date"), rs.getInt("marked"), rs.getInt("present"),
					rs.getInt("absent"), rs.getInt("late"), rs.getInt("half_day"), rs.getInt("excused"), rs.getLong("minutes_late")))
			.list();
		return new StaffDailyReport(start, end, rows);
	}

	// --- monthly ----------------------------------------------------------------------------

	public StaffMonthlyReport monthly(CurrentUser user, UUID schoolId, String month, String cursor, Integer limit) {
		this.staff.requireReader(user, schoolId);
		YearMonth ym = month(schoolId, month);
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
		List<SortedMonthly> rows = this.jdbc.sql("select * from (" + MONTHLY_SELECT + """
				) x where (cast(:an as text) is null or (x.sort_name, x.staff_id) > (cast(:an as text), cast(:ai as uuid)))
				order by x.sort_name, x.staff_id limit :n""")
			.param("school", schoolId)
			.param("from", java.sql.Date.valueOf(ym.atDay(1)))
			.param("to", java.sql.Date.valueOf(ym.atEndOfMonth()))
			.param("an", afterName)
			.param("ai", afterId)
			.param("n", pageSize + 1)
			.query(StaffAttendanceReports::monthlyRow)
			.list();
		CursorPage<StaffMonthlySummaryRow> page = CursorPage
			.fromOverfetch(rows, pageSize, (r) -> Cursors.encode(r.sortName(), r.row().staffId().toString()))
			.map(SortedMonthly::row);
		return new StaffMonthlyReport(ym.toString(), page);
	}

	@Transactional
	public ExportFile exportMonthly(CurrentUser user, UUID schoolId, String month) {
		this.staff.requireReader(user, schoolId);
		this.limiter.consume("staff-attendance-export", user.userId().toString(), 10, Duration.ofMinutes(10),
				"staff_attendance.export_rate_limited");
		YearMonth ym = month(schoolId, month);
		List<SortedMonthly> rows = this.jdbc.sql("select * from (" + MONTHLY_SELECT
				+ ") x order by x.sort_name, x.staff_id limit :n")
			.param("school", schoolId)
			.param("from", java.sql.Date.valueOf(ym.atDay(1)))
			.param("to", java.sql.Date.valueOf(ym.atEndOfMonth()))
			.param("n", MAX_EXPORT_ROWS + 1)
			.query(StaffAttendanceReports::monthlyRow)
			.list();
		if (rows.size() > MAX_EXPORT_ROWS) {
			throw ApiException.unprocessable("attendance.export_too_large");
		}
		audit("MONTHLY", schoolId, ym.atDay(1).toString(), ym.atEndOfMonth().toString(), rows.size());
		Locale locale = LocaleContextHolder.getLocale();
		StringWriter out = new StringWriter();
		out.write('﻿');
		try (CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
			printer.printRecord(text("staff", locale), text("staff_type", locale), text("job_title", locale), text("marked", locale),
					text("present", locale), text("absent", locale), text("late", locale), text("half_day", locale),
					text("excused", locale), text("minutes_late", locale));
			for (SortedMonthly s : rows) {
				StaffMonthlySummaryRow r = s.row();
				printer.printRecord(CsvCells.safe(r.displayName()), r.staffType(), CsvCells.safe(r.jobTitle()), r.marked(),
						r.present(), r.absent(), r.late(), r.halfDay(), r.excused(), r.minutesLate());
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		return new ExportFile(out.toString().getBytes(StandardCharsets.UTF_8), "staff-attendance-" + ym + ".csv");
	}

	/** {@code yyyy-MM}; the school's current month by default. */
	private YearMonth month(UUID schoolId, String month) {
		if (month == null || month.isBlank()) {
			return YearMonth.from(this.time.today(this.time.zoneOfSchool(schoolId)));
		}
		try {
			return YearMonth.parse(month.strip());
		}
		catch (DateTimeParseException ex) {
			throw ApiException.badRequest("staff_attendance.invalid_month");
		}
	}

	private static SortedMonthly monthlyRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		return new SortedMonthly(new StaffMonthlySummaryRow(Rows.uuid(rs, "staff_id"), rs.getString("display_name"),
				rs.getString("staff_type"), rs.getString("job_title"), rs.getInt("marked"), rs.getInt("present"),
				rs.getInt("absent"), rs.getInt("late"), rs.getInt("half_day"), rs.getInt("excused"), rs.getLong("minutes_late")),
				rs.getString("sort_name"));
	}

	private record SortedMonthly(StaffMonthlySummaryRow row, String sortName) {
	}

	private void audit(String kind, UUID schoolId, String from, String to, int rows) {
		this.audit.record(AuditEntry.of("staff_attendance.report.export", AuditOutcome.SUCCESS)
			.target("school", schoolId)
			.school(schoolId)
			.metadata("kind", kind)
			.metadata("from", from)
			.metadata("to", to)
			.metadata("rows", rows));
	}

	private String text(String column, Locale locale) {
		return this.messages.getMessage("attendance.report.csv." + column, null, locale);
	}

}
