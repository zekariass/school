package com.nemaz.school.imports.application;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.academic.ClassMemberships;
import com.nemaz.school.academic.StructureImports.YearInfo;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.imports.application.ImportModel.Action;
import com.nemaz.school.imports.application.ImportModel.Row;
import com.nemaz.school.imports.application.SchoolLookups.ClassMatch;
import com.nemaz.school.imports.application.SchoolLookups.Loaded;
import com.nemaz.school.imports.domain.Cells;
import com.nemaz.school.imports.domain.ImportType;
import com.nemaz.school.people.PeopleImports;
import com.nemaz.school.people.PeopleImports.StudentSnapshot;

import org.springframework.stereotype.Component;

import static com.nemaz.school.imports.application.HandlerSupport.str;
import static com.nemaz.school.imports.application.HandlerSupport.uuid;

/**
 * Enrollments: puts students that already exist (by student number or external id) into classes that already exist
 * (by name, in the current year). A student already in the class is left alone; a student in another class stays
 * there too (moving is a decision for a person).
 */
@Component
class EnrollmentHandler implements RowHandler {

	private final PeopleImports people;

	private final ClassMemberships memberships;

	private final SchoolLookups lookups;

	private final ExternalMappings mappings;

	EnrollmentHandler(PeopleImports people, ClassMemberships memberships, SchoolLookups lookups, ExternalMappings mappings) {
		this.people = people;
		this.memberships = memberships;
		this.lookups = lookups;
		this.mappings = mappings;
	}

	@Override
	public ImportType type() {
		return ImportType.ENROLLMENTS;
	}

	@Override
	public Session open(JobContext context) {
		return new EnrollmentSession(context, this.lookups.load(context.schoolId()));
	}

	private final class EnrollmentSession implements Session {

		private final JobContext ctx;

		private final Loaded school;

		private final Map<String, Integer> seen = new HashMap<>();

		EnrollmentSession(JobContext ctx, Loaded school) {
			this.ctx = ctx;
			this.school = school;
		}

		@Override
		public Planned plan(RowInput in) {
			Draft d = new Draft(in.sourceWarnings());
			String number = Cells.identifier(in.get("student_number"));
			String external = Cells.identifier(in.get("external_id"));
			if (number == null && external == null) {
				d.error("student_number", "student_required");
			}
			if (in.get("class_name") == null) {
				d.error("class_name", "required");
			}
			if (!d.ok()) {
				return d.invalid();
			}
			UUID schoolId = this.ctx.schoolId();
			StudentSnapshot byExternal = null;
			if (external != null) {
				UUID mapped = EnrollmentHandler.this.mappings.find(this.ctx.externalSystemId(), "STUDENT", external).orElse(null);
				byExternal = (mapped != null) ? EnrollmentHandler.this.people.student(schoolId, mapped).orElse(null) : null;
			}
			StudentSnapshot byNumber = (number != null) ? EnrollmentHandler.this.people.studentByNumber(schoolId, number).orElse(null)
					: null;
			if (byExternal != null && byNumber != null && !byExternal.studentId().equals(byNumber.studentId())) {
				d.error("student_number", "identifier_conflict");
				return d.invalid();
			}
			StudentSnapshot student = (byExternal != null) ? byExternal : byNumber;
			if (student == null) {
				d.error((number != null) ? "student_number" : "external_id", "student_not_found");
			}
			else if (!student.live()) {
				d.error((number != null) ? "student_number" : "external_id", "student_not_active");
			}
			YearInfo year = this.school.currentYear().orElse(null);
			ClassMatch match = this.school.classByName(in.get("class_name"), (year != null) ? year.id() : null);
			if (match.found() == null) {
				d.error("class_name", match.ambiguous() ? "class_ambiguous" : (match.archivedOnly() ? "class_archived" : "class_not_found"),
						in.get("class_name"));
			}
			if (!d.ok()) {
				return d.invalid();
			}
			Integer first = this.seen.putIfAbsent(student.studentId() + "|" + match.found().id(), in.rowNumber());
			if (first != null) {
				d.error("class_name", "duplicate_in_file", String.valueOf(first));
				return d.invalid();
			}
			d.put("studentId", student.studentId().toString());
			d.put("studentName", student.displayName());
			d.put("classId", match.found().id().toString());
			d.put("className", match.found().name());
			d.entityType = "CLASS_STUDENT";
			d.entityId = student.studentId();
			boolean member = EnrollmentHandler.this.memberships.activeStudentIds(match.found().id()).contains(student.studentId());
			return d.valid(member ? Action.NONE : Action.UPDATE);
		}

		@Override
		public Applied apply(Row row) {
			Map<String, Object> n = row.normalized();
			UUID studentId = uuid(n, "studentId");
			UUID classId = uuid(n, "classId");
			UUID schoolId = this.ctx.schoolId();
			if (studentId == null || classId == null) {
				throw ApiException.conflict("import.target_missing");
			}
			StudentSnapshot student = EnrollmentHandler.this.people.student(schoolId, studentId)
				.filter(StudentSnapshot::live)
				.orElseThrow(() -> ApiException.conflict("import.target_missing"));
			if (EnrollmentHandler.this.memberships.activeStudentIds(classId).contains(student.studentId())) {
				return new Applied("UNCHANGED", "CLASS_STUDENT", studentId, null);
			}
			EnrollmentHandler.this.memberships.addStudent(schoolId, classId, studentId, this.ctx.actorId());
			return new Applied("UPDATED", "CLASS_STUDENT", studentId, null);
		}

	}

}
