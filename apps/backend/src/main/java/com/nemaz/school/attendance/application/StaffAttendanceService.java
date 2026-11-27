package com.nemaz.school.attendance.application;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.attendance.AttendanceStatus;
import com.nemaz.school.attendance.StaffAttendanceStatus;
import com.nemaz.school.attendance.application.CheckinLedger.Attempt;
import com.nemaz.school.attendance.application.CheckinModels.CheckinResultCode;
import com.nemaz.school.attendance.application.StaffAttendanceModels.StaffCorrectionRequest;
import com.nemaz.school.attendance.application.StaffAttendanceModels.StaffCorrectionView;
import com.nemaz.school.attendance.application.StaffAttendanceModels.StaffDayEntry;
import com.nemaz.school.attendance.application.StaffAttendanceModels.StaffDayRequest;
import com.nemaz.school.attendance.application.StaffAttendanceModels.StaffDayResult;
import com.nemaz.school.attendance.application.StaffAttendanceModels.StaffDayRoll;
import com.nemaz.school.attendance.application.StaffAttendanceModels.StaffMarkInput;
import com.nemaz.school.attendance.application.StaffAttendanceModels.StaffMarkOutcome;
import com.nemaz.school.attendance.application.StaffAttendanceModels.StaffRecordDetail;
import com.nemaz.school.attendance.application.StaffAttendanceModels.StaffRecordView;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.tx.AdvisoryLocks;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff attendance (Implementation Plan M7d, Database_Design §4.34), marked by hand. A manager
 * ({@code school.staff_attendance.manage}) marks the staff of a school-local day and corrects a record with a reason
 * (append-only corrections); {@code school.staff_attendance.read} reads the records and the summaries; a staff member
 * reads their own records. <b>A teacher never reads a colleague's record</b>: a teacher is a staff member and nothing
 * more, so only the permissions above (or the person themself) open a record, and a record the caller may not read is
 * a 404, exactly like an unknown id. The module's own switches apply in this order: the school is visible (404), the
 * permission (403 {@code staff_attendance.access_denied}), the feature {@code attendance} (403
 * {@code attendance.feature_disabled}) and the setting {@code attendance.staff.enabled} (403
 * {@code staff_attendance.disabled}). Dates are school-local ({@link SchoolTime}: the staff member's campus zone, else
 * the school's). There is no leave, pay or other HR data (D14), and staff records never reach class, points or student
 * reports.
 */
@Service
@Transactional
public class StaffAttendanceService {

	/** Marking a day is allowed this far back (older gaps are a data-entry job for an import, not for the desk). */
	static final int MAX_DAYS_BACK = 62;

	static final int MAX_RANGE_DAYS = 400;

	static final int MAX_MARKS = 500;

	private static final String RECORD_SELECT = """
			select r.id, r.staff_id, u.display_name, r.attendance_date, r.status, r.minutes_late, r.note, r.method, r.campus_id,
			       r.version, r.recorded_by, r.created_at,
			       (select count(*) from staff_attendance_corrections c where c.record_id = r.id) as corrections
			from staff_attendance_records r
			join school_staff st on st.id = r.staff_id
			join users u on u.id = st.user_id""";

	private final AttendanceAccess access;

	private final AttendanceSettings settings;

	private final SchoolTime time;

	private final JdbcClient jdbc;

	private final AuditLog audit;

	private final CheckinLedger ledger;

	StaffAttendanceService(AttendanceAccess access, AttendanceSettings settings, SchoolTime time, JdbcClient jdbc, AuditLog audit,
			CheckinLedger ledger) {
		this.ledger = ledger;
		this.access = access;
		this.settings = settings;
		this.time = time;
		this.jdbc = jdbc;
		this.audit = audit;
	}

	// --- access -----------------------------------------------------------------------------

	boolean mayManage(CurrentUser user, UUID schoolId) {
		return this.access.gate().can(user, schoolId, Permissions.SCHOOL_STAFF_ATTENDANCE_MANAGE);
	}

	/** Holder of {@code school.staff_attendance.read} or {@code .manage}. */
	boolean mayRead(CurrentUser user, UUID schoolId) {
		return this.access.gate().can(user, schoolId, Permissions.SCHOOL_STAFF_ATTENDANCE_READ) || mayManage(user, schoolId);
	}

	/** 404 for a school the caller cannot see, 403 without the permission, then the feature and the setting. */
	void requireReader(CurrentUser user, UUID schoolId) {
		this.access.gate().requireVisible(user, schoolId);
		if (!mayRead(user, schoolId)) {
			throw ApiException.forbidden("staff_attendance.access_denied");
		}
		requireOn(schoolId);
	}

	void requireManager(CurrentUser user, UUID schoolId) {
		this.access.gate().requireVisible(user, schoolId);
		if (!mayManage(user, schoolId)) {
			throw ApiException.forbidden("staff_attendance.access_denied");
		}
		requireOn(schoolId);
	}

	private void requireOn(UUID schoolId) {
		this.access.requireFeature(schoolId);
		if (!this.settings.staffEnabled(schoolId)) {
			throw ApiException.forbidden("staff_attendance.disabled");
		}
	}

	// --- the day's roll ---------------------------------------------------------------------

	/** The staff of a school-local day with their records (managers and readers). */
	@Transactional(readOnly = true)
	public StaffDayRoll day(CurrentUser user, UUID schoolId, LocalDate date, UUID campusId, String cursor, Integer limit) {
		requireReader(user, schoolId);
		ZoneId zone = this.time.zoneOfSchool(schoolId);
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
		List<SortedEntry> rows = this.jdbc.sql("""
				select st.id as staff_id, u.display_name, lower(u.display_name) as sort_name, st.staff_type, st.job_title,
				       st.campus_id, r.id as record_id, r.status, r.minutes_late, r.note, r.method, r.campus_id as record_campus,
				       r.version, r.recorded_by, r.created_at,
				       (select count(*) from staff_attendance_corrections c where c.record_id = r.id) as corrections
				from school_staff st
				join users u on u.id = st.user_id
				left join staff_attendance_records r on r.staff_id = st.id and r.attendance_date = :date
				where st.school_id = :school
				  and (cast(:campus as uuid) is null or st.campus_id = :campus)
				  and ((st.status = 'ACTIVE' and (st.started_on is null or st.started_on <= :date)
				        and (st.ended_on is null or st.ended_on >= :date)) or r.id is not null)
				  and (cast(:an as text) is null or (lower(u.display_name), st.id) > (cast(:an as text), cast(:ai as uuid)))
				order by lower(u.display_name), st.id limit :n""")
			.param("date", java.sql.Date.valueOf(date))
			.param("school", schoolId)
			.param("campus", campusId)
			.param("an", afterName)
			.param("ai", afterId)
			.param("n", pageSize + 1)
			.query((rs, i) -> {
				UUID recordId = Rows.uuid(rs, "record_id");
				StaffRecordView record = (recordId == null) ? null
						: new StaffRecordView(recordId, Rows.uuid(rs, "staff_id"), rs.getString("display_name"), date,
								StaffAttendanceStatus.valueOf(rs.getString("status")), Rows.integer(rs, "minutes_late"),
								rs.getString("note"), rs.getString("method"), Rows.uuid(rs, "record_campus"), rs.getInt("version"),
								Rows.uuid(rs, "recorded_by"), Rows.instant(rs, "created_at"), rs.getInt("corrections"));
				return new SortedEntry(new StaffDayEntry(Rows.uuid(rs, "staff_id"), rs.getString("display_name"),
						rs.getString("staff_type"), rs.getString("job_title"), Rows.uuid(rs, "campus_id"), record),
						rs.getString("sort_name"));
			})
			.list();
		CursorPage<StaffDayEntry> page = CursorPage
			.fromOverfetch(rows, pageSize, (e) -> Cursors.encode(e.sortName(), e.entry().staffId().toString()))
			.map(SortedEntry::entry);
		return new StaffDayRoll(date, zone.getId(), page);
	}

	private record SortedEntry(StaffDayEntry entry, String sortName) {
	}

	// --- marking a day ----------------------------------------------------------------------

	/**
	 * Creates the day's records of the listed staff (and, with {@code markRestPresent}, PRESENT for every other ACTIVE staff
	 * member of the day without one). Everything is validated before anything is written; a staff member who already has a
	 * record keeps it (outcome EXISTS), so a repeated request is harmless and a change always needs a correction.
	 */
	public StaffDayResult mark(CurrentUser user, UUID schoolId, LocalDate date, StaffDayRequest cmd) {
		requireManager(user, schoolId);
		List<StaffMarkInput> listed = (cmd.records() != null) ? cmd.records() : List.of();
		boolean rest = Boolean.TRUE.equals(cmd.markRestPresent());
		if (listed.isEmpty() && !rest) {
			throw ApiException.badRequest("staff_attendance.nothing_to_record");
		}
		if (listed.size() > MAX_MARKS) {
			throw ApiException.badRequest("staff_attendance.too_many_records");
		}
		Set<UUID> seen = new HashSet<>();
		for (StaffMarkInput in : listed) {
			if (!seen.add(in.staffId())) {
				throw ApiException.badRequest("staff_attendance.duplicate_staff");
			}
			checkMinutes(in.status(), in.minutesLate());
		}
		if (date.isBefore(this.time.today(this.time.zoneOfSchool(schoolId)).minusDays(MAX_DAYS_BACK))) {
			throw ApiException.badRequest("staff_attendance.date_too_old");
		}
		Map<UUID, StaffRow> staff = staffRows(schoolId, seen);
		for (UUID id : seen) {
			StaffRow row = staff.get(id);
			if (row == null) {
				throw ApiException.notFound("staff_attendance.staff_not_found");
			}
			if (!row.active()) {
				throw ApiException.conflict("staff_attendance.staff_not_active");
			}
		}
		List<Mark> marks = new ArrayList<>();
		for (StaffMarkInput in : listed) {
			marks.add(new Mark(staff.get(in.staffId()), in.status(), in.minutesLate(), clean(in.note())));
		}
		if (rest) {
			for (StaffRow row : activeStaffOn(schoolId, date)) {
				if (!seen.contains(row.id())) {
					marks.add(new Mark(row, StaffAttendanceStatus.PRESENT, null, null));
				}
			}
		}
		Map<UUID, ZoneId> zones = new HashMap<>();
		for (Mark mark : marks) {
			ZoneId zone = zones.computeIfAbsent(mark.staff().id(), (id) -> this.time.zoneOf(schoolId, mark.staff().campusId()));
			if (date.isAfter(this.time.today(zone))) {
				throw ApiException.conflict("staff_attendance.date_in_future");
			}
		}
		List<StaffMarkOutcome> outcomes = new ArrayList<>();
		int created = 0;
		int existing = 0;
		Timestamp now = Timestamp.from(this.time.now());
		for (Mark mark : marks) {
			int inserted = this.jdbc.sql("""
					insert into staff_attendance_records (id, school_id, campus_id, staff_id, attendance_date, status, minutes_late,
					                                      method, recorded_by, note, created_at, updated_at)
					values (:id, :school, :campus, :staff, :date, :status, :minutes, 'MANUAL', :by, :note, :now, :now)
					on conflict (staff_id, attendance_date) do nothing""")
				.param("id", Ids.newId())
				.param("school", schoolId)
				.param("campus", mark.staff().campusId())
				.param("staff", mark.staff().id())
				.param("date", java.sql.Date.valueOf(date))
				.param("status", mark.status().name())
				.param("minutes", mark.minutesLate())
				.param("by", user.userId())
				.param("note", mark.note())
				.param("now", now)
				.update();
			StaffRecordView view = recordOf(mark.staff().id(), date);
			outcomes.add(new StaffMarkOutcome(mark.staff().id(), (inserted > 0) ? "CREATED" : "EXISTS", view));
			if (inserted > 0) {
				created++;
			}
			else {
				existing++;
			}
		}
		if (created > 0) {
			this.audit.record(AuditEntry.of("staff_attendance.day.record", AuditOutcome.SUCCESS)
				.target("school", schoolId)
				.school(schoolId)
				.metadata("date", date.toString())
				.metadata("created", created)
				.metadata("existing", existing)
				.metadata("markedRestPresent", rest));
		}
		return new StaffDayResult(date, created, existing, outcomes);
	}

	// --- check-ins (M7e) --------------------------------------------------------------------

	/** What a staff check-in asks of the day's record; {@code status} is PRESENT or LATE. */
	record StaffCheckin(Attempt attempt, StaffAttendanceStatus status, Integer minutesLate) {
	}

	record StaffCheckinOutcome(CheckinResultCode result, UUID checkinId, StaffRecordView record) {

		boolean accepted() {
			return this.result == CheckinResultCode.ACCEPTED;
		}

	}

	/**
	 * The only way a QR scan reaches {@code staff_attendance_records}, under the rules of manual marking: one record per
	 * staff member and school-local day (their campus's zone, else the school's), <b>never overwritten</b> (a stored record
	 * that holds the same status is REJECTED_DUPLICATE, a different one REJECTED_CONFLICT: a change is a manager's correction),
	 * only PRESENT or LATE, only for the staff member's current day (REJECTED_WINDOW), only for an ACTIVE staff member
	 * (REJECTED_NOT_ENROLLED). The caller has checked the credential or the code and the scanner's right; the ledger row is
	 * appended in every case, the record only when ACCEPTED. The switches (feature, {@code attendance.staff.enabled}) are
	 * the caller's: {@link #requireCheckinOn}.
	 */
	StaffCheckinOutcome recordFromCheckin(StaffCheckin c) {
		Attempt attempt = c.attempt();
		UUID schoolId = attempt.schoolId();
		UUID staffId = attempt.personId();
		if (c.status() != StaffAttendanceStatus.PRESENT && c.status() != StaffAttendanceStatus.LATE) {
			throw ApiException.badRequest("attendance.checkin_status_invalid");
		}
		checkMinutes(c.status(), c.minutesLate());
		StaffRow staff = staffRows(schoolId, Set.of(staffId)).get(staffId);
		if (staff == null || !staff.active()) {
			return refuse(attempt, CheckinResultCode.REJECTED_NOT_ENROLLED, null);
		}
		ZoneId zone = this.time.zoneOf(schoolId, staff.campusId());
		LocalDate date = attempt.date();
		if (!date.equals(this.time.today(zone))) {
			return refuse(attempt, CheckinResultCode.REJECTED_WINDOW, null);
		}
		AdvisoryLocks.acquire(this.jdbc, "staff-attendance:" + staffId + ":" + date);
		StaffRecordView existing = this.jdbc.sql(RECORD_SELECT + " where r.staff_id = :staff and r.attendance_date = :date")
			.param("staff", staffId)
			.param("date", java.sql.Date.valueOf(date))
			.query(StaffAttendanceService::view)
			.optional()
			.orElse(null);
		if (existing != null) {
			boolean same = existing.status() == c.status() && java.util.Objects.equals(existing.minutesLate(), c.minutesLate());
			return refuse(attempt, same ? CheckinResultCode.REJECTED_DUPLICATE : CheckinResultCode.REJECTED_CONFLICT, existing);
		}
		Timestamp now = Timestamp.from(this.time.now());
		this.ledger.append(attempt, CheckinResultCode.ACCEPTED, AttendanceStatus.valueOf(c.status().name()), c.minutesLate());
		int inserted = this.jdbc.sql("""
				insert into staff_attendance_records (id, school_id, campus_id, staff_id, attendance_date, status, minutes_late,
				                                      check_in_at, method, checkin_id, recorded_by, created_at, updated_at)
				values (:id, :school, :campus, :staff, :date, :status, :minutes, :now, :method, :checkin, :by, :now, :now)
				on conflict (staff_id, attendance_date) do nothing""")
			.param("id", Ids.newId())
			.param("school", schoolId)
			.param("campus", staff.campusId())
			.param("staff", staffId)
			.param("date", java.sql.Date.valueOf(date))
			.param("status", c.status().name())
			.param("minutes", c.minutesLate())
			.param("now", now)
			.param("method", attempt.method())
			.param("checkin", attempt.id())
			.param("by", attempt.scannerUserId())
			.update();
		if (inserted == 0) {
			// a manual mark of the same day slipped in between: nothing is written (this rolls the ledger row back too)
			throw ApiException.conflict("attendance.checkin_conflict");
		}
		return new StaffCheckinOutcome(CheckinResultCode.ACCEPTED, attempt.id(), recordOf(staffId, date));
	}

	private StaffCheckinOutcome refuse(Attempt attempt, CheckinResultCode result, StaffRecordView existing) {
		this.ledger.append(attempt, result, null, null);
		return new StaffCheckinOutcome(result, attempt.id(), existing);
	}

	/** A manager takes a check-in's record off the day, while it is exactly what the check-in wrote (version 0: no correction). */
	boolean removeCheckinRecord(UUID checkinId) {
		return this.jdbc.sql("delete from staff_attendance_records where checkin_id = :c and version = 0")
			.param("c", checkinId)
			.update() > 0;
	}

	/** The switches of staff check-in: the feature {@code attendance} and the setting {@code attendance.staff.enabled}. */
	void requireCheckinOn(UUID schoolId) {
		requireOn(schoolId);
	}

	/** The caller's ACTIVE staff row of the school (a rotating check-in is the staff member's own); empty for nobody else. */
	java.util.Optional<UUID> activeStaffIdOf(UUID schoolId, UUID userId) {
		return this.jdbc.sql("select id from school_staff where school_id = :s and user_id = :u and status = 'ACTIVE' order by id limit 1")
			.param("s", schoolId)
			.param("u", userId)
			.query(UUID.class)
			.optional();
	}

	// --- corrections ------------------------------------------------------------------------

	/** Changes a record's status, lateness or note and appends the correction with its reason (never edited, never deleted). */
	public StaffRecordDetail correct(CurrentUser user, UUID schoolId, UUID recordId, StaffCorrectionRequest cmd) {
		requireManager(user, schoolId);
		RecordRow row = this.jdbc.sql("""
				select id, staff_id, status, minutes_late, note, version from staff_attendance_records
				where id = :id and school_id = :school for update""")
			.param("id", recordId)
			.param("school", schoolId)
			.query((rs, i) -> new RecordRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "staff_id"),
					StaffAttendanceStatus.valueOf(rs.getString("status")), Rows.integer(rs, "minutes_late"), rs.getString("note"),
					rs.getInt("version")))
			.optional()
			.orElseThrow(() -> ApiException.notFound("staff_attendance.record_not_found"));
		checkMinutes(cmd.status(), cmd.minutesLate());
		String reason = cmd.reason().strip();
		if (reason.isEmpty()) {
			throw ApiException.badRequest("staff_attendance.reason_required");
		}
		if (cmd.expectedVersion() != row.version()) {
			throw ApiException.conflict("staff_attendance.version_conflict");
		}
		String note = (cmd.note() == null) ? row.note() : clean(cmd.note());
		if (cmd.status() == row.status() && java.util.Objects.equals(cmd.minutesLate(), row.minutesLate())
				&& java.util.Objects.equals(note, row.note())) {
			throw ApiException.badRequest("staff_attendance.nothing_to_correct");
		}
		Timestamp now = Timestamp.from(this.time.now());
		this.jdbc.sql("""
				update staff_attendance_records set status = :status, minutes_late = :minutes, note = :note,
				       version = version + 1, updated_at = :now
				where id = :id and version = :version""")
			.param("status", cmd.status().name())
			.param("minutes", cmd.minutesLate())
			.param("note", note)
			.param("now", now)
			.param("id", recordId)
			.param("version", row.version())
			.update();
		this.jdbc.sql("""
				insert into staff_attendance_corrections (id, school_id, record_id, old_status, new_status, old_minutes_late,
				                                          new_minutes_late, reason, corrected_by, corrected_at)
				values (:id, :school, :record, :old, :new, :oldMinutes, :newMinutes, :reason, :by, :now)""")
			.param("id", Ids.newId())
			.param("school", schoolId)
			.param("record", recordId)
			.param("old", row.status().name())
			.param("new", cmd.status().name())
			.param("oldMinutes", row.minutesLate())
			.param("newMinutes", cmd.minutesLate())
			.param("reason", reason)
			.param("by", user.userId())
			.param("now", now)
			.update();
		this.audit.record(AuditEntry.of("staff_attendance.record.correct", AuditOutcome.SUCCESS)
			.target("staff_attendance_record", recordId)
			.school(schoolId)
			.metadata("oldStatus", row.status().name())
			.metadata("newStatus", cmd.status().name()));
		return detail(schoolId, recordId);
	}

	// --- reads ------------------------------------------------------------------------------

	/** Records over school-local dates, newest first (managers and readers); staffId narrows to one staff member. */
	@Transactional(readOnly = true)
	public CursorPage<StaffRecordView> list(CurrentUser user, UUID schoolId, UUID staffId, LocalDate from, LocalDate to,
			String cursor, Integer limit) {
		requireReader(user, schoolId);
		return page(schoolId, staffId, null, from, to, cursor, limit);
	}

	/** The caller's own records: any ACTIVE staff member of the school (a teacher, an administrator, support staff). */
	@Transactional(readOnly = true)
	public CursorPage<StaffRecordView> mine(CurrentUser user, UUID schoolId, LocalDate from, LocalDate to, String cursor,
			Integer limit) {
		this.access.gate().requireVisible(user, schoolId);
		List<UUID> own = ownStaffIds(user, schoolId);
		if (own.isEmpty() || !isActiveStaff(user, schoolId)) {
			throw ApiException.forbidden("staff_attendance.access_denied");
		}
		requireOn(schoolId);
		return page(schoolId, null, own, from, to, cursor, limit);
	}

	/**
	 * One record with its corrections: readers and the staff member themself; for everybody else (a colleague included)
	 * it does not exist (404).
	 */
	@Transactional(readOnly = true)
	public StaffRecordDetail record(CurrentUser user, UUID schoolId, UUID recordId) {
		this.access.gate().requireVisible(user, schoolId);
		UUID staffId = this.jdbc.sql("select staff_id from staff_attendance_records where id = :id and school_id = :s")
			.param("id", recordId)
			.param("s", schoolId)
			.query(UUID.class)
			.optional()
			.orElse(null);
		if (staffId == null || !(mayRead(user, schoolId) || ownStaffIds(user, schoolId).contains(staffId))) {
			throw ApiException.notFound("staff_attendance.record_not_found");
		}
		requireOn(schoolId);
		return detail(schoolId, recordId);
	}

	private CursorPage<StaffRecordView> page(UUID schoolId, UUID staffId, List<UUID> staffIds, LocalDate from, LocalDate to,
			String cursor, Integer limit) {
		ZoneId zone = this.time.zoneOfSchool(schoolId);
		LocalDate end = (to != null) ? to : this.time.today(zone);
		LocalDate start = (from != null) ? from : end.minusDays(29);
		if (end.isBefore(start) || end.toEpochDay() - start.toEpochDay() > MAX_RANGE_DAYS) {
			throw ApiException.badRequest("staff_attendance.invalid_range");
		}
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
		String scope = (staffIds != null) ? "r.staff_id in (:ids)" : "(cast(:staff as uuid) is null or r.staff_id = :staff)";
		JdbcClient.StatementSpec spec = this.jdbc.sql(RECORD_SELECT + """

				where r.school_id = :school and r.attendance_date between :from and :to and\s""" + scope + """

				  and (cast(:ad as date) is null or (r.attendance_date, r.id) < (cast(:ad as date), cast(:ai as uuid)))
				order by r.attendance_date desc, r.id desc limit :n""")
			.param("school", schoolId)
			.param("from", java.sql.Date.valueOf(start))
			.param("to", java.sql.Date.valueOf(end))
			.param("ad", (afterDate != null) ? java.sql.Date.valueOf(afterDate) : null)
			.param("ai", afterId)
			.param("n", pageSize + 1);
		spec = (staffIds != null) ? spec.param("ids", staffIds) : spec.param("staff", staffId);
		List<StaffRecordView> rows = spec.query(StaffAttendanceService::view).list();
		return CursorPage.fromOverfetch(rows, pageSize, (r) -> Cursors.encode(r.date().toString(), r.id().toString()));
	}

	private StaffRecordDetail detail(UUID schoolId, UUID recordId) {
		StaffRecordView record = this.jdbc.sql(RECORD_SELECT + " where r.id = :id and r.school_id = :school")
			.param("id", recordId)
			.param("school", schoolId)
			.query(StaffAttendanceService::view)
			.single();
		List<StaffCorrectionView> corrections = this.jdbc.sql("""
				select id, old_status, new_status, old_minutes_late, new_minutes_late, reason, corrected_by, corrected_at
				from staff_attendance_corrections where record_id = :id order by corrected_at, id""")
			.param("id", recordId)
			.query((rs, i) -> new StaffCorrectionView(Rows.uuid(rs, "id"), StaffAttendanceStatus.valueOf(rs.getString("old_status")),
					StaffAttendanceStatus.valueOf(rs.getString("new_status")), Rows.integer(rs, "old_minutes_late"),
					Rows.integer(rs, "new_minutes_late"), rs.getString("reason"), Rows.uuid(rs, "corrected_by"),
					Rows.instant(rs, "corrected_at")))
			.list();
		return new StaffRecordDetail(record, corrections);
	}

	private StaffRecordView recordOf(UUID staffId, LocalDate date) {
		return this.jdbc.sql(RECORD_SELECT + " where r.staff_id = :staff and r.attendance_date = :date")
			.param("staff", staffId)
			.param("date", java.sql.Date.valueOf(date))
			.query(StaffAttendanceService::view)
			.single();
	}

	private static StaffRecordView view(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		return new StaffRecordView(Rows.uuid(rs, "id"), Rows.uuid(rs, "staff_id"), rs.getString("display_name"),
				Rows.date(rs, "attendance_date"), StaffAttendanceStatus.valueOf(rs.getString("status")),
				Rows.integer(rs, "minutes_late"), rs.getString("note"), rs.getString("method"), Rows.uuid(rs, "campus_id"),
				rs.getInt("version"), Rows.uuid(rs, "recorded_by"), Rows.instant(rs, "created_at"), rs.getInt("corrections"));
	}

	// --- staff rows -------------------------------------------------------------------------

	/** The caller's staff rows of the school (past ones too, so that a returning member sees their history). */
	private List<UUID> ownStaffIds(CurrentUser user, UUID schoolId) {
		return this.jdbc.sql("select id from school_staff where school_id = :s and user_id = :u")
			.param("s", schoolId)
			.param("u", user.userId())
			.query(UUID.class)
			.list();
	}

	private boolean isActiveStaff(CurrentUser user, UUID schoolId) {
		return this.jdbc.sql("select exists (select 1 from school_staff where school_id = :s and user_id = :u and status = 'ACTIVE')")
			.param("s", schoolId)
			.param("u", user.userId())
			.query(Boolean.class)
			.single();
	}

	private Map<UUID, StaffRow> staffRows(UUID schoolId, Set<UUID> ids) {
		Map<UUID, StaffRow> rows = new LinkedHashMap<>();
		if (ids.isEmpty()) {
			return rows;
		}
		this.jdbc.sql("select id, campus_id, status from school_staff where school_id = :s and id in (:ids)")
			.param("s", schoolId)
			.param("ids", ids)
			.query((rs, i) -> {
				rows.put(Rows.uuid(rs, "id"), new StaffRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "campus_id"), "ACTIVE".equals(rs.getString("status"))));
				return null;
			})
			.list();
		return rows;
	}

	/** ACTIVE staff of the school on the day (employed that day) who have no record yet. */
	private List<StaffRow> activeStaffOn(UUID schoolId, LocalDate date) {
		return this.jdbc.sql("""
				select st.id, st.campus_id from school_staff st
				where st.school_id = :s and st.status = 'ACTIVE' and (st.started_on is null or st.started_on <= :d)
				  and (st.ended_on is null or st.ended_on >= :d)
				  and not exists (select 1 from staff_attendance_records r where r.staff_id = st.id and r.attendance_date = :d)""")
			.param("s", schoolId)
			.param("d", java.sql.Date.valueOf(date))
			.query((rs, i) -> new StaffRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "campus_id"), true))
			.list();
	}

	private static void checkMinutes(StaffAttendanceStatus status, Integer minutesLate) {
		if (minutesLate != null && (status != StaffAttendanceStatus.LATE || minutesLate < 1 || minutesLate > 600)) {
			throw ApiException.badRequest("staff_attendance.invalid_minutes_late");
		}
	}

	private static String clean(String note) {
		return (note == null || note.isBlank()) ? null : note.strip();
	}

	private record StaffRow(UUID id, UUID campusId, boolean active) {
	}

	private record Mark(StaffRow staff, StaffAttendanceStatus status, Integer minutesLate, String note) {
	}

	private record RecordRow(UUID id, UUID staffId, StaffAttendanceStatus status, Integer minutesLate, String note, int version) {
	}

}
