package com.nemaz.school.academic.application;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.persistence.Constraints;
import com.nemaz.school.common.persistence.Rows;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Academic years, periods, grade levels and subjects of a school (Implementation Plan M3 step 1). Callers
 * authorize ({@code school.academic.manage} to write; school members read). Rules:
 * <ul>
 * <li>exactly one academic year is current per school ({@link #makeCurrent}); a CLOSED year cannot be
 * current, and the current year cannot be closed or deleted. Closing a year archives nothing: classes and
 * memberships stay as they are until a school promotes or archives them;</li>
 * <li>periods lie within their year, never overlap and have a unique sequence in the year (also enforced by
 * the database); changing a year's dates cannot cut off its periods;</li>
 * <li>grade level sequences are unique per school; subject codes are unique per school (case-insensitive);</li>
 * <li>rows that classes use cannot be deleted (409): deactivate grades and subjects instead.</li>
 * </ul>
 * Every mutation writes an audit row in its transaction.
 */
@Service
@Transactional
public class AcademicStructureService {

	private final JdbcClient jdbc;

	private final AuditLog audit;

	AcademicStructureService(JdbcClient jdbc, AuditLog audit) {
		this.jdbc = jdbc;
		this.audit = audit;
	}

	// --- academic years -------------------------------------------------------------------

	@Transactional(readOnly = true)
	public List<AcademicYearView> years(UUID schoolId) {
		return this.jdbc.sql(YEAR + " where school_id = :s order by starts_on desc, id desc")
			.param("s", schoolId)
			.query(AcademicStructureService::year)
			.list();
	}

	@Transactional(readOnly = true)
	public AcademicYearView year(UUID schoolId, UUID yearId) {
		return findYear(schoolId, yearId);
	}

	public AcademicYearView createYear(UUID schoolId, String name, LocalDate startsOn, LocalDate endsOn,
			YearStatus status, boolean makeCurrent) {
		requireDates(startsOn, endsOn);
		YearStatus wanted = (status != null) ? status : YearStatus.PLANNED;
		if (makeCurrent && wanted == YearStatus.CLOSED) {
			throw ApiException.conflict("academic.year_closed");
		}
		UUID id = Ids.newId();
		this.jdbc.sql("""
				insert into academic_years (id, school_id, name, starts_on, ends_on, status)
				values (:id, :s, :name, :from, :to, :status)""")
			.param("id", id)
			.param("s", schoolId)
			.param("name", name.trim())
			.param("from", startsOn)
			.param("to", endsOn)
			.param("status", wanted.name())
			.update();
		this.audit.record(AuditEntry.of("academic_year.create", AuditOutcome.SUCCESS)
			.target("academic_year", id)
			.school(schoolId)
			.metadata("name", name.trim()));
		if (makeCurrent) {
			return makeCurrent(schoolId, id);
		}
		return findYear(schoolId, id);
	}

	public AcademicYearView updateYear(UUID schoolId, UUID yearId, String name, LocalDate startsOn, LocalDate endsOn,
			YearStatus status) {
		AcademicYearView before = lockYear(schoolId, yearId);
		LocalDate from = (startsOn != null) ? startsOn : before.startsOn();
		LocalDate to = (endsOn != null) ? endsOn : before.endsOn();
		YearStatus newStatus = (status != null) ? status : before.status();
		requireDates(from, to);
		if (newStatus == YearStatus.CLOSED && before.current()) {
			throw ApiException.conflict("academic.current_year_cannot_close");
		}
		boolean outside = this.jdbc.sql("""
				select exists (select 1 from academic_periods where academic_year_id = :y and (starts_on < :from or ends_on > :to))""")
			.param("y", yearId)
			.param("from", from)
			.param("to", to)
			.query(Boolean.class)
			.single();
		if (outside) {
			throw ApiException.conflict("academic.year_dates_exclude_periods");
		}
		this.jdbc.sql("""
				update academic_years set name = :name, starts_on = :from, ends_on = :to, status = :status,
				       updated_at = now(), version = version + 1 where id = :id""")
			.param("name", (name != null) ? name.trim() : before.name())
			.param("from", from)
			.param("to", to)
			.param("status", newStatus.name())
			.param("id", yearId)
			.update();
		AcademicYearView after = findYear(schoolId, yearId);
		this.audit.record(AuditEntry.of("academic_year.update", AuditOutcome.SUCCESS)
			.target("academic_year", yearId)
			.school(schoolId)
			.changes(before, after));
		return after;
	}

	/** Makes the year the school's only current one (a PLANNED year becomes ACTIVE; the previous current year stays as it is). */
	public AcademicYearView makeCurrent(UUID schoolId, UUID yearId) {
		AcademicYearView year = lockYear(schoolId, yearId);
		if (year.status() == YearStatus.CLOSED) {
			throw ApiException.conflict("academic.year_closed");
		}
		try {
			this.jdbc.sql("update academic_years set is_current = false, updated_at = now(), version = version + 1 "
					+ "where school_id = :s and is_current and id <> :id")
				.param("s", schoolId)
				.param("id", yearId)
				.update();
			this.jdbc.sql("""
					update academic_years set is_current = true, status = 'ACTIVE', updated_at = now(), version = version + 1
					where id = :id""").param("id", yearId).update();
		}
		catch (DataIntegrityViolationException ex) {
			if (Constraints.violated(ex, "ux_academic_year_current")) {
				throw ApiException.conflict("academic.concurrent_update");
			}
			throw ex;
		}
		this.audit.record(AuditEntry.of("academic_year.make_current", AuditOutcome.SUCCESS)
			.target("academic_year", yearId)
			.school(schoolId)
			.metadata("name", year.name()));
		return findYear(schoolId, yearId);
	}

	public void deleteYear(UUID schoolId, UUID yearId) {
		AcademicYearView year = lockYear(schoolId, yearId);
		boolean inUse = this.jdbc.sql("select exists (select 1 from classes where academic_year_id = :y)")
			.param("y", yearId)
			.query(Boolean.class)
			.single();
		if (year.current() || inUse) {
			throw ApiException.conflict("academic.year_in_use");
		}
		this.jdbc.sql("delete from academic_periods where academic_year_id = :y").param("y", yearId).update();
		this.jdbc.sql("delete from academic_years where id = :y").param("y", yearId).update();
		this.audit.record(AuditEntry.of("academic_year.delete", AuditOutcome.SUCCESS)
			.target("academic_year", yearId)
			.school(schoolId)
			.metadata("name", year.name()));
	}

	/** The school's current year id, or null. */
	@Transactional(readOnly = true)
	public UUID currentYearId(UUID schoolId) {
		return this.jdbc.sql("select id from academic_years where school_id = :s and is_current")
			.param("s", schoolId)
			.query(UUID.class)
			.optional()
			.orElse(null);
	}

	// --- periods --------------------------------------------------------------------------

	@Transactional(readOnly = true)
	public List<AcademicPeriodView> periods(UUID schoolId, UUID yearId) {
		findYear(schoolId, yearId);
		return this.jdbc.sql(PERIOD + " where academic_year_id = :y order by sequence")
			.param("y", yearId)
			.query(AcademicStructureService::period)
			.list();
	}

	public AcademicPeriodView createPeriod(UUID schoolId, UUID yearId, String name, PeriodType type, Integer sequence,
			LocalDate startsOn, LocalDate endsOn) {
		AcademicYearView year = lockYear(schoolId, yearId);
		requirePeriodDates(year, startsOn, endsOn);
		int seq = (sequence != null) ? sequence : this.jdbc
			.sql("select coalesce(max(sequence), 0) + 1 from academic_periods where academic_year_id = :y")
			.param("y", yearId)
			.query(Integer.class)
			.single();
		requireNoOverlap(yearId, null, startsOn, endsOn);
		UUID id = Ids.newId();
		try {
			this.jdbc.sql("""
					insert into academic_periods (id, academic_year_id, school_id, name, period_type, sequence, starts_on, ends_on)
					values (:id, :y, :s, :name, :type, :seq, :from, :to)""")
				.param("id", id)
				.param("y", yearId)
				.param("s", schoolId)
				.param("name", name.trim())
				.param("type", type.name())
				.param("seq", seq)
				.param("from", startsOn)
				.param("to", endsOn)
				.update();
		}
		catch (DataIntegrityViolationException ex) {
			throw periodConflict(ex);
		}
		this.audit.record(AuditEntry.of("academic_period.create", AuditOutcome.SUCCESS)
			.target("academic_period", id)
			.school(schoolId)
			.metadata("yearId", yearId.toString())
			.metadata("name", name.trim()));
		return findPeriod(schoolId, yearId, id);
	}

	public AcademicPeriodView updatePeriod(UUID schoolId, UUID yearId, UUID periodId, String name, PeriodType type,
			Integer sequence, LocalDate startsOn, LocalDate endsOn) {
		AcademicYearView year = lockYear(schoolId, yearId);
		AcademicPeriodView before = findPeriod(schoolId, yearId, periodId);
		LocalDate from = (startsOn != null) ? startsOn : before.startsOn();
		LocalDate to = (endsOn != null) ? endsOn : before.endsOn();
		requirePeriodDates(year, from, to);
		requireNoOverlap(yearId, periodId, from, to);
		try {
			this.jdbc.sql("""
					update academic_periods set name = :name, period_type = :type, sequence = :seq, starts_on = :from,
					       ends_on = :to, updated_at = now(), version = version + 1 where id = :id""")
				.param("name", (name != null) ? name.trim() : before.name())
				.param("type", ((type != null) ? type : before.periodType()).name())
				.param("seq", (sequence != null) ? sequence : before.sequence())
				.param("from", from)
				.param("to", to)
				.param("id", periodId)
				.update();
		}
		catch (DataIntegrityViolationException ex) {
			throw periodConflict(ex);
		}
		AcademicPeriodView after = findPeriod(schoolId, yearId, periodId);
		this.audit.record(AuditEntry.of("academic_period.update", AuditOutcome.SUCCESS)
			.target("academic_period", periodId)
			.school(schoolId)
			.changes(before, after));
		return after;
	}

	public void deletePeriod(UUID schoolId, UUID yearId, UUID periodId) {
		lockYear(schoolId, yearId);
		AcademicPeriodView period = findPeriod(schoolId, yearId, periodId);
		this.jdbc.sql("delete from academic_periods where id = :id").param("id", periodId).update();
		this.audit.record(AuditEntry.of("academic_period.delete", AuditOutcome.SUCCESS)
			.target("academic_period", periodId)
			.school(schoolId)
			.metadata("name", period.name()));
	}

	// --- grade levels ---------------------------------------------------------------------

	@Transactional(readOnly = true)
	public List<GradeLevelView> grades(UUID schoolId) {
		return this.jdbc.sql(GRADE + " where school_id = :s order by sequence")
			.param("s", schoolId)
			.query(AcademicStructureService::grade)
			.list();
	}

	public GradeLevelView createGrade(UUID schoolId, String name, String code, Integer sequence) {
		int seq = (sequence != null) ? sequence : this.jdbc
			.sql("select coalesce(max(sequence), 0) + 1 from grade_levels where school_id = :s")
			.param("s", schoolId)
			.query(Integer.class)
			.single();
		UUID id = Ids.newId();
		try {
			this.jdbc.sql("insert into grade_levels (id, school_id, name, code, sequence) values (:id, :s, :name, :code, :seq)")
				.param("id", id)
				.param("s", schoolId)
				.param("name", name.trim())
				.param("code", blankToNull(code))
				.param("seq", seq)
				.update();
		}
		catch (DataIntegrityViolationException ex) {
			throw gradeConflict(ex);
		}
		this.audit.record(AuditEntry.of("grade_level.create", AuditOutcome.SUCCESS)
			.target("grade_level", id)
			.school(schoolId)
			.metadata("name", name.trim()));
		return findGrade(schoolId, id);
	}

	public GradeLevelView updateGrade(UUID schoolId, UUID gradeId, String name, String code, Integer sequence,
			Boolean active) {
		GradeLevelView before = findGrade(schoolId, gradeId);
		try {
			this.jdbc.sql("""
					update grade_levels set name = :name, code = :code, sequence = :seq, is_active = :active,
					       updated_at = now(), version = version + 1 where id = :id""")
				.param("name", (name != null) ? name.trim() : before.name())
				.param("code", (code != null) ? blankToNull(code) : before.code())
				.param("seq", (sequence != null) ? sequence : before.sequence())
				.param("active", (active != null) ? active : before.active())
				.param("id", gradeId)
				.update();
		}
		catch (DataIntegrityViolationException ex) {
			throw gradeConflict(ex);
		}
		GradeLevelView after = findGrade(schoolId, gradeId);
		this.audit.record(AuditEntry.of("grade_level.update", AuditOutcome.SUCCESS)
			.target("grade_level", gradeId)
			.school(schoolId)
			.changes(before, after));
		return after;
	}

	public void deleteGrade(UUID schoolId, UUID gradeId) {
		GradeLevelView grade = findGrade(schoolId, gradeId);
		try {
			this.jdbc.sql("delete from grade_levels where id = :id").param("id", gradeId).update();
		}
		catch (DataIntegrityViolationException ex) {
			throw ApiException.conflict("academic.grade_in_use");
		}
		this.audit.record(AuditEntry.of("grade_level.delete", AuditOutcome.SUCCESS)
			.target("grade_level", gradeId)
			.school(schoolId)
			.metadata("name", grade.name()));
	}

	// --- subjects -------------------------------------------------------------------------

	@Transactional(readOnly = true)
	public List<SubjectView> subjects(UUID schoolId) {
		return this.jdbc.sql(SUBJECT + " where school_id = :s order by name, id")
			.param("s", schoolId)
			.query(AcademicStructureService::subject)
			.list();
	}

	public SubjectView createSubject(UUID schoolId, String name, String code, String color) {
		UUID id = Ids.newId();
		try {
			this.jdbc.sql("insert into subjects (id, school_id, name, code, color) values (:id, :s, :name, :code, :color)")
				.param("id", id)
				.param("s", schoolId)
				.param("name", name.trim())
				.param("code", blankToNull(code))
				.param("color", blankToNull(color))
				.update();
		}
		catch (DataIntegrityViolationException ex) {
			throw subjectConflict(ex);
		}
		this.audit.record(AuditEntry.of("subject.create", AuditOutcome.SUCCESS)
			.target("subject", id)
			.school(schoolId)
			.metadata("name", name.trim()));
		return findSubject(schoolId, id);
	}

	public SubjectView updateSubject(UUID schoolId, UUID subjectId, String name, String code, String color,
			Boolean active) {
		SubjectView before = findSubject(schoolId, subjectId);
		try {
			this.jdbc.sql("""
					update subjects set name = :name, code = :code, color = :color, is_active = :active,
					       updated_at = now(), version = version + 1 where id = :id""")
				.param("name", (name != null) ? name.trim() : before.name())
				.param("code", (code != null) ? blankToNull(code) : before.code())
				.param("color", (color != null) ? blankToNull(color) : before.color())
				.param("active", (active != null) ? active : before.active())
				.param("id", subjectId)
				.update();
		}
		catch (DataIntegrityViolationException ex) {
			throw subjectConflict(ex);
		}
		SubjectView after = findSubject(schoolId, subjectId);
		this.audit.record(AuditEntry.of("subject.update", AuditOutcome.SUCCESS)
			.target("subject", subjectId)
			.school(schoolId)
			.changes(before, after));
		return after;
	}

	public void deleteSubject(UUID schoolId, UUID subjectId) {
		SubjectView subject = findSubject(schoolId, subjectId);
		try {
			this.jdbc.sql("delete from subjects where id = :id").param("id", subjectId).update();
		}
		catch (DataIntegrityViolationException ex) {
			throw ApiException.conflict("academic.subject_in_use");
		}
		this.audit.record(AuditEntry.of("subject.delete", AuditOutcome.SUCCESS)
			.target("subject", subjectId)
			.school(schoolId)
			.metadata("name", subject.name()));
	}

	// --- lookups --------------------------------------------------------------------------

	private AcademicYearView findYear(UUID schoolId, UUID yearId) {
		return this.jdbc.sql(YEAR + " where id = :id and school_id = :s")
			.param("id", yearId)
			.param("s", schoolId)
			.query(AcademicStructureService::year)
			.optional()
			.orElseThrow(() -> ApiException.notFound("academic.year_not_found"));
	}

	/** Locks the year row so changes to the year and its periods run one after the other. */
	private AcademicYearView lockYear(UUID schoolId, UUID yearId) {
		return this.jdbc.sql(YEAR + " where id = :id and school_id = :s for update")
			.param("id", yearId)
			.param("s", schoolId)
			.query(AcademicStructureService::year)
			.optional()
			.orElseThrow(() -> ApiException.notFound("academic.year_not_found"));
	}

	private AcademicPeriodView findPeriod(UUID schoolId, UUID yearId, UUID periodId) {
		return this.jdbc.sql(PERIOD + " where id = :id and academic_year_id = :y and school_id = :s")
			.param("id", periodId)
			.param("y", yearId)
			.param("s", schoolId)
			.query(AcademicStructureService::period)
			.optional()
			.orElseThrow(() -> ApiException.notFound("academic.period_not_found"));
	}

	private GradeLevelView findGrade(UUID schoolId, UUID gradeId) {
		return this.jdbc.sql(GRADE + " where id = :id and school_id = :s")
			.param("id", gradeId)
			.param("s", schoolId)
			.query(AcademicStructureService::grade)
			.optional()
			.orElseThrow(() -> ApiException.notFound("academic.grade_not_found"));
	}

	private SubjectView findSubject(UUID schoolId, UUID subjectId) {
		return this.jdbc.sql(SUBJECT + " where id = :id and school_id = :s")
			.param("id", subjectId)
			.param("s", schoolId)
			.query(AcademicStructureService::subject)
			.optional()
			.orElseThrow(() -> ApiException.notFound("academic.subject_not_found"));
	}

	private static void requireDates(LocalDate from, LocalDate to) {
		if (from == null || to == null || !to.isAfter(from)) {
			throw ApiException.badRequest("academic.invalid_dates");
		}
	}

	private static void requirePeriodDates(AcademicYearView year, LocalDate from, LocalDate to) {
		if (from == null || to == null || to.isBefore(from)) {
			throw ApiException.badRequest("academic.invalid_dates");
		}
		if (from.isBefore(year.startsOn()) || to.isAfter(year.endsOn())) {
			throw ApiException.conflict("academic.period_outside_year");
		}
	}

	private void requireNoOverlap(UUID yearId, UUID exceptPeriodId, LocalDate from, LocalDate to) {
		boolean overlap = this.jdbc.sql("""
				select exists (select 1 from academic_periods where academic_year_id = :y
				  and (cast(:except as uuid) is null or id <> :except) and starts_on <= :to and ends_on >= :from)""")
			.param("y", yearId)
			.param("except", exceptPeriodId)
			.param("from", from)
			.param("to", to)
			.query(Boolean.class)
			.single();
		if (overlap) {
			throw ApiException.conflict("academic.period_overlap");
		}
	}

	private static ApiException periodConflict(DataIntegrityViolationException ex) {
		if (Constraints.violated(ex, "uq_academic_periods_sequence")) {
			return ApiException.conflict("academic.period_sequence_taken");
		}
		if (Constraints.violated(ex, "ex_academic_periods_no_overlap")) {
			return ApiException.conflict("academic.period_overlap");
		}
		throw ex;
	}

	private static ApiException gradeConflict(DataIntegrityViolationException ex) {
		if (Constraints.violated(ex, "uq_grade_levels_sequence")) {
			return ApiException.conflict("academic.grade_sequence_taken");
		}
		throw ex;
	}

	private static ApiException subjectConflict(DataIntegrityViolationException ex) {
		if (Constraints.violated(ex, "ux_subjects_code")) {
			return ApiException.conflict("academic.subject_code_taken");
		}
		throw ex;
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.trim();
	}

	// --- rows -----------------------------------------------------------------------------

	private static final String YEAR = "select id, school_id, name, starts_on, ends_on, status, is_current from academic_years";

	private static final String PERIOD = "select id, academic_year_id, name, period_type, sequence, starts_on, ends_on from academic_periods";

	private static final String GRADE = "select id, name, code, sequence, is_active from grade_levels";

	private static final String SUBJECT = "select id, name, code, color, is_active from subjects";

	private static AcademicYearView year(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
		return new AcademicYearView(Rows.uuid(rs, "id"), rs.getString("name"), Rows.date(rs, "starts_on"),
				Rows.date(rs, "ends_on"), YearStatus.valueOf(rs.getString("status")), rs.getBoolean("is_current"));
	}

	private static AcademicPeriodView period(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
		return new AcademicPeriodView(Rows.uuid(rs, "id"), Rows.uuid(rs, "academic_year_id"), rs.getString("name"),
				PeriodType.valueOf(rs.getString("period_type")), rs.getInt("sequence"), Rows.date(rs, "starts_on"),
				Rows.date(rs, "ends_on"));
	}

	private static GradeLevelView grade(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
		return new GradeLevelView(Rows.uuid(rs, "id"), rs.getString("name"), rs.getString("code"), rs.getInt("sequence"),
				rs.getBoolean("is_active"));
	}

	private static SubjectView subject(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
		return new SubjectView(Rows.uuid(rs, "id"), rs.getString("name"), rs.getString("code"), rs.getString("color"),
				rs.getBoolean("is_active"));
	}

	public enum YearStatus {

		PLANNED, ACTIVE, CLOSED

	}

	public enum PeriodType {

		TERM, SEMESTER, QUARTER, CUSTOM

	}

	public record AcademicYearView(@NotNull UUID id, @NotNull String name, @NotNull LocalDate startsOn,
			@NotNull LocalDate endsOn, @NotNull YearStatus status,
			@NotNull @Schema(description = "Exactly one year of a school is current") boolean current) {
	}

	public record AcademicPeriodView(@NotNull UUID id, @NotNull UUID academicYearId, @NotNull String name,
			@NotNull PeriodType periodType, @NotNull int sequence, @NotNull LocalDate startsOn, @NotNull LocalDate endsOn) {
	}

	public record GradeLevelView(@NotNull UUID id, @NotNull String name, String code, @NotNull int sequence,
			@NotNull boolean active) {
	}

	public record SubjectView(@NotNull UUID id, @NotNull String name, String code, String color, @NotNull boolean active) {
	}

}
