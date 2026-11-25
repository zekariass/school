package com.nemaz.school.people.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.people.PeopleImports;
import com.nemaz.school.people.application.StaffService.AddStaff;
import com.nemaz.school.people.application.StaffService.Provisioned;
import com.nemaz.school.people.application.StaffService.StaffType;
import com.nemaz.school.people.application.StudentService.CreateStudent;
import com.nemaz.school.people.application.StudentService.UpdateStudent;

import com.nemaz.school.tenancy.FeatureService;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultPeopleImports implements PeopleImports {

	private static final String STUDENT = """
			select ss.student_id, ss.id as enrollment_id, ss.status, ss.student_number, ss.campus_id, u.display_name, u.given_name,
			       u.middle_name, u.family_name, u.date_of_birth, u.gender, u.username
			from school_students ss join users u on u.id = ss.student_id""";

	private final JdbcClient jdbc;

	private final StudentService students;

	private final StaffService staff;

	private final FeatureService features;

	DefaultPeopleImports(JdbcClient jdbc, StudentService students, StaffService staff, FeatureService features) {
		this.features = features;
		this.jdbc = jdbc;
		this.students = students;
		this.staff = staff;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<StudentSnapshot> studentByNumber(UUID schoolId, String studentNumber) {
		return this.jdbc.sql(STUDENT + " where ss.school_id = :s and ss.student_number = :n")
			.param("s", schoolId)
			.param("n", studentNumber)
			.query(DefaultPeopleImports::snapshot)
			.optional();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<StudentSnapshot> student(UUID schoolId, UUID studentId) {
		return this.jdbc.sql(STUDENT + " where ss.school_id = :s and ss.student_id = :st"
				+ " order by (ss.status in ('PENDING', 'ACTIVE')) desc, ss.created_at desc, ss.id desc limit 1")
			.param("s", schoolId)
			.param("st", studentId)
			.query(DefaultPeopleImports::snapshot)
			.optional();
	}

	@Override
	@Transactional(readOnly = true)
	public List<StudentCandidate> studentCandidates(UUID schoolId, String nameFold, LocalDate dateOfBirth,
			double minSimilarity, int limit) {
		if (nameFold == null || nameFold.isBlank() || dateOfBirth == null) {
			return List.of();
		}
		// The % operator uses the GIN trigram index; the explicit threshold is the configured one.
		return this.jdbc.sql("""
				select ss.student_id, u.display_name, u.date_of_birth, ss.student_number,
				       similarity(sp.name_fold, :fold) as sim
				from student_profiles sp
				join school_students ss on ss.student_id = sp.user_id and ss.school_id = :s and ss.status in ('PENDING', 'ACTIVE')
				join users u on u.id = sp.user_id
				where sp.name_fold % :fold and similarity(sp.name_fold, :fold) >= :min
				  and u.date_of_birth between cast(:dob as date) - 366 and cast(:dob as date) + 366
				order by sim desc, ss.student_id limit :limit""")
			.param("fold", nameFold)
			.param("s", schoolId)
			.param("min", minSimilarity)
			.param("dob", dateOfBirth)
			.param("limit", limit)
			.query((rs, i) -> new StudentCandidate(Rows.uuid(rs, "student_id"), rs.getString("display_name"),
					Rows.date(rs, "date_of_birth"), rs.getString("student_number"), rs.getDouble("sim")))
			.list();
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.OptionalLong remainingStudentCapacity(UUID schoolId) {
		java.util.OptionalLong limit = this.features.limit("max_students", schoolId);
		if (limit.isEmpty()) {
			return limit;
		}
		long active = this.jdbc.sql("select count(*) from school_students where school_id = :s and status in ('PENDING', 'ACTIVE')")
			.param("s", schoolId)
			.query(Long.class)
			.single();
		return java.util.OptionalLong.of(Math.max(0, limit.getAsLong() - active));
	}

	@Override
	public UUID createStudent(UUID schoolId, UUID actorId, NewStudent s) {
		return this.students.createForImport(actorId, schoolId,
				new CreateStudent(s.givenName(), s.middleName(), s.familyName(), s.dateOfBirth(), s.gender(),
						s.studentNumber(), s.campusId(), s.username(), null, s.classId(), null),
				s.externalRef());
	}

	@Override
	public void updateStudent(UUID schoolId, UUID actorId, UUID studentId, StudentChange c, String externalRef) {
		this.students.updateForImport(actorId, schoolId, studentId, new UpdateStudent(c.givenName(), c.middleName(),
				c.familyName(), c.dateOfBirth(), c.gender(), c.studentNumber(), c.campusId(), c.username(), null, null),
				externalRef);
	}

	@Override
	@Transactional(readOnly = true)
	public List<StaffSnapshot> staffByContact(UUID schoolId, String phoneE164, String email) {
		if (phoneE164 == null && email == null) {
			return List.of();
		}
		return this.jdbc.sql("""
				select ss.id, ss.user_id, ss.staff_type, ss.job_title, ss.campus_id, ss.external_ref, u.display_name
				from school_staff ss join users u on u.id = ss.user_id
				where ss.school_id = :s and ss.status in ('INVITED', 'ACTIVE')
				  and ((cast(:phone as text) is not null and u.phone_e164 = :phone)
				       or (cast(:email as text) is not null and u.email = cast(:email as citext)))
				order by ss.id""")
			.param("s", schoolId)
			.param("phone", phoneE164)
			.param("email", email)
			.query((rs, i) -> new StaffSnapshot(Rows.uuid(rs, "id"), Rows.uuid(rs, "user_id"), rs.getString("staff_type"),
					rs.getString("job_title"), Rows.uuid(rs, "campus_id"), rs.getString("external_ref"),
					rs.getString("display_name")))
			.list();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<StaffSnapshot> staff(UUID schoolId, UUID staffId) {
		return this.jdbc.sql("""
				select ss.id, ss.user_id, ss.staff_type, ss.job_title, ss.campus_id, ss.external_ref, u.display_name
				from school_staff ss join users u on u.id = ss.user_id
				where ss.school_id = :s and ss.id = :id and ss.status in ('INVITED', 'ACTIVE')""")
			.param("s", schoolId)
			.param("id", staffId)
			.query((rs, i) -> new StaffSnapshot(Rows.uuid(rs, "id"), Rows.uuid(rs, "user_id"), rs.getString("staff_type"),
					rs.getString("job_title"), Rows.uuid(rs, "campus_id"), rs.getString("external_ref"),
					rs.getString("display_name")))
			.optional();
	}

	@Override
	public NewStaffResult addStaff(UUID schoolId, UUID actorId, NewStaff s) {
		Provisioned p = this.staff.addForImport(actorId, schoolId,
				new AddStaff(s.phone(), s.email(), "ET", s.givenName(), s.familyName(), s.displayName(),
						StaffType.valueOf(s.staffType()), s.jobTitle(), null, s.campusId(), null, null),
				s.externalRef());
		return new NewStaffResult(p.staffId(), p.userId(), p.accountCreated());
	}

	@Override
	public void updateStaff(UUID schoolId, UUID actorId, UUID staffId, String jobTitle, UUID campusId,
			String externalRef) {
		this.staff.updateForImport(actorId, schoolId, staffId, jobTitle, campusId, externalRef);
	}

	private static StudentSnapshot snapshot(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		return new StudentSnapshot(Rows.uuid(rs, "student_id"), Rows.uuid(rs, "enrollment_id"), rs.getString("status"),
				rs.getString("display_name"), rs.getString("given_name"), rs.getString("middle_name"),
				rs.getString("family_name"), Rows.date(rs, "date_of_birth"), rs.getString("gender"),
				rs.getString("student_number"), Rows.uuid(rs, "campus_id"), rs.getString("username"));
	}

}
