package com.nemaz.school.people.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess;
import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.academic.ClassMemberships;
import com.nemaz.school.academic.ClassMemberships.StudentClass;
import com.nemaz.school.academic.DataAccessLevel;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.persistence.Constraints;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.common.text.PersonNames;
import com.nemaz.school.identity.UserAccounts;
import com.nemaz.school.identity.UserAccounts.ManagedChange;
import com.nemaz.school.identity.UserAccounts.ManagedRequest;
import com.nemaz.school.media.MediaService;
import com.nemaz.school.media.MediaSummary;
import com.nemaz.school.people.StudentEnrolledInSchool;
import com.nemaz.school.people.StudentWithdrawnFromSchool;
import com.nemaz.school.tenancy.FeatureService;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;
import com.nemaz.school.tenancy.TenantDirectory;
import com.nemaz.school.tenancy.TenantDirectory.CampusRef;
import com.nemaz.school.tenancy.TenantDirectory.SchoolRef;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Students of a school: school-managed accounts (R-31) with a profile and an enrollment (R-01).
 * <ul>
 * <li><b>Create:</b> {@code school.students.manage}, or a teacher of the target class creating the student inline
 * into that class. The account is MANAGED (no phone or email), in the school's default language; the school's
 * plan limit {@code max_students} caps ACTIVE students.</li>
 * <li><b>Privacy (DTO split):</b> {@link StudentView} carries name and avatar for everybody who may see the student
 * (classmates, campus administrators) and date of birth, gender, username, number and classes only at
 * {@link DataAccessLevel#FULL}: holders of {@code school.students.manage}, teachers of one of the student's
 * classes and the student. Anyone else gets 404.</li>
 * <li><b>Withdraw:</b> WITHDRAWN with {@code left_on}; class memberships end (LEFT_SCHOOL), login codes are revoked and,
 * when no other enrollment is live, the account is signed out everywhere.</li>
 * <li><b>Password:</b> only while {@code students.password_login.enabled} is on for the school; the account needs a
 * username; the password must be changed by its owner.</li>
 * </ul>
 */
@Service
@Transactional
public class StudentService {

	private final JdbcClient jdbc;

	private final UserAccounts accounts;

	private final ClassAccess classAccess;

	private final StudentAccess access;

	private final ClassMemberships classes;

	private final LoginCodeService loginCodes;

	private final TenantDirectory directory;

	private final SettingsService settings;

	private final FeatureService features;

	private final SchoolGate gate;

	private final MediaService media;

	private final JsonMapper json;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	StudentService(JdbcClient jdbc, UserAccounts accounts, ClassAccess classAccess, StudentAccess access,
			ClassMemberships classes,
			LoginCodeService loginCodes, TenantDirectory directory, SettingsService settings, FeatureService features,
			SchoolGate gate, MediaService media, JsonMapper json, AuditLog audit, ApplicationEventPublisher events,
			Clock clock) {
		this.jdbc = jdbc;
		this.accounts = accounts;
		this.classAccess = classAccess;
		this.access = access;
		this.classes = classes;
		this.loginCodes = loginCodes;
		this.directory = directory;
		this.settings = settings;
		this.features = features;
		this.gate = gate;
		this.media = media;
		this.json = json;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	public StudentView create(CurrentUser actor, UUID schoolId, CreateStudent r) {
		boolean manager = this.gate.can(actor, schoolId, Permissions.SCHOOL_STUDENTS_MANAGE);
		if (!manager) {
			this.gate.requireVisible(actor, schoolId);
		}
		if (r.classId() != null) {
			ClassRef ref = this.classAccess.find(r.classId()).filter((c) -> c.schoolId().equals(schoolId)).orElse(null);
			if (ref == null || (!manager && !this.classAccess.canView(actor, r.classId()))) {
				throw ApiException.notFound("class.not_found");
			}
			if (!manager && !this.classAccess.canTeach(actor, r.classId())) {
				throw ApiException.forbidden("class.access_denied");
			}
		}
		else if (!manager) {
			throw ApiException.forbidden("student.access_denied");
		}
		UUID studentId = provision(actor.userId(), schoolId, r, null);
		return load(actor, schoolId, studentId, DataAccessLevel.FULL);
	}

	/**
	 * Creates a student the caller was already allowed to create (the roster import checked
	 * {@code school.import.manage}); no permission check, {@code actorId} stands for the initiator.
	 */
	public UUID createForImport(UUID actorId, UUID schoolId, CreateStudent r, String externalRef) {
		return provision(actorId, schoolId, r, externalRef);
	}

	private UUID provision(UUID actorId, UUID schoolId, CreateStudent r, String externalRef) {
		SchoolRef school = this.directory.school(schoolId).orElseThrow(() -> ApiException.notFound("school.not_found"));
		if (r.dateOfBirth() != null && r.dateOfBirth().isAfter(LocalDate.now(this.clock))) {
			throw ApiException.badRequest("student.invalid_birth_date");
		}
		UUID campusId = validCampus(schoolId, r.campusId());
		long active = this.jdbc.sql("select count(*) from school_students where school_id = :s and status in ('PENDING', 'ACTIVE')")
			.param("s", schoolId)
			.query(Long.class)
			.single();
		if (this.features.limit("max_students", schoolId).stream().anyMatch((limit) -> active >= limit)) {
			throw ApiException.conflict("student.limit_reached");
		}
		UUID studentId = this.accounts.createManaged(new ManagedRequest(r.givenName(), r.middleName(), r.familyName(),
				r.dateOfBirth(), r.gender(), r.username(), school.defaultLanguageCode()));
		this.jdbc.sql("insert into student_profiles (user_id, avatar_config, name_fold) values (:u, cast(:cfg as jsonb), :fold)")
			.param("u", studentId)
			.param("cfg", configJson(r.avatarConfig()))
			.param("fold", nameFold(r.givenName(), r.middleName(), r.familyName()))
			.update();
		UUID enrollmentId = enroll(schoolId, studentId, campusId, r.studentNumber(), r.admissionOn(), externalRef);
		if (r.classId() != null) {
			this.classes.addStudent(schoolId, r.classId(), studentId, actorId);
		}
		this.events.publishEvent(new StudentEnrolledInSchool(schoolId, studentId, enrollmentId, r.classId(), actorId));
		this.audit.record(AuditEntry.of("student.create", AuditOutcome.SUCCESS)
			.actor(actorId)
			.target("student", studentId)
			.school(schoolId)
			.metadata("enrollmentId", enrollmentId.toString())
			.metadata("classId", (r.classId() != null) ? r.classId().toString() : null));
		return studentId;
	}

	/** Changes a student on behalf of the roster import (absent fields stay); no permission check. */
	public void updateForImport(UUID actorId, UUID schoolId, UUID studentId, UpdateStudent r, String externalRef) {
		Row before = find(schoolId, studentId);
		applyUpdate(schoolId, studentId, before, r, externalRef);
		this.audit.record(AuditEntry.of("student.update", AuditOutcome.SUCCESS)
			.actor(actorId)
			.target("student", studentId)
			.school(schoolId)
			.metadata("fields", changedFields(r))
			.metadata("by", "import"));
	}

	@Transactional(readOnly = true)
	public CursorPage<StudentView> list(CurrentUser user, UUID schoolId, StudentFilter f, String cursor, Integer limit) {
		boolean manager = this.gate.can(user, schoolId, Permissions.SCHOOL_STUDENTS_MANAGE);
		Set<UUID> ids = null;
		if (!manager) {
			this.gate.requireVisible(user, schoolId);
			boolean teacher = this.jdbc.sql("""
					select exists (select 1 from school_staff where school_id = :s and user_id = :u and status = 'ACTIVE'
					               and staff_type = 'TEACHER')""")
				.param("s", schoolId)
				.param("u", user.userId())
				.query(Boolean.class)
				.single();
			if (!teacher) {
				throw ApiException.forbidden("student.access_denied");
			}
			ids = new HashSet<>(this.classes.activeStudentIdsOfTeacher(user.userId()));
		}
		if (f.classId() != null) {
			if (!manager && !this.classAccess.canTeach(user, f.classId())) {
				throw ApiException.forbidden("class.access_denied");
			}
			ids = intersect(ids, this.classes.activeStudentIds(f.classId()));
		}
		if (f.gradeLevelId() != null) {
			ids = intersect(ids, this.classes.activeStudentIdsInGrade(schoolId, f.gradeLevelId()));
		}
		if (ids != null && ids.isEmpty()) {
			return new CursorPage<>(List.of(), null);
		}
		int size = Cursors.clampLimit(limit);
		StringBuilder where = new StringBuilder("ss.school_id = :s");
		Map<String, Object> params = new HashMap<>();
		params.put("s", schoolId);
		StudentStatus status = (f.status() != null) ? f.status() : StudentStatus.ACTIVE;
		where.append(" and ss.status = :status");
		params.put("status", status.name());
		if (ids != null) {
			where.append(" and ss.student_id in (:ids)");
			params.put("ids", ids);
		}
		if (f.campusId() != null) {
			where.append(" and ss.campus_id = :campus");
			params.put("campus", f.campusId());
		}
		if (f.q() != null && !f.q().isBlank()) {
			where.append(" and u.display_name ilike :q escape '\\'");
			params.put("q", "%" + f.q().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
		}
		if (cursor != null) {
			List<String> parts = Cursors.decode(cursor, 2);
			where.append(" and (u.display_name, ss.id) > (:cname, :cid)");
			params.put("cname", parts.get(0));
			try {
				params.put("cid", UUID.fromString(parts.get(1)));
			}
			catch (IllegalArgumentException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		// Reads users and student_profiles (identity's and this module's tables) for sorting and name search.
		List<Row> rows = this.jdbc.sql(SELECT + " where " + where + " order by u.display_name, ss.id limit " + (size + 1))
			.params(params)
			.query(this::row)
			.list();
		CursorPage<Row> page = CursorPage.fromOverfetch(rows, size, (row) -> Cursors.encode(row.displayName(), row.enrollmentId().toString()));
		return new CursorPage<>(views(page.items(), (row) -> DataAccessLevel.FULL), page.nextCursor());
	}

	/**
	 * Reading another person's full record (date of birth, gender, username) is audited (spec section 131). A guardian
	 * reads their own child's full record through the guardian link without being a member of the school (M5).
	 */
	public StudentView get(CurrentUser user, UUID schoolId, UUID studentId) {
		DataAccessLevel level = this.access.level(user, schoolId, studentId);
		if (level == null) {
			// Strangers learn nothing: an invisible school is 404 school.not_found, then the student 404.
			this.gate.requireVisible(user, schoolId);
			throw ApiException.notFound("student.not_found");
		}
		StudentView view = load(user, schoolId, studentId, level);
		if (level == DataAccessLevel.FULL && !user.userId().equals(studentId)) {
			this.audit.record(AuditEntry.of("student.read", AuditOutcome.SUCCESS).target("student", studentId).school(schoolId));
		}
		return view;
	}

	/** Managers, teachers of the student and the student change details; the student alone may only change the avatar. */
	public StudentView update(CurrentUser user, UUID schoolId, UUID studentId, UpdateStudent r) {
		boolean manager = this.gate.can(user, schoolId, Permissions.SCHOOL_STUDENTS_MANAGE);
		boolean self = user.userId().equals(studentId);
		if (!manager) {
			this.gate.requireVisible(user, schoolId);
			if (!self) {
				throw (this.access.level(user, schoolId, studentId) != null) ? ApiException.forbidden("student.access_denied")
						: ApiException.notFound("student.not_found");
			}
			if (r.givenName() != null || r.middleName() != null || r.familyName() != null || r.dateOfBirth() != null
					|| r.gender() != null || r.studentNumber() != null || r.campusId() != null || r.username() != null
					|| r.admissionOn() != null) {
				throw ApiException.forbidden("student.access_denied");
			}
		}
		Row before = find(schoolId, studentId);
		applyUpdate(schoolId, studentId, before, r, null);
		this.audit.record(AuditEntry.of("student.update", AuditOutcome.SUCCESS)
			.target("student", studentId)
			.school(schoolId)
			.metadata("fields", changedFields(r))
			.metadata("by", self && !manager ? "self" : "staff"));
		return load(user, schoolId, studentId, DataAccessLevel.FULL);
	}

	public StudentView withdraw(CurrentUser actor, UUID schoolId, UUID studentId, String reason) {
		this.gate.require(actor, schoolId, Permissions.SCHOOL_STUDENTS_MANAGE);
		Row row = this.jdbc.sql(SELECT + " where ss.school_id = :s and ss.student_id = :st and ss.status in ('PENDING', 'ACTIVE') for update of ss")
			.param("s", schoolId)
			.param("st", studentId)
			.query(this::row)
			.optional()
			.orElseThrow(() -> this.jdbc.sql("select exists (select 1 from school_students where school_id = :s and student_id = :st)")
				.param("s", schoolId)
				.param("st", studentId)
				.query(Boolean.class)
				.single() ? ApiException.conflict("student.not_enrolled") : ApiException.notFound("student.not_found"));
		this.jdbc.sql("""
				update school_students set status = 'WITHDRAWN', left_on = :today, left_reason = :reason, updated_at = now(),
				       version = version + 1 where id = :id""")
			.param("today", LocalDate.now(this.clock))
			.param("reason", blankToNull(reason))
			.param("id", row.enrollmentId())
			.update();
		int memberships = this.classes.endStudentMemberships(schoolId, studentId, "LEFT_SCHOOL", actor.userId());
		int codes = this.loginCodes.revokeAllForWithdrawal(schoolId, studentId, actor.userId());
		boolean otherEnrollment = this.jdbc.sql("select exists (select 1 from school_students where student_id = :st and status in ('PENDING', 'ACTIVE'))")
			.param("st", studentId)
			.query(Boolean.class)
			.single();
		if (!otherEnrollment) {
			this.accounts.signOutEverywhere(studentId);
		}
		this.events.publishEvent(new StudentWithdrawnFromSchool(schoolId, studentId, row.enrollmentId(), actor.userId()));
		this.audit.record(AuditEntry.of("student.withdraw", AuditOutcome.SUCCESS)
			.target("student", studentId)
			.school(schoolId)
			.metadata("classMembershipsEnded", memberships)
			.metadata("loginCodesRevoked", codes));
		return load(actor, schoolId, studentId, DataAccessLevel.FULL);
	}

	/** Sets an initial password (managers, or a teacher of the student) when the school allows password sign-in. */
	public void setPassword(CurrentUser actor, UUID schoolId, UUID studentId, String password) {
		this.gate.requireVisible(actor, schoolId);
		DataAccessLevel level = this.access.level(actor, schoolId, studentId);
		boolean allowed = this.access.mayAdminister(actor, schoolId, studentId);
		if (level == null) {
			throw ApiException.notFound("student.not_found");
		}
		if (!allowed) {
			throw ApiException.forbidden("student.access_denied");
		}
		if (!this.settings.getBoolean("students.password_login.enabled", SettingScope.school(schoolId))) {
			throw ApiException.conflict("student.password_login_disabled");
		}
		Row row = find(schoolId, studentId);
		if (row.status() != StudentStatus.ACTIVE) {
			throw ApiException.conflict("student.not_enrolled");
		}
		this.accounts.setInitialPassword(studentId, password);
		this.audit.record(AuditEntry.of("student.password.set", AuditOutcome.SUCCESS).target("student", studentId).school(schoolId));
	}

	private void applyUpdate(UUID schoolId, UUID studentId, Row before, UpdateStudent r, String externalRef) {
		if (r.dateOfBirth() != null && r.dateOfBirth().isAfter(LocalDate.now(this.clock))) {
			throw ApiException.badRequest("student.invalid_birth_date");
		}
		this.accounts.updateManaged(studentId, new ManagedChange(r.givenName(), r.middleName(), r.familyName(),
				r.dateOfBirth(), r.gender(), r.username()));
		if (r.avatarConfig() != null) {
			this.jdbc.sql("update student_profiles set avatar_config = cast(:cfg as jsonb) where user_id = :u")
				.param("cfg", configJson(r.avatarConfig()))
				.param("u", studentId)
				.update();
		}
		if (r.givenName() != null || r.middleName() != null || r.familyName() != null) {
			refreshNameFold(studentId);
		}
		UUID campus = (r.campusId() != null) ? validCampus(schoolId, r.campusId()) : before.campusId();
		try {
			this.jdbc.sql("""
					update school_students set campus_id = :campus, student_number = :number, admission_on = :admission,
					       external_ref = coalesce(:ext, external_ref), updated_at = now(), version = version + 1
					where id = :id""")
				.param("campus", campus)
				.param("number", (r.studentNumber() != null) ? blankToNull(r.studentNumber()) : before.studentNumber())
				.param("admission", (r.admissionOn() != null) ? r.admissionOn() : before.admissionOn())
				.param("ext", blankToNull(externalRef))
				.param("id", before.enrollmentId())
				.update();
		}
		catch (DataIntegrityViolationException ex) {
			throw numberTaken(ex);
		}
	}

	/** Recomputes the comparison key of the student's name from the account (called after a name change). */
	void refreshNameFold(UUID studentId) {
		this.jdbc.sql("""
				update student_profiles sp set name_fold = :fold where sp.user_id = :u""")
			.param("fold", this.jdbc.sql("select given_name, middle_name, family_name from users where id = :u")
				.param("u", studentId)
				.query((rs, i) -> nameFold(rs.getString(1), rs.getString(2), rs.getString(3)))
				.single())
			.param("u", studentId)
			.update();
	}

	static String nameFold(String given, String middle, String family) {
		String fold = PersonNames.fold(given, middle, family);
		return fold.isEmpty() ? null : fold;
	}

	// --- helpers ------------------------------------------------------------------------

	private StudentView load(CurrentUser user, UUID schoolId, UUID studentId, DataAccessLevel level) {
		if (level == null) {
			throw ApiException.notFound("student.not_found");
		}
		return views(List.of(find(schoolId, studentId)), (row) -> level).getFirst();
	}

	private Row find(UUID schoolId, UUID studentId) {
		return this.jdbc.sql(SELECT + " where ss.school_id = :s and ss.student_id = :st"
				+ " order by (ss.status in ('PENDING', 'ACTIVE')) desc, ss.created_at desc, ss.id desc limit 1")
			.param("s", schoolId)
			.param("st", studentId)
			.query(this::row)
			.optional()
			.orElseThrow(() -> ApiException.notFound("student.not_found"));
	}

	private List<StudentView> views(List<Row> rows, java.util.function.Function<Row, DataAccessLevel> level) {
		Map<UUID, MediaSummary> avatars = this.media.summaries(rows.stream().map(Row::avatarMediaId).filter(java.util.Objects::nonNull).toList());
		Map<UUID, List<StudentClass>> classOf = this.classes.activeClassesOf(rows.stream().map(Row::studentId).toList());
		List<StudentView> views = new ArrayList<>();
		for (Row row : rows) {
			MediaSummary avatar = (row.avatarMediaId() != null) ? avatars.get(row.avatarMediaId()) : null;
			if (level.apply(row) == DataAccessLevel.FULL) {
				views.add(new StudentView(row.studentId(), DataAccessLevel.FULL, row.displayName(), avatar, row.avatarConfig(),
						row.enrollmentId(), row.status(), row.givenName(), row.middleName(), row.familyName(),
						row.dateOfBirth(), row.gender(), row.username(), row.studentNumber(), row.campusId(),
						row.admissionOn(), row.leftOn(),
						classOf.getOrDefault(row.studentId(), List.of())
							.stream()
							.map((c) -> new StudentClassRef(c.classId(), c.name()))
							.toList()));
			}
			else {
				views.add(new StudentView(row.studentId(), DataAccessLevel.BASIC, row.displayName(), avatar,
						row.avatarConfig(), null, null, null, null, null, null, null, null, null, null, null, null, null));
			}
		}
		return views;
	}

	private UUID enroll(UUID schoolId, UUID studentId, UUID campusId, String number, LocalDate admissionOn,
			String externalRef) {
		UUID id = Ids.newId();
		try {
			this.jdbc.sql("""
					insert into school_students (id, school_id, student_id, campus_id, student_number, external_ref, admission_on,
					                             status)
					values (:id, :s, :st, :campus, :number, :ext, :admission, 'ACTIVE')""")
				.param("id", id)
				.param("s", schoolId)
				.param("st", studentId)
				.param("campus", campusId)
				.param("number", blankToNull(number))
				.param("ext", blankToNull(externalRef))
				.param("admission", (admissionOn != null) ? admissionOn : LocalDate.now(this.clock))
				.update();
		}
		catch (DataIntegrityViolationException ex) {
			throw numberTaken(ex);
		}
		return id;
	}

	private static ApiException numberTaken(DataIntegrityViolationException ex) {
		if (Constraints.violated(ex, "ux_school_student_number")) {
			return ApiException.conflict("student.number_taken");
		}
		throw ex;
	}

	private UUID validCampus(UUID schoolId, UUID campusId) {
		if (campusId == null) {
			return null;
		}
		CampusRef campus = this.directory.campus(campusId).filter((c) -> c.schoolId().equals(schoolId)).orElse(null);
		if (campus == null) {
			throw ApiException.notFound("campus.not_found");
		}
		if (!"ACTIVE".equals(campus.status())) {
			throw ApiException.conflict("campus.archived");
		}
		return campusId;
	}

	private String configJson(Map<String, Object> config) {
		return (config == null) ? null : this.json.writeValueAsString(config);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> configMap(String json) {
		return (json == null) ? null : this.json.readValue(json, Map.class);
	}

	private static Set<UUID> intersect(Set<UUID> current, Set<UUID> other) {
		if (current == null) {
			return new HashSet<>(other);
		}
		Set<UUID> result = new HashSet<>(current);
		result.retainAll(other);
		return result;
	}

	private static List<String> changedFields(UpdateStudent r) {
		List<String> fields = new ArrayList<>();
		if (r.givenName() != null || r.middleName() != null || r.familyName() != null) {
			fields.add("name");
		}
		if (r.dateOfBirth() != null) {
			fields.add("dateOfBirth");
		}
		if (r.gender() != null) {
			fields.add("gender");
		}
		if (r.username() != null) {
			fields.add("username");
		}
		if (r.studentNumber() != null) {
			fields.add("studentNumber");
		}
		if (r.campusId() != null) {
			fields.add("campusId");
		}
		if (r.avatarConfig() != null) {
			fields.add("avatarConfig");
		}
		if (r.admissionOn() != null) {
			fields.add("admissionOn");
		}
		return fields;
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.trim();
	}

	private static final String SELECT = """
			select ss.id as enrollment_id, ss.student_id, ss.status, ss.student_number, ss.campus_id, ss.admission_on, ss.left_on,
			       u.display_name, u.given_name, u.middle_name, u.family_name, u.date_of_birth, u.gender, u.username,
			       u.avatar_media_id, sp.avatar_config::text as avatar_config
			from school_students ss
			join users u on u.id = ss.student_id
			join student_profiles sp on sp.user_id = ss.student_id""";

	private Row row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		return new Row(Rows.uuid(rs, "enrollment_id"), Rows.uuid(rs, "student_id"),
				StudentStatus.valueOf(rs.getString("status")), rs.getString("student_number"), Rows.uuid(rs, "campus_id"),
				Rows.date(rs, "admission_on"), Rows.date(rs, "left_on"), rs.getString("display_name"),
				rs.getString("given_name"), rs.getString("middle_name"), rs.getString("family_name"),
				Rows.date(rs, "date_of_birth"), rs.getString("gender"), rs.getString("username"),
				Rows.uuid(rs, "avatar_media_id"), configMap(rs.getString("avatar_config")));
	}

	private record Row(UUID enrollmentId, UUID studentId, StudentStatus status, String studentNumber, UUID campusId,
			LocalDate admissionOn, LocalDate leftOn, String displayName, String givenName, String middleName,
			String familyName, LocalDate dateOfBirth, String gender, String username, UUID avatarMediaId,
			Map<String, Object> avatarConfig) {
	}

	public enum StudentStatus {

		/** SUSPENDED: a school-level moderation decision (M13): reversible, the student keeps their data but has no access. */
		PENDING, ACTIVE, SUSPENDED, TRANSFERRED, GRADUATED, WITHDRAWN

	}

	public record CreateStudent(String givenName, String middleName, String familyName, LocalDate dateOfBirth,
			String gender, String studentNumber, UUID campusId, String username, Map<String, Object> avatarConfig,
			UUID classId, LocalDate admissionOn) {
	}

	public record UpdateStudent(String givenName, String middleName, String familyName, LocalDate dateOfBirth,
			String gender, String studentNumber, UUID campusId, String username, Map<String, Object> avatarConfig,
			LocalDate admissionOn) {
	}

	public record StudentFilter(UUID classId, UUID gradeLevelId, UUID campusId, StudentStatus status, String q) {
	}

	public record StudentClassRef(@NotNull UUID id, @NotNull String name) {
	}

	/**
	 * A student as the caller may see them. BASIC (name and avatar) is what classmates and campus administrators get;
	 * FULL adds the fields marked below.
	 */
	public record StudentView(@NotNull @Schema(description = "The student's user id; used in every path and roster") UUID id,
			@NotNull DataAccessLevel accessLevel, @NotNull String displayName, MediaSummary avatar,
			@Schema(description = "Avatar builder configuration, client-defined") Map<String, Object> avatarConfig,
			@Schema(description = "FULL only") UUID enrollmentId, @Schema(description = "FULL only") StudentStatus status,
			@Schema(description = "FULL only") String givenName, @Schema(description = "FULL only") String middleName,
			@Schema(description = "FULL only") String familyName, @Schema(description = "FULL only") LocalDate dateOfBirth,
			@Schema(description = "FULL only") String gender, @Schema(description = "FULL only") String username,
			@Schema(description = "FULL only") String studentNumber, @Schema(description = "FULL only") UUID campusId,
			@Schema(description = "FULL only") LocalDate admissionOn, @Schema(description = "FULL only") LocalDate leftOn,
			@Schema(description = "FULL only: ACTIVE classes") List<StudentClassRef> classes) {
	}

}
