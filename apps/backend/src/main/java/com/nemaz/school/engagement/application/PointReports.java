package com.nemaz.school.engagement.application;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.engagement.PointPolarity;
import com.nemaz.school.system.RateLimiter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Points reports: net points per student and category over a date range, for the teachers of a class (their class) and
 * for holders of {@code school.points.read} (the whole school or one class). Only CLASS and SCHOOL points count: HOME
 * points never appear in a class or school report (D2). Reversals are netted in ({@code points} is the sum of awards and
 * reversals). The CSV export is audited and rate limited, UTF-8 with a byte order mark, CRLF line ends, headers in the
 * caller's language, and every text cell that a spreadsheet could read as a formula is neutralized.
 */
@Service
@Transactional(readOnly = true)
public class PointReports {

	static final int MAX_EXPORT_ROWS = 50_000;

	private static final String EXPORT_LIMIT_CODE = "points.export_rate_limited";

	private final JdbcClient jdbc;

	private final EngagementAccess access;

	private final RateLimiter limiter;

	private final AuditLog audit;

	private final MessageSource messages;

	private final JsonMapper json;

	PointReports(JdbcClient jdbc, EngagementAccess access, RateLimiter limiter, AuditLog audit, MessageSource messages,
			JsonMapper json) {
		this.jdbc = jdbc;
		this.access = access;
		this.limiter = limiter;
		this.audit = audit;
		this.messages = messages;
		this.json = json;
	}

