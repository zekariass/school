package com.nemaz.school.academic.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.academic.ClassCreated;
import com.nemaz.school.academic.ClassMembershipChanged;
import com.nemaz.school.academic.ClassMembershipChanged.Change;
import com.nemaz.school.academic.ClassMembershipChanged.MemberType;
import com.nemaz.school.academic.SchoolPeople;
import com.nemaz.school.academic.StructureImports;
import com.nemaz.school.academic.application.AcademicStructureService.GradeLevelView;
import com.nemaz.school.academic.application.AcademicStructureService.SubjectView;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.persistence.Rows;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultStructureImports implements StructureImports {

	private final JdbcClient jdbc;

	private final AcademicStructureService structure;

	private final SchoolPeople people;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	DefaultStructureImports(JdbcClient jdbc, AcademicStructureService structure, SchoolPeople people, AuditLog audit,
			ApplicationEventPublisher events, Clock clock) {
		this.jdbc = jdbc;
		this.structure = structure;
		this.people = people;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public List<YearInfo> years(UUID schoolId) {
		return this.jdbc.sql("select id, name, is_current, status from academic_years where school_id = :s order by starts_on desc, id")
			.param("s", schoolId)
			.query((rs, i) -> new YearInfo(Rows.uuid(rs, "id"), rs.getString("name"), rs.getBoolean("is_current"),
					rs.getString("status")))
			.list();
	}

	@Override
	@Transactional(readOnly = true)
	public List<GradeInfo> gradeLevels(UUID schoolId) {
		return this.structure.grades(schoolId)
			.stream()
			.map((g) -> new GradeInfo(g.id(), g.name(), g.code(), g.sequence(), g.active()))
			.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public List<SubjectInfo> subjects(UUID schoolId) {
		return this.structure.subjects(schoolId)
			.stream()
			.map((s) -> new SubjectInfo(s.id(), s.name(), s.code(), s.color(), s.active()))
			.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public List<ClassInfo> classes(UUID schoolId) {
		return this.jdbc.sql("""
				select c.id, c.name, c.academic_year_id, c.grade_level_id, c.campus_id, c.class_type, c.status,
				       (select t.teacher_id from class_teachers t where t.class_id = c.id and t.role = 'LEAD' and t.status = 'ACTIVE'
				        order by t.joined_at, t.id limit 1) as lead_id
				from classes c where c.school_id = :s order by c.name, c.id""")
			.param("s", schoolId)
			.query((rs, i) -> new ClassInfo(Rows.uuid(rs, "id"), rs.getString("name"), Rows.uuid(rs, "academic_year_id"),
					Rows.uuid(rs, "grade_level_id"), Rows.uuid(rs, "campus_id"), rs.getString("class_type"),
					rs.getString("status"), Rows.uuid(rs, "lead_id")))
			.list();
	}

	@Override
	public UUID createGradeLevel(UUID schoolId, UUID actorId, String name, String code, Integer sequence) {
		return this.structure.createGrade(schoolId, name, code, sequence).id();
	}

	@Override
	public void updateGradeLevel(UUID schoolId, UUID actorId, UUID gradeId, String name, String code, Integer sequence) {
		GradeLevelView before = this.structure.grades(schoolId)
			.stream()
			.filter((g) -> g.id().equals(gradeId))
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("academic.grade_not_found"));
		this.structure.updateGrade(schoolId, gradeId, name, code, sequence, before.active());
	}

	@Override
	public UUID createSubject(UUID schoolId, UUID actorId, String name, String code, String color) {
		return this.structure.createSubject(schoolId, name, code, color).id();
	}

	@Override
	public void updateSubject(UUID schoolId, UUID actorId, UUID subjectId, String name, String code, String color) {
		SubjectView before = this.structure.subjects(schoolId)
			.stream()
			.filter((s) -> s.id().equals(subjectId))
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("academic.subject_not_found"));
		this.structure.updateSubject(schoolId, subjectId, name, code, color, before.active());
	}

	@Override
	public UUID createClass(UUID schoolId, UUID actorId, NewClass c) {
		String yearStatus = this.jdbc.sql("select status from academic_years where id = :y and school_id = :s")
			.param("y", c.academicYearId())
			.param("s", schoolId)
			.query(String.class)
			.optional()
			.orElseThrow(() -> ApiException.notFound("academic.year_not_found"));
		if ("CLOSED".equals(yearStatus)) {
			throw ApiException.conflict("academic.year_closed");
		}
		if (c.gradeLevelId() != null) {
			requireOwned("grade_levels", c.gradeLevelId(), schoolId, "academic.grade_not_found");
		}
		UUID id = Ids.newId();
		this.jdbc.sql("""
				insert into classes (id, school_id, campus_id, academic_year_id, grade_level_id, name, class_type, created_by)
				values (:id, :s, :campus, :year, :grade, :name, :type, :by)""")
			.param("id", id)
			.param("s", schoolId)
			.param("campus", c.campusId())
			.param("year", c.academicYearId())
			.param("grade", c.gradeLevelId())
			.param("name", c.name().trim())
			.param("type", (c.classType() != null) ? c.classType() : "HOMEROOM")
			.param("by", actorId)
			.update();
		this.events.publishEvent(new ClassCreated(id, schoolId, c.campusId(), c.academicYearId(), actorId));
		if (c.leadTeacherId() != null) {
			addLead(schoolId, id, c.leadTeacherId(), actorId);
		}
		this.audit.record(AuditEntry.of("class.create", AuditOutcome.SUCCESS)
			.actor(actorId)
			.target("class", id)
			.school(schoolId)
			.metadata("name", c.name().trim())
			.metadata("by", "import"));
		return id;
	}

	@Override
	public boolean updateClass(UUID schoolId, UUID actorId, UUID classId, UUID gradeLevelId, UUID campusId,
			String classType, UUID leadTeacherId) {
		String status = this.jdbc.sql("select status from classes where id = :id and school_id = :s for update")
			.param("id", classId)
			.param("s", schoolId)
			.query(String.class)
			.optional()
			.orElseThrow(() -> ApiException.notFound("class.not_found"));
		if ("ARCHIVED".equals(status)) {
			throw ApiException.conflict("class.archived");
		}
		if (gradeLevelId != null) {
			requireOwned("grade_levels", gradeLevelId, schoolId, "academic.grade_not_found");
		}
		this.jdbc.sql("""
				update classes set grade_level_id = coalesce(:grade, grade_level_id), campus_id = coalesce(:campus, campus_id),
				       class_type = coalesce(:type, class_type), updated_at = now(), version = version + 1
				where id = :id""")
			.param("grade", gradeLevelId)
			.param("campus", campusId)
			.param("type", classType)
			.param("id", classId)
			.update();
		boolean leadSet = false;
		if (leadTeacherId != null) {
			boolean hasLead = this.jdbc.sql("select exists (select 1 from class_teachers where class_id = :c and role = 'LEAD' and status = 'ACTIVE')")
				.param("c", classId)
				.query(Boolean.class)
				.single();
			if (!hasLead) {
				addLead(schoolId, classId, leadTeacherId, actorId);
				leadSet = true;
			}
		}
		this.audit.record(AuditEntry.of("class.update", AuditOutcome.SUCCESS)
			.actor(actorId)
			.target("class", classId)
			.school(schoolId)
			.metadata("leadSet", leadSet)
			.metadata("by", "import"));
		return leadSet;
	}

	private void addLead(UUID schoolId, UUID classId, UUID teacherId, UUID actorId) {
		if (!this.people.isActiveTeacher(schoolId, teacherId)) {
			throw ApiException.conflict("class.teacher_not_staff");
		}
		this.jdbc.sql("""
				insert into class_teachers (id, class_id, school_id, teacher_id, role, status, joined_at, added_by)
				values (:id, :c, :s, :t, 'LEAD', 'ACTIVE', :now, :by)
				on conflict do nothing""")
			.param("id", Ids.newId())
			.param("c", classId)
			.param("s", schoolId)
			.param("t", teacherId)
			.param("now", Timestamp.from(this.clock.instant()))
			.param("by", actorId)
			.update();
		this.events.publishEvent(new ClassMembershipChanged(classId, schoolId, teacherId, MemberType.TEACHER, Change.JOINED,
				"LEAD", null, actorId));
	}

	private void requireOwned(String table, UUID id, UUID schoolId, String code) {
		boolean exists = this.jdbc.sql("select exists (select 1 from " + table + " where id = :id and school_id = :s)")
			.param("id", id)
			.param("s", schoolId)
			.query(Boolean.class)
			.single();
		if (!exists) {
			throw ApiException.notFound(code);
		}
	}

}
