package com.nemaz.school.academic.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess;
import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.academic.ClassCreated;
import com.nemaz.school.academic.ClassMembershipChanged;
import com.nemaz.school.academic.ClassMembershipChanged.Change;
import com.nemaz.school.academic.ClassMembershipChanged.MemberType;
import com.nemaz.school.academic.ClassMembershipView.Kind;
import com.nemaz.school.academic.ClassMembershipView.TeacherRole;
import com.nemaz.school.academic.SchoolPeople;
import com.nemaz.school.academic.SqlFragment;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.AccessControl;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;
import com.nemaz.school.tenancy.TenantDirectory;
import com.nemaz.school.tenancy.TenantDirectory.CampusRef;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Classes of a school: create, list (only what the caller may see), read, change, archive, restore, delete.
 * <ul>
 * <li><b>Create:</b> {@code class.manage} covering the class's campus (or the school), or an ACTIVE TEACHER of
 * the school while the setting {@code classes.teachers_can_create} is on; an ACTIVE teacher who creates a class
 * becomes its LEAD. The year defaults to the current one and cannot be CLOSED.</li>
 * <li><b>List:</b> one SQL predicate ({@link ClassAccess#visibleClasses}) so lists and single reads agree;
 * keyset by name then id.</li>
 * <li><b>Archive:</b> sets ARCHIVED and {@code archived_at}; memberships stay ACTIVE (nothing is ended
 * automatically: history and the class feed stay readable, and rosters cannot change while archived).</li>
 * <li><b>Delete:</b> only a class that never had students and has no teacher besides its first (creating)
 * lead.</li>
 * </ul>
 */
@Service
@Transactional
public class ClassService {

	static final String TEACHERS_CAN_CREATE = "classes.teachers_can_create";

	private final JdbcClient jdbc;

	private final ClassAccess access;

	private final ClassLookups lookups;

	private final AccessControl accessControl;

	private final SchoolGate gate;

	private final SchoolPeople people;

	private final TenantDirectory directory;

	private final SettingsService settings;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	private final RosterWrites roster;

	private final Clock clock;

	ClassService(JdbcClient jdbc, ClassAccess access, ClassLookups lookups, AccessControl accessControl, SchoolGate gate,
			SchoolPeople people, TenantDirectory directory, SettingsService settings, AuditLog audit,
			ApplicationEventPublisher events, RosterWrites roster, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.lookups = lookups;
		this.accessControl = accessControl;
		this.gate = gate;
		this.people = people;
		this.directory = directory;
		this.settings = settings;
		this.audit = audit;
		this.events = events;
		this.roster = roster;
		this.clock = clock;
	}

	public ClassView create(CurrentUser user, UUID schoolId, CreateClass r) {
		UUID campusId = validCampus(schoolId, r.campusId());
		boolean manager = this.accessControl.can(user, Permissions.CLASS_MANAGE,
				(campusId != null) ? AccessScope.campus(schoolId, campusId) : AccessScope.school(schoolId));
		boolean teacher = this.people.isActiveTeacher(schoolId, user.userId());
		if (!manager) {
			this.gate.requireVisible(user, schoolId);
			if (!(teacher && this.settings.getBoolean(TEACHERS_CAN_CREATE, SettingScope.school(schoolId)))) {
				throw ApiException.forbidden("class.create_not_allowed");
			}
		}
		UUID yearId = (r.academicYearId() != null) ? r.academicYearId() : this.jdbc
			.sql("select id from academic_years where school_id = :s and is_current")
			.param("s", schoolId)
			.query(UUID.class)
			.optional()
			.orElseThrow(() -> ApiException.conflict("academic.no_current_year"));
		String yearStatus = this.jdbc.sql("select status from academic_years where id = :y and school_id = :s")
			.param("y", yearId)
			.param("s", schoolId)
			.query(String.class)
			.optional()
			.orElseThrow(() -> ApiException.notFound("academic.year_not_found"));
		if ("CLOSED".equals(yearStatus)) {
			throw ApiException.conflict("academic.year_closed");
		}
		if (r.gradeLevelId() != null) {
			requireOwned("grade_levels", r.gradeLevelId(), schoolId, "academic.grade_not_found");
		}
		Set<UUID> subjectIds = new LinkedHashSet<>(orEmpty(r.subjectIds()));
		for (UUID subjectId : subjectIds) {
			requireOwned("subjects", subjectId, schoolId, "academic.subject_not_found");
		}
		Set<UUID> studentIds = new LinkedHashSet<>(orEmpty(r.studentIds()));
		if (!studentIds.isEmpty() && this.people.activeStudents(schoolId, studentIds).size() != studentIds.size()) {
			throw ApiException.conflict("class.student_not_enrolled");
		}
		UUID id = Ids.newId();
		this.jdbc.sql("""
				insert into classes (id, school_id, campus_id, academic_year_id, grade_level_id, name, class_type, icon, color,
				                     created_by)
				values (:id, :s, :campus, :year, :grade, :name, :type, :icon, :color, :by)""")
			.param("id", id)
			.param("s", schoolId)
			.param("campus", campusId)
			.param("year", yearId)
			.param("grade", r.gradeLevelId())
			.param("name", r.name().trim())
			.param("type", (r.classType() != null) ? r.classType().name() : ClassType.HOMEROOM.name())
			.param("icon", blankToNull(r.icon()))
			.param("color", blankToNull(r.color()))
			.param("by", user.userId())
			.update();
		for (UUID subjectId : subjectIds) {
			this.jdbc.sql("insert into class_subjects (class_id, subject_id, school_id) values (:c, :sub, :s)")
				.param("c", id)
				.param("sub", subjectId)
				.param("s", schoolId)
				.update();
		}
		this.events.publishEvent(new ClassCreated(id, schoolId, campusId, yearId, user.userId()));
		if (teacher) {
			this.jdbc.sql("""
					insert into class_teachers (id, class_id, school_id, teacher_id, role, status, joined_at, added_by)
					values (:id, :c, :s, :t, 'LEAD', 'ACTIVE', :now, :by)""")
				.param("id", Ids.newId())
				.param("c", id)
				.param("s", schoolId)
				.param("t", user.userId())
				.param("now", java.sql.Timestamp.from(this.clock.instant()))
				.param("by", user.userId())
				.update();
			this.events.publishEvent(new ClassMembershipChanged(id, schoolId, user.userId(), MemberType.TEACHER,
					Change.JOINED, "LEAD", null, user.userId()));
		}
		for (UUID studentId : studentIds) {
			this.roster.addStudent(schoolId, id, studentId, user.userId());
		}
		this.audit.record(AuditEntry.of("class.create", AuditOutcome.SUCCESS)
			.target("class", id)
			.school(schoolId)
			.metadata("name", r.name().trim())
			.metadata("students", studentIds.size()));
		return get(user, schoolId, id);
	}

	@Transactional(readOnly = true)
	public CursorPage<ClassView> list(CurrentUser user, UUID schoolId, ClassFilter f, String cursor, Integer limit) {
		this.gate.requireVisible(user, schoolId);
		int size = Cursors.clampLimit(limit);
		SqlFragment visible = this.access.visibleClasses(user, "c");
		StringBuilder where = new StringBuilder("c.school_id = :school and (" + visible.sql() + ")");
		Map<String, Object> params = new HashMap<>(visible.params());
		params.put("school", schoolId);
		params.put("me", user.userId());
		if (f.academicYearId() != null) {
			where.append(" and c.academic_year_id = :year");
			params.put("year", f.academicYearId());
		}
		if (f.gradeLevelId() != null) {
			where.append(" and c.grade_level_id = :grade");
			params.put("grade", f.gradeLevelId());
		}
		if (f.campusId() != null) {
			where.append(" and c.campus_id = :campus");
			params.put("campus", f.campusId());
		}
		if (f.status() != null) {
			where.append(" and c.status = :status");
			params.put("status", f.status().name());
		}
		if (f.classType() != null) {
			where.append(" and c.class_type = :ctype");
			params.put("ctype", f.classType().name());
		}
		if (f.mine()) {
			where.append(" and (exists (select 1 from class_teachers m where m.class_id = c.id and m.teacher_id = :me"
					+ " and m.status = 'ACTIVE') or exists (select 1 from class_students m2 where m2.class_id = c.id"
					+ " and m2.student_id = :me and m2.status = 'ACTIVE'))");
		}
		if (f.q() != null && !f.q().isBlank()) {
			where.append(" and c.name ilike :q escape '\\'");
			params.put("q", "%" + f.q().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
		}
		if (cursor != null) {
			List<String> parts = Cursors.decode(cursor, 2);
			where.append(" and (c.name, c.id) > (:cname, :cid)");
			params.put("cname", parts.get(0));
			try {
				params.put("cid", UUID.fromString(parts.get(1)));
			}
			catch (IllegalArgumentException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		List<ClassView> rows = this.jdbc.sql(SELECT + " where " + where + " order by c.name, c.id limit " + (size + 1))
			.params(params)
			.query(ClassService::view)
			.list();
		return CursorPage.fromOverfetch(rows, size, (c) -> Cursors.encode(c.name(), c.id().toString()));
	}

	@Transactional(readOnly = true)
	public ClassView get(CurrentUser user, UUID schoolId, UUID classId) {
		this.lookups.viewable(user, schoolId, classId);
		return this.jdbc.sql(SELECT + " where c.id = :id")
			.param("id", classId)
			.param("me", user.userId())
			.query(ClassService::view)
			.single();
	}

	public ClassView update(CurrentUser user, UUID schoolId, UUID classId, UpdateClass r) {
		ClassRef ref = this.lookups.leadable(user, schoolId, classId);
		this.lookups.lockActive(ref);
		ClassView before = get(user, schoolId, classId);
		boolean manager = this.access.canManage(user, classId);
		if ((r.campusId() != null || r.academicYearId() != null) && !manager) {
			throw ApiException.forbidden("class.access_denied");
		}
		UUID campusId = (r.campusId() != null) ? validCampus(schoolId, r.campusId()) : before.campusId();
		if (campusId != null && r.campusId() != null && !this.accessControl.can(user, Permissions.CLASS_MANAGE,
				AccessScope.campus(schoolId, campusId))) {
			throw ApiException.forbidden("class.access_denied");
		}
		UUID yearId = (r.academicYearId() != null) ? r.academicYearId() : before.academicYearId();
		if (r.academicYearId() != null) {
			String status = this.jdbc.sql("select status from academic_years where id = :y and school_id = :s")
				.param("y", yearId)
				.param("s", schoolId)
				.query(String.class)
				.optional()
				.orElseThrow(() -> ApiException.notFound("academic.year_not_found"));
			if ("CLOSED".equals(status)) {
				throw ApiException.conflict("academic.year_closed");
			}
		}
		UUID gradeId = (r.gradeLevelId() != null) ? r.gradeLevelId() : before.gradeLevelId();
		if (r.gradeLevelId() != null) {
			requireOwned("grade_levels", gradeId, schoolId, "academic.grade_not_found");
		}
		this.jdbc.sql("""
				update classes set name = :name, campus_id = :campus, academic_year_id = :year, grade_level_id = :grade,
				       class_type = :type, icon = :icon, color = :color, updated_at = now(), version = version + 1
				where id = :id""")
			.param("name", (r.name() != null) ? r.name().trim() : before.name())
			.param("campus", campusId)
			.param("year", yearId)
			.param("grade", gradeId)
			.param("type", ((r.classType() != null) ? r.classType() : before.classType()).name())
			.param("icon", (r.icon() != null) ? blankToNull(r.icon()) : before.icon())
			.param("color", (r.color() != null) ? blankToNull(r.color()) : before.color())
			.param("id", classId)
			.update();
		if (r.subjectIds() != null) {
			Set<UUID> subjectIds = new LinkedHashSet<>(r.subjectIds());
			for (UUID subjectId : subjectIds) {
				requireOwned("subjects", subjectId, schoolId, "academic.subject_not_found");
			}
			this.jdbc.sql("delete from class_subjects where class_id = :c").param("c", classId).update();
			for (UUID subjectId : subjectIds) {
				this.jdbc.sql("insert into class_subjects (class_id, subject_id, school_id) values (:c, :sub, :s)")
					.param("c", classId)
					.param("sub", subjectId)
					.param("s", schoolId)
					.update();
			}
		}
		ClassView after = get(user, schoolId, classId);
		this.audit.record(AuditEntry.of("class.update", AuditOutcome.SUCCESS)
			.target("class", classId)
			.school(schoolId)
			.changes(before, after));
		return after;
	}

	public ClassView archive(CurrentUser user, UUID schoolId, UUID classId) {
		this.lookups.leadable(user, schoolId, classId);
		this.jdbc.sql("""
				update classes set status = 'ARCHIVED', archived_at = coalesce(archived_at, :now), updated_at = now(),
				       version = version + 1 where id = :id and status = 'ACTIVE'""")
			.param("now", java.sql.Timestamp.from(this.clock.instant()))
			.param("id", classId)
			.update();
		this.audit.record(AuditEntry.of("class.archive", AuditOutcome.SUCCESS).target("class", classId).school(schoolId));
		return get(user, schoolId, classId);
	}

	public ClassView restore(CurrentUser user, UUID schoolId, UUID classId) {
		this.lookups.leadable(user, schoolId, classId);
		this.jdbc.sql("""
				update classes set status = 'ACTIVE', archived_at = null, updated_at = now(), version = version + 1
				where id = :id and status = 'ARCHIVED'""").param("id", classId).update();
		this.audit.record(AuditEntry.of("class.restore", AuditOutcome.SUCCESS).target("class", classId).school(schoolId));
		return get(user, schoolId, classId);
	}

	public void delete(CurrentUser user, UUID schoolId, UUID classId) {
		this.lookups.leadable(user, schoolId, classId);
		this.jdbc.sql("select id from classes where id = :id for update").param("id", classId).query(UUID.class).single();
		boolean hadStudents = this.jdbc.sql("select exists (select 1 from class_students where class_id = :c)")
			.param("c", classId)
			.query(Boolean.class)
			.single();
		int teachers = this.jdbc.sql("select count(distinct teacher_id) from class_teachers where class_id = :c")
			.param("c", classId)
			.query(Integer.class)
			.single();
		boolean hadCodes = this.jdbc.sql("select exists (select 1 from student_login_codes where class_id = :c)")
			.param("c", classId)
			.query(Boolean.class)
			.single();
		if (hadStudents || teachers > 1 || hadCodes) {
			throw ApiException.conflict("class.not_empty");
		}
		try {
			this.jdbc.sql("delete from student_group_members where group_id in (select id from student_groups where class_id = :c)")
				.param("c", classId)
				.update();
			this.jdbc.sql("delete from student_groups where class_id = :c").param("c", classId).update();
			this.jdbc.sql("delete from class_teachers where class_id = :c").param("c", classId).update();
			this.jdbc.sql("delete from class_subjects where class_id = :c").param("c", classId).update();
			this.jdbc.sql("delete from classes where id = :c").param("c", classId).update();
		}
		catch (DataIntegrityViolationException ex) {
			// Something else (a later module) references the class.
			throw ApiException.conflict("class.not_empty");
		}
		this.audit.record(AuditEntry.of("class.delete", AuditOutcome.SUCCESS).target("class", classId).school(schoolId));
	}

	// --- helpers ------------------------------------------------------------------------

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

	private void requireOwned(String table, UUID id, UUID schoolId, String notFoundCode) {
		boolean exists = this.jdbc.sql("select exists (select 1 from " + table + " where id = :id and school_id = :s)")
			.param("id", id)
			.param("s", schoolId)
			.query(Boolean.class)
			.single();
		if (!exists) {
			throw ApiException.notFound(notFoundCode);
		}
	}

	private static <T> List<T> orEmpty(List<T> values) {
		return (values != null) ? values : List.of();
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.trim();
	}

	private static final String SELECT = """
			select c.id, c.school_id, c.name, c.class_type, c.icon, c.color, c.status, c.archived_at, c.campus_id,
			       c.academic_year_id, ay.name as year_name, c.grade_level_id, gl.name as grade_name,
			       (select coalesce(array_agg(cs.subject_id order by cs.subject_id), '{}') from class_subjects cs
			         where cs.class_id = c.id) as subject_ids,
			       (select count(*) from class_students s where s.class_id = c.id and s.status = 'ACTIVE') as student_count,
			       (select count(*) from class_teachers t where t.class_id = c.id and t.status = 'ACTIVE') as teacher_count,
			       (select t2.role from class_teachers t2 where t2.class_id = c.id and t2.teacher_id = :me
			         and t2.status = 'ACTIVE' order by case t2.role when 'LEAD' then 0 else 1 end limit 1) as my_teacher_role,
			       exists (select 1 from class_students s2 where s2.class_id = c.id and s2.student_id = :me
			         and s2.status = 'ACTIVE') as i_am_student
			from classes c
			join academic_years ay on ay.id = c.academic_year_id
			left join grade_levels gl on gl.id = c.grade_level_id""";

	private static ClassView view(ResultSet rs, int row) throws SQLException {
		java.sql.Array subjects = rs.getArray("subject_ids");
		List<UUID> subjectIds = new ArrayList<>();
		if (subjects != null) {
			for (Object o : (Object[]) subjects.getArray()) {
				subjectIds.add((UUID) o);
			}
		}
		String teacherRole = rs.getString("my_teacher_role");
		Kind mine = (teacherRole != null) ? Kind.TEACHER : rs.getBoolean("i_am_student") ? Kind.STUDENT : null;
		return new ClassView(Rows.uuid(rs, "id"), Rows.uuid(rs, "school_id"), rs.getString("name"),
				ClassType.valueOf(rs.getString("class_type")), rs.getString("icon"), rs.getString("color"),
				ClassStatus.valueOf(rs.getString("status")), Rows.instant(rs, "archived_at"), Rows.uuid(rs, "campus_id"),
				Rows.uuid(rs, "academic_year_id"), rs.getString("year_name"), Rows.uuid(rs, "grade_level_id"),
				rs.getString("grade_name"), subjectIds, rs.getInt("student_count"), rs.getInt("teacher_count"), mine,
				(teacherRole != null) ? TeacherRole.valueOf(teacherRole) : null);
	}

	public enum ClassType {

		HOMEROOM, SUBJECT, CLUB, OTHER

	}

	public enum ClassStatus {

		ACTIVE, ARCHIVED

	}

	public record CreateClass(String name, UUID academicYearId, UUID gradeLevelId, UUID campusId, ClassType classType,
			String icon, String color, List<UUID> subjectIds, List<UUID> studentIds) {
	}

	public record UpdateClass(String name, UUID academicYearId, UUID gradeLevelId, UUID campusId, ClassType classType,
			String icon, String color, List<UUID> subjectIds) {
	}

	public record ClassFilter(UUID academicYearId, UUID gradeLevelId, UUID campusId, ClassStatus status,
			ClassType classType, boolean mine, String q) {
	}

	public record ClassView(@NotNull UUID id, @NotNull UUID schoolId, @NotNull String name, @NotNull ClassType classType,
			String icon, String color, @NotNull ClassStatus status, Instant archivedAt, UUID campusId,
			@NotNull UUID academicYearId, @NotNull String academicYearName, UUID gradeLevelId, String gradeLevelName,
			@NotNull List<UUID> subjectIds, @NotNull int studentCount, @NotNull int teacherCount,
			@Schema(description = "How the caller belongs to this class; absent for administrators who only manage it") Kind myMembership,
			@Schema(description = "The caller's teacher role when myMembership is TEACHER") TeacherRole myTeacherRole) {
	}

}