	public CursorPage<ReportRow> report(CurrentUser user, UUID schoolId, UUID classId, LocalDate from, LocalDate to,
			UUID categoryId, String cursor, Integer limit) {
		authorize(user, schoolId, classId);
		Window window = window(schoolId, from, to);
		int pageSize = Cursors.clampLimit(limit);
		UUID afterStudent = null;
		UUID afterCategory = null;
		if (cursor != null) {
			try {
				List<String> parts = Cursors.decode(cursor, 2);
				afterStudent = UUID.fromString(parts.get(0));
				afterCategory = UUID.fromString(parts.get(1));
			}
			catch (IllegalArgumentException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		Locale locale = LocaleContextHolder.getLocale();
		List<ReportRow> rows = this.jdbc.sql(REPORT_SELECT + """
				  and (cast(:as as uuid) is null or (t.student_id, t.category_id) > (cast(:as as uuid), cast(:ac as uuid)))
				""" + REPORT_GROUP + " order by t.student_id, t.category_id limit :n")
			.param("s", schoolId)
			.param("c", classId)
			.param("from", window.from())
			.param("to", window.to())
			.param("cat", categoryId)
			.param("as", afterStudent)
			.param("ac", afterCategory)
			.param("n", pageSize + 1)
			.query((rs, i) -> row(rs, locale))
			.list();
		return CursorPage.fromOverfetch(rows, pageSize,
				(r) -> Cursors.encode(r.studentId().toString(), r.categoryId().toString()));
	}

	/** The whole report as a CSV file (at most {@value #MAX_EXPORT_ROWS} rows: narrow the range or the class beyond that). */
	@Transactional
	public ExportFile export(CurrentUser user, UUID schoolId, UUID classId, LocalDate from, LocalDate to, UUID categoryId) {
		authorize(user, schoolId, classId);
		this.limiter.consume("points-export", user.userId().toString(), 10, Duration.ofMinutes(10), EXPORT_LIMIT_CODE);
		Window window = window(schoolId, from, to);
		Locale locale = LocaleContextHolder.getLocale();
		List<ReportRow> rows = this.jdbc.sql(REPORT_SELECT + REPORT_GROUP
				+ " order by lower(u.display_name), t.student_id, pc.sort_order, t.category_id limit :n")
			.param("s", schoolId)
			.param("c", classId)
			.param("from", window.from())
			.param("to", window.to())
			.param("cat", categoryId)
			.param("n", MAX_EXPORT_ROWS + 1)
			.query((rs, i) -> row(rs, locale))
			.list();
		if (rows.size() > MAX_EXPORT_ROWS) {
			throw ApiException.unprocessable("points.export_too_large");
		}
		this.audit.record(AuditEntry.of("points.report.export", AuditOutcome.SUCCESS)
			.target("school", schoolId)
			.school(schoolId)
			.metadata("classId", (classId != null) ? classId.toString() : null)
			.metadata("categoryId", (categoryId != null) ? categoryId.toString() : null)
			.metadata("from", (from != null) ? from.toString() : null)
			.metadata("to", (to != null) ? to.toString() : null)
			.metadata("rows", rows.size()));
		return new ExportFile(csv(rows, locale), "points-report.csv");
	}

	private void authorize(CurrentUser user, UUID schoolId, UUID classId) {
		if (classId != null) {
			ClassRef ref = this.access.viewableClass(user, schoolId, classId);
			if (!this.access.classes().canTeach(user, ref.id()) && !this.access.pointsReader(user, schoolId)) {
				throw ApiException.forbidden("class.access_denied");
			}
			return;
		}
		this.access.gate().requireVisible(user, schoolId);
		if (!this.access.pointsReader(user, schoolId)) {
			throw ApiException.forbidden("points.access_denied");
		}
	}

	private Window window(UUID schoolId, LocalDate from, LocalDate to) {
		if (from != null && to != null && to.isBefore(from)) {
			throw ApiException.badRequest("points.invalid_range");
		}
		ZoneId zone = this.jdbc.sql("select timezone from schools where id = :s")
			.param("s", schoolId)
			.query(String.class)
			.optional()
			.map(ZoneId::of)
			.orElse(ZoneId.of("UTC"));
		return new Window((from != null) ? Timestamp.from(from.atStartOfDay(zone).toInstant()) : null,
				(to != null) ? Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()) : null);
	}

	/** The range as database timestamps: {@code from} inclusive, {@code to} exclusive, either may be open. */
	private record Window(Timestamp from, Timestamp to) {
	}

	private static final String REPORT_SELECT = """
			select t.student_id, u.display_name, t.category_id, pc.name_i18n, t.polarity, sum(t.points) as points,
			       count(*) filter (where t.transaction_type = 'AWARD') as awards,
			       count(*) filter (where t.transaction_type = 'REVERSAL') as reversals
			from point_transactions t
			join users u on u.id = t.student_id
			join point_categories pc on pc.id = t.category_id
			where t.school_id = :s and t.context in ('CLASS', 'SCHOOL')
			  and (cast(:c as uuid) is null or t.class_id = :c)
			  and (cast(:from as timestamptz) is null or t.created_at >= :from)
			  and (cast(:to as timestamptz) is null or t.created_at < :to)
			  and (cast(:cat as uuid) is null or t.category_id = :cat)
			""";

	private static final String REPORT_GROUP = " group by t.student_id, u.display_name, t.category_id, pc.name_i18n, pc.sort_order, t.polarity";

	private ReportRow row(java.sql.ResultSet rs, Locale locale) throws java.sql.SQLException {
		return new ReportRow(Rows.uuid(rs, "student_id"), rs.getString("display_name"), Rows.uuid(rs, "category_id"),
				Names.pick(Names.read(this.json, rs.getString("name_i18n")), locale),
				PointPolarity.valueOf(rs.getString("polarity")), rs.getInt("points"), rs.getInt("awards"),
				rs.getInt("reversals"));
	}

	// --- CSV --------------------------------------------------------------------------------

	private byte[] csv(List<ReportRow> rows, Locale locale) {
		StringWriter out = new StringWriter();
		out.write('﻿');
		try (CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
			printer.printRecord(text("student", locale), text("category", locale), text("type", locale),
					text("points", locale), text("awards", locale), text("reversals", locale));
			for (ReportRow r : rows) {
				printer.printRecord(safe(r.studentName()), safe(r.categoryName()),
						this.messages.getMessage("points.report.csv.polarity." + r.polarity().name().toLowerCase(Locale.ROOT), null,
								locale),
						r.points(), r.awards(), r.reversals());
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		return out.toString().getBytes(StandardCharsets.UTF_8);
	}

	private String text(String column, Locale locale) {
		return this.messages.getMessage("points.report.csv." + column, null, locale);
	}

	/**
	 * Spreadsheet formula injection: a text cell that starts with {@code = + - @}, a tab or a carriage return would be
	 * read as a formula by Excel and friends, so it gets a leading apostrophe (shown as text). Numbers are written as
	 * numbers, not through this.
	 */
	public static String safe(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		char first = value.charAt(0);
		if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r' || first == '\n') {
			return "'" + value;
		}
		return value;
	}

	// --- shapes -----------------------------------------------------------------------------

	public record ReportRow(@NotNull UUID studentId, @NotNull String studentName, @NotNull UUID categoryId,
			@NotNull String categoryName, @NotNull PointPolarity polarity,
			@Schema(description = "Net points: awards plus reversals (needs-work points are negative)") int points,
			int awards, int reversals) {
	}

	public record ExportFile(byte[] content, String filename) {
	}

}
